package qbotics.client.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import qbotics.Qbotics;
import qbotics.agent.AgentChat;
import qbotics.ide.Checker;
import qbotics.ide.Docs;
import qbotics.net.AgentPayloads;

/**
 * The in-game AI agent (design doc §17.8): the player talks to their own model in chat
 * ("@ai ..."), and the model looks at and works on their computers through tools.
 *
 * <p>It runs here, in the player's game: the model is theirs (a local one such as Ollama or
 * LM Studio, or any service with an OpenAI-style API), and its address and key stay on their
 * PC. What a tool does in the world is asked of the server (AgentTools), as this player.
 * Writing a file or running a command waits for the player's [Allow], unless auto mode is on.
 * With the server sharing replies (teacher and student sessions), the question and answer go
 * to everyone through the server instead of only to this player.
 */
public final class ClientAgent {
    private ClientAgent() {
    }

    public static final String PREFIX = "@ai";
    private static final int MAX_STEPS = 12;
    private static final int MAX_HISTORY = 40;
    private static final int SERVER_TIMEOUT_SECONDS = 60;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final List<JsonObject> history = new ArrayList<>();
    private static volatile boolean busy;
    private static volatile boolean stopping;
    private static volatile CompletableFuture<Boolean> approval;

    // What the server allows, sent when joining (AgentPayloads.Settings).
    private static volatile boolean serverEnabled = true;
    private static volatile boolean serverShared;
    // The model the server offers, if any: its name; it is called through the server, never directly.
    private static volatile boolean serverOffers;
    private static volatile String serverModel = "";
    private static volatile boolean serverVision;
    private static final Map<Integer, CompletableFuture<AgentPayloads.ModelReply>> MODEL_PENDING = new ConcurrentHashMap<>();

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, CompletableFuture<String>> PENDING = new ConcurrentHashMap<>();

    // ---- from the game ---------------------------------------------------------------

    /** A chat message the player is about to send. Returns true if it was for the agent (and must not be sent). */
    public static boolean onChat(String message) {
        String text = message.strip();
        if (!text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                || (text.length() > PREFIX.length() && !Character.isWhitespace(text.charAt(PREFIX.length())))) {
            return false;
        }
        String question = text.substring(PREFIX.length()).strip();
        if (question.isEmpty()) {
            help();
            return true;
        }
        ask(question, null);
        return true;
    }

    // ---- for the editor's Chat panel -------------------------------------------------

    private static volatile Boolean reachable;
    private static volatile long checkedAt;
    private static volatile boolean checking;
    /** What the agent is waiting for the player to allow, or null. */
    private static volatile String pendingWhat;

    /**
     * Whether the agent can be asked: a model is set and answered lately. Looks again (in the
     * background) every 30 seconds, so a model started later shows up.
     */
    public static boolean available() {
        AgentSettings settings;
        try {
            settings = AgentSettings.get();
        } catch (RuntimeException e) {
            return false; // no game (the editor's preview tool)
        }
        if (usingServer(settings)) {
            return true; // the server tested it, and offers it only while it works
        }
        if (settings.model.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!checking && now - checkedAt > 30_000) {
            checking = true;
            checkedAt = now;
            if (badAddress(settings.url) != null) {
                reachable = false; // a saved address that isn't one (this is asked while drawing: it must not throw)
                checking = false;
                return false;
            }
            try {
                String url = settings.url.replaceAll("/+$", "") + "/models";
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET();
                if (!settings.key.isEmpty()) {
                    request.header("Authorization", "Bearer " + settings.key);
                }
                // Any answer means the server is up (not every one lists its models there).
                HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                    reachable = error == null;
                    checking = false;
                });
            } catch (RuntimeException e) {
                reachable = false;
                checking = false;
            }
        }
        return Boolean.TRUE.equals(reachable);
    }

    /** What is wrong with a model's address as typed, or null if it is one: http(s)://host[:port][/path]. */
    public static @org.jspecify.annotations.Nullable String badAddress(String url) {
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "it must start with http:// or https://";
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return "it names no computer after " + scheme + "://";
            }
            return null;
        } catch (java.net.URISyntaxException e) {
            return "it has a space or a character an address can't have";
        }
    }

    /** /ai url: saved only if it is an address, then tried at once so the player hears whether anything answers. */
    private static int setAddress(String typed) {
        String url = typed.strip();
        String wrong = badAddress(url);
        if (wrong != null) {
            say("\"" + url + "\" isn't a model's address: " + wrong + ". For example: /ai url http://localhost:11434/v1", ChatFormatting.RED);
            return 0;
        }
        set(s -> s.url = url, "The model's address is now " + url + ". Trying it...");
        checkedAt = 0;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/models")).timeout(Duration.ofSeconds(4)).GET().build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
                if (error == null) {
                    say("Something answers at that address.", ChatFormatting.GRAY);
                } else {
                    say("Nothing answered at that address. It is saved; check that the model is running and the address is right.", ChatFormatting.YELLOW);
                }
            }));
        } catch (RuntimeException e) {
            say("That address couldn't be tried: " + e.getMessage(), ChatFormatting.YELLOW);
        }
        return 1;
    }

    /** A question from the editor: what it shows is the question; the model is also told what is open there. */
    public static void askFromEditor(String question, String context) {
        if (question.isBlank()) {
            return;
        }
        ask(question.strip(), context);
    }

    public static boolean busy() {
        return busy;
    }

    /** What the agent asks the player to allow, while it waits; else null. */
    public static String pendingApproval() {
        return pendingWhat;
    }

    public static void stopTurn() {
        if (busy) {
            stop();
        }
    }

    public static void onSettings(AgentPayloads.Settings settings) {
        serverEnabled = settings.enabled();
        serverShared = settings.shared();
        serverOffers = settings.offered();
        serverModel = settings.model();
        serverVision = settings.vision();
    }

    public static void onModelReply(AgentPayloads.ModelReply reply) {
        CompletableFuture<AgentPayloads.ModelReply> waiting = MODEL_PENDING.remove(reply.id());
        if (waiting != null) {
            waiting.complete(reply);
        }
    }

    /** True when questions go to the server's model: it offers one, and the player hasn't chosen their own. */
    private static boolean usingServer(AgentSettings settings) {
        return serverOffers && !"own".equals(settings.use);
    }

    /** Whether the model in use can be shown pictures. */
    private static boolean canSee(AgentSettings settings) {
        return settings.vision && (!usingServer(settings) || serverVision);
    }

    public static void onToolResult(AgentPayloads.ToolResult result) {
        CompletableFuture<String> waiting = PENDING.remove(result.id());
        if (waiting != null) {
            waiting.complete(result.text());
        }
    }

    /** /ai ... : settings, and answering the agent's questions. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> ai = Commands.literal("ai").executes(c -> help());
        ai.then(Commands.literal("allow").executes(c -> answer(true)));
        ai.then(Commands.literal("deny").executes(c -> answer(false)));
        ai.then(Commands.literal("stop").executes(c -> stop()));
        ai.then(Commands.literal("clear").executes(c -> {
            synchronized (history) {
                history.clear();
            }
            say("Forgot the conversation.", ChatFormatting.GRAY);
            return 1;
        }));
        ai.then(Commands.literal("status").executes(c -> status()));
        ai.then(Commands.literal("url").then(Commands.argument("url", StringArgumentType.greedyString()).executes(c ->
                setAddress(StringArgumentType.getString(c, "url")))));
        ai.then(Commands.literal("model").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c ->
                set(s -> s.model = StringArgumentType.getString(c, "name").strip(), "The model is now " + StringArgumentType.getString(c, "name").strip()))));
        ai.then(Commands.literal("key").then(Commands.argument("key", StringArgumentType.greedyString()).executes(c -> {
            String key = StringArgumentType.getString(c, "key").strip();
            return set(s -> s.key = key.equalsIgnoreCase("none") ? "" : key, key.equalsIgnoreCase("none") ? "No key." : "Key saved (on this PC only).");
        })));
        ai.then(Commands.literal("auto")
                .then(Commands.literal("on").executes(c -> set(s -> s.auto = true, "Auto mode on: the agent writes files and runs commands without asking.")))
                .then(Commands.literal("off").executes(c -> set(s -> s.auto = false, "Auto mode off: the agent asks before writing files or running commands."))));
        ai.then(Commands.literal("think")
                .then(Commands.literal("off").executes(c -> set(s -> s.thinking = "off", "Thinking off: the quickest answers.")))
                .then(Commands.literal("low").executes(c -> set(s -> s.thinking = "low", "Thinking low: a little thought before answering (the default).")))
                .then(Commands.literal("medium").executes(c -> set(s -> s.thinking = "medium", "Thinking medium: more thought, slower.")))
                .then(Commands.literal("high").executes(c -> set(s -> s.thinking = "high", "Thinking high: as much as the model likes; slow, for hard problems."))));
        ai.then(Commands.literal("use")
                .then(Commands.literal("server").executes(c -> set(s -> s.use = "server", "Using this server's AI model when it offers one.")))
                .then(Commands.literal("own").executes(c -> set(s -> s.use = "own", "Using your own model (/ai url, /ai model), even where the server offers one."))));
        ai.then(Commands.literal("vision")
                .then(Commands.literal("on").executes(c -> set(s -> s.vision = true, "Vision on: \"@ai look ...\" shows the model your view.")))
                .then(Commands.literal("off").executes(c -> set(s -> s.vision = false, "Vision off (for models that can't see images)."))));
        dispatcher.register(ai);
    }

    private static int set(java.util.function.Consumer<AgentSettings> change, String said) {
        AgentSettings settings = AgentSettings.get();
        change.accept(settings);
        settings.save();
        say(said, ChatFormatting.GRAY);
        return 1;
    }

    private static int help() {
        if (usingServer(AgentSettings.get())) {
            say("This server offers an AI model (" + serverModel + "): type \"@ai\" and a question in chat, e.g. \"@ai why doesn't my "
                    + "sorter move coal?\"" + (serverVision ? ", or \"@ai look ...\" to show it your view" : "") + ". Nothing to set up.", ChatFormatting.GRAY);
            say("  /ai auto on|off   /ai status   /ai clear (forget the conversation)   /ai stop   /ai use own (your own model instead)", ChatFormatting.GRAY);
            return 1;
        }
        say("The AI agent: type \"@ai\" and a question in chat, e.g. \"@ai why doesn't my sorter move coal?\". "
                + "\"@ai look ...\" also shows it your view. It uses your own model:", ChatFormatting.GRAY);
        say("  /ai url http://localhost:11434/v1   (Ollama; LM Studio: http://localhost:1234/v1)", ChatFormatting.GRAY);
        say("  /ai model qwen3-vl:8b   /ai key <key, or none>   /ai auto on|off   /ai vision on|off   /ai think off|low|medium|high", ChatFormatting.GRAY);
        say("  /ai status   /ai clear (forget the conversation)   /ai stop", ChatFormatting.GRAY);
        return 1;
    }

    private static int status() {
        AgentSettings s = AgentSettings.get();
        if (usingServer(s)) {
            say("Using this server's AI model: " + serverModel + (serverVision ? " (it can see pictures)" : "") + ". Auto mode "
                    + (s.auto ? "on" : "off") + ". /ai use own to use your own instead."
                    + (serverShared ? " This server shows AI questions and answers to everyone." : ""), ChatFormatting.GRAY);
            return 1;
        }
        if (serverOffers) {
            say("This server offers its AI model (" + serverModel + "), but you chose your own: /ai use server to switch.", ChatFormatting.GRAY);
        }
        say("Model " + (s.model.isEmpty() ? "(not set: /ai model <name>)" : s.model) + " at " + s.url
                + (s.key.isEmpty() ? "" : ", with a key") + ". Auto mode " + (s.auto ? "on" : "off") + ", vision " + (s.vision ? "on" : "off") + ", thinking " + s.thinking + "."
                + (serverEnabled ? "" : " This server has turned the agent's tools off.")
                + (serverShared ? " This server shows AI questions and answers to everyone." : ""), ChatFormatting.GRAY);
        return 1;
    }

    /** The player's answer to [Allow] / [Deny] (the screenshot run gives it directly). */
    public static int answer(boolean yes) {
        CompletableFuture<Boolean> waiting = approval;
        if (waiting == null) {
            say("The agent isn't waiting for an answer.", ChatFormatting.GRAY);
        } else {
            waiting.complete(yes);
        }
        return 1;
    }

    private static int stop() {
        if (!busy) {
            say("The agent isn't working on anything.", ChatFormatting.GRAY);
            return 1;
        }
        stopping = true;
        CompletableFuture<Boolean> waiting = approval;
        if (waiting != null) {
            waiting.complete(false);
        }
        say("Stopping...", ChatFormatting.GRAY);
        return 1;
    }

    // ---- a turn: the question, the model, its tools, the answer ----------------------------

    private static void ask(String question, String context) {
        AgentSettings settings = AgentSettings.get();
        if (!usingServer(settings) && settings.model.isEmpty()) {
            say((serverOffers ? "You chose your own model (/ai use own) but haven't set it up: "
                    : "This server offers no AI model, so set up your own: ")
                    + "/ai model <name> (and /ai url if it isn't Ollama on this PC). /ai for help.", ChatFormatting.YELLOW);
            return;
        }
        if (busy) {
            say("The agent is still working on the last question (/ai stop to stop it).", ChatFormatting.YELLOW);
            return;
        }
        busy = true;
        stopping = false;
        boolean look = question.regionMatches(true, 0, "look", 0, 4) && (question.length() == 4 || !Character.isLetter(question.charAt(4)));
        if (serverShared) {
            Qbotics.platform().sendToServer(new AgentPayloads.Share(false, question));
        } else {
            show(AgentChat.question(Minecraft.getInstance().getUser().getName(), question));
        }
        Thread.ofVirtual().name("qbotics-ai").start(() -> {
            try {
                turn(context == null ? question : question + "\n\n" + context, look && canSee(settings), settings);
            } catch (Exception e) {
                say("The agent stopped: " + describe(e), ChatFormatting.RED);
            } finally {
                busy = false;
                approval = null;
                stripImages();
            }
        });
    }

    private static void turn(String question, boolean look, AgentSettings settings) throws Exception {
        synchronized (history) {
            if (history.isEmpty()) {
                history.add(message("system", systemPrompt()));
            }
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        if (look) {
            say("Looking...", ChatFormatting.DARK_GRAY);
            String sees = serverTool("look", "{}");
            String image = Screenshots.capture().get(10, TimeUnit.SECONDS);
            JsonArray parts = new JsonArray();
            parts.add(textPart(question + "\n\n(The game says the player is looking at: " + sees + ")"));
            parts.add(imagePart(image));
            user.add("content", parts);
        } else {
            user.addProperty("content", question);
        }
        addHistory(user);

        for (int step = 0; step < MAX_STEPS && !stopping; step++) {
            JsonObject reply = complete(settings);
            String content = reply.has("content") && reply.get("content").isJsonPrimitive() ? reply.get("content").getAsString() : "";
            List<JsonObject> calls = toolCalls(reply, content);
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.addProperty("content", content);
            if (reply.has("tool_calls")) {
                assistant.add("tool_calls", reply.get("tool_calls"));
            }
            addHistory(assistant);
            if (calls.isEmpty()) {
                finish(withoutThinking(content));
                return;
            }
            for (JsonObject call : calls) {
                if (stopping) {
                    break;
                }
                String name = call.getAsJsonObject("function").get("name").getAsString();
                String args = argumentsOf(call.getAsJsonObject("function"));
                String result = runTool(name, args, settings);
                JsonObject toolMessage = message("tool", result);
                toolMessage.addProperty("tool_call_id", call.has("id") ? call.get("id").getAsString() : name);
                addHistory(toolMessage);
                if (name.equals("screenshot") && result.startsWith("data:image")) {
                    // A tool's answer can't hold a picture in every server: it comes as the next message.
                    toolMessage.addProperty("content", "The screenshot follows.");
                    JsonObject picture = new JsonObject();
                    picture.addProperty("role", "user");
                    JsonArray parts = new JsonArray();
                    parts.add(textPart("The screenshot you asked for:"));
                    parts.add(imagePart(result));
                    picture.add("content", parts);
                    addHistory(picture);
                }
            }
        }
        say(stopping ? "Stopped." : "The agent took too many steps without answering; try asking more simply.", ChatFormatting.GRAY);
    }

    private static void finish(String answer) {
        String text = answer.isBlank() ? "(no answer)" : answer.strip();
        if (serverShared) {
            Qbotics.platform().sendToServer(new AgentPayloads.Share(true, text.length() > 7_900 ? text.substring(0, 7_900) : text));
        } else {
            show(AgentChat.answer(Minecraft.getInstance().getUser().getName(), text));
        }
    }

    /** Set when the model's server refused the thinking settings (a hosted API without them): they aren't sent again. */
    private static volatile boolean noTemplateSwitches;

    /** One call to the model. */
    private static JsonObject complete(AgentSettings settings) throws Exception {
        if (usingServer(settings)) {
            return completeOnServer(settings);
        }
        try {
            return complete(settings, !noTemplateSwitches);
        } catch (IllegalStateException refused) {
            if (!noTemplateSwitches && refused.getMessage() != null && (refused.getMessage().contains("chat_template_kwargs") || refused.getMessage().contains("reasoning_effort"))) {
                noTemplateSwitches = true;
                return complete(settings, false);
            }
            throw refused;
        }
    }

    /** The server's model: the conversation goes to the server, which calls the model (its address and key stay there). */
    private static JsonObject completeOnServer(AgentSettings settings) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray messages = new JsonArray();
        synchronized (history) {
            history.forEach(messages::add);
        }
        body.add("messages", messages);
        body.add("tools", tools(settings));
        int id = NEXT_ID.getAndIncrement();
        CompletableFuture<AgentPayloads.ModelReply> reply = new CompletableFuture<>();
        MODEL_PENDING.put(id, reply);
        Qbotics.platform().sendToServer(new AgentPayloads.ModelRequest(id, GSON.toJson(body)));
        AgentPayloads.ModelReply answer;
        try {
            answer = reply.get(7, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("the server's AI didn't answer in time");
        } finally {
            MODEL_PENDING.remove(id);
        }
        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(answer.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the server's AI answered " + answer.status() + " with something unreadable");
        }
        if (answer.status() != 200 || !parsed.has("choices")) {
            String said = parsed.has("error") && parsed.get("error").isJsonObject() && parsed.getAsJsonObject("error").has("message")
                    ? parsed.getAsJsonObject("error").get("message").getAsString() : answer.body();
            throw new IllegalStateException(said.length() > 300 ? said.substring(0, 300) : said);
        }
        return parsed.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }

    private static JsonObject complete(AgentSettings settings, boolean templateSwitches) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model);
        if (templateSwitches) {
            // How much models that think before answering (Qwen3 and others) do (/ai think): "off" through the
            // model's chat template (llama.cpp's server, vLLM), the rest as OpenAI's reasoning_effort.
            // Measured on Qwen with llama.cpp: full thinking 54 s, low 6 s, off 3 s for the same question.
            switch (settings.thinking) {
                case "off" -> {
                    JsonObject switches = new JsonObject();
                    switches.addProperty("enable_thinking", false);
                    body.add("chat_template_kwargs", switches);
                }
                case "low", "medium" -> body.addProperty("reasoning_effort", settings.thinking);
                default -> {
                    // high: as much as the model likes
                }
            }
        }
        JsonArray messages = new JsonArray();
        synchronized (history) {
            history.forEach(messages::add);
        }
        body.add("messages", messages);
        body.add("tools", tools(settings));
        body.addProperty("stream", false);
        if (badAddress(settings.url) != null) {
            throw new IllegalStateException("\"" + settings.url + "\" isn't a model's address (" + badAddress(settings.url) + "). Set it with /ai url");
        }
        String url = settings.url.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (!settings.key.isEmpty()) {
            request.header("Authorization", "Bearer " + settings.key);
        }
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new IllegalStateException("couldn't reach the model at " + settings.url + ". Is it running? (/ai url to change it)");
        }
        if (response.statusCode() != 200) {
            String said = response.body().length() > 300 ? response.body().substring(0, 300) : response.body();
            if (said.contains("image") && said.toLowerCase(java.util.Locale.ROOT).contains("support")) {
                said += " (this model can't see pictures: /ai vision off, or use a vision model)";
            }
            throw new IllegalStateException("the model answered " + response.statusCode() + ": " + said);
        }
        JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        return parsed.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }

    private static final Pattern TEXT_CALL = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);

    /** The model's tool calls: as the API gives them, or, from models served without tool support, written in the text. */
    private static List<JsonObject> toolCalls(JsonObject reply, String content) {
        List<JsonObject> calls = new ArrayList<>();
        if (reply.has("tool_calls") && reply.get("tool_calls").isJsonArray()) {
            for (JsonElement call : reply.getAsJsonArray("tool_calls")) {
                calls.add(call.getAsJsonObject());
            }
        }
        if (calls.isEmpty()) {
            Matcher m = TEXT_CALL.matcher(content);
            int n = 0;
            while (m.find()) {
                try {
                    JsonObject written = JsonParser.parseString(m.group(1)).getAsJsonObject();
                    JsonObject function = new JsonObject();
                    function.addProperty("name", written.get("name").getAsString());
                    function.addProperty("arguments", written.has("arguments") ? written.get("arguments").toString() : "{}");
                    JsonObject call = new JsonObject();
                    call.addProperty("id", "call_" + n++);
                    call.addProperty("type", "function");
                    call.add("function", function);
                    calls.add(call);
                } catch (RuntimeException e) {
                    // Not a call after all.
                }
            }
        }
        return calls;
    }

    private static String argumentsOf(JsonObject function) {
        JsonElement args = function.get("arguments");
        if (args == null || args.isJsonNull()) {
            return "{}";
        }
        return args.isJsonPrimitive() ? args.getAsString() : args.toString();
    }

    private static String withoutThinking(String content) {
        return content.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)^.*?</think>", "")
                .replaceAll("(?s)<tool_call>.*?</tool_call>", "").strip();
    }

    // ---- the tools ---------------------------------------------------------------------

    private static JsonArray tools(AgentSettings settings) {
        JsonArray tools = new JsonArray();
        String computer = "Computer name; leave out for the one the player opened last";
        tools.add(tool("computers", "The player's computers (and laptop): names, whether running, which they opened last.", Map.of()));
        tools.add(tool("look", "What the player is looking at in the game: the block, a device on it (name, kind, address), "
                + "what a plate points at, and what a container holds.", Map.of()));
        if (canSee(settings)) {
            tools.add(tool("screenshot", "A picture of what the player sees now. Use when the layout of blocks matters.", Map.of()));
        }
        tools.add(tool("devices", "Every device on a computer's cable network: kind, name (use it in code), addresses, what plates point at.",
                Map.of("computer", computer)));
        tools.add(tool("list_files", "The files in a folder on a computer.", Map.of("computer", computer, "folder", "Folder, e.g. / or /lib")));
        tools.add(tool("read_file", "A file on a computer.", Map.of("computer", computer, "path", "The file, e.g. /main.py"), "path"));
        tools.add(tool("write_file", "Writes a whole file on a computer (creates or replaces it). The player may have to allow it.",
                Map.of("computer", computer, "path", "The file, e.g. /main.py", "content", "The complete new text of the file"), "path", "content"));
        tools.add(tool("run", "Runs a shell command on a computer as if typed (\"run test.py\", \"ls\", \"devices\"); gives its output and "
                + "whether it worked. Stopped after " + qbotics.agent.AgentTools.RUN_SECONDS + " seconds. The player may have to allow it.",
                Map.of("computer", computer, "command", "The command"), "command"));
        tools.add(tool("python", "Your scratchpad: runs Python on a computer and gives what it printed, without touching its files. "
                + "Look-only by default: it can read anything (devices, counts, contents, storage, sensors' entities and blocks, env, "
                + "watches, recipes) but anything that would change something (moving items, using, attacking, breaking, placing, "
                + "writing, files) is refused, so use it freely to find out how things stand before answering. With change \"true\" "
                + "it may change things, and the player may have to allow it. Stopped after " + qbotics.agent.AgentTools.RUN_SECONDS + " seconds.",
                Map.of("computer", computer, "code", "The Python to run", "change", "\"true\" only if it must change things"), "code"));
        tools.add(tool("stop_program", "Stops the program running on a computer (as Ctrl+C), so you can run your tests there: a computer "
                + "running a program (an events.run() loop, say) is busy until then. The computers tool says what each runs. The player "
                + "is asked first. Start it again with start_program when done.", Map.of("computer", computer)));
        tools.add(tool("start_program", "Starts a program running on a computer, in the background, as if typed: by default the one "
                + "stop_program stopped there. Use it to put back what you stopped. The player is asked first.",
                Map.of("computer", computer, "command", "What to start, e.g. \"run sorter.py\"; leave out for what you stopped")));
        tools.add(tool("check_code", "Checks Python for mistakes without running it: a file on a computer, or code given directly.",
                Map.of("computer", computer, "path", "A file to check", "code", "Or the code itself")));
        tools.add(tool("find_items", "Finds items in this game, every mod's included: by word (\"copper\"), pattern (\"*_ingot\"), "
                + "tag (\"#c:ores\") or mod (\"@create\"). Gives each item's full name (mod:name) and display name.",
                Map.of("query", "What to look for"), "query"));
        tools.add(tool("item_info", "One item as this game has it: display name, mod, tags, stack size, fuel and food values, "
                + "how many recipes make and use it.", Map.of("item", "The item: mod:name, or its display name"), "item"));
        tools.add(tool("recipes", "The recipes in this game (every mod's, as this modpack has them) that make an item, or with "
                + "uses=true those it is used in: crafting grids, furnaces, other mods' machines.",
                Map.of("item", "The item: mod:name, or its display name", "uses", "\"true\" for the recipes it is used in"), "item"));
        tools.add(tool("plan", "How to make an item from what the player carries, worked out by the game: every step in order "
                + "(each recipe, its station and how many times), what comes from their inventory, and what is missing.",
                Map.of("item", "The item: mod:name, or its display name", "count", "How many (default 1)"), "item"));
        tools.add(tool("search_manual", "Searches the Qbotics manual (devices, the logistics/events/network/display modules, limits).",
                Map.of("query", "What to look for, e.g. \"furnace faces\" or \"events on_change\""), "query"));
        tools.add(tool("mods", "The mods loaded in this game, with versions; with a query, those matching it and what each says it is. "
                + "Check here before suggesting a mod or its items: only these are installed.",
                Map.of("query", "A word to narrow it, e.g. \"jetpack\"; leave out for all")));
        tools.add(tool("mod_docs", "Searches what the other mods in this game say about themselves, in this version: their guidebooks "
                + "(Patchouli), tooltips and JEI information pages. Use it to explain how another mod's things work.",
                Map.of("query", "What to look for, e.g. \"jetpack fuel\" or \"bee breeding\""), "query"));
        tools.add(tool("controls", "The player's key bindings, as they have set them: which key does what, every mod's included. "
                + "Use it for \"how do I use/turn on...\" questions.",
                Map.of("query", "A mod or action, e.g. \"jetpack\" or \"hover\"; leave out for all")));
        return tools;
    }

    private static JsonObject tool(String name, String description, Map<String, String> parameters, String... needed) {
        JsonObject properties = new JsonObject();
        parameters.forEach((key, about) -> {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", about);
            properties.add(key, p);
        });
        JsonArray required = new JsonArray();
        for (String key : needed) {
            required.add(key);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", schema);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static String runTool(String name, String args, AgentSettings settings) throws Exception {
        JsonObject parsed;
        try {
            parsed = args.isBlank() ? new JsonObject() : JsonParser.parseString(args).getAsJsonObject();
        } catch (RuntimeException e) {
            return "The arguments weren't JSON: " + args;
        }
        String on = parsed.has("computer") ? " on " + parsed.get("computer").getAsString() : "";
        switch (name) {
            case "screenshot":
                if (!canSee(settings)) {
                    return "This model can't be shown pictures.";
                }
                say("(taking a picture of the player's view)", ChatFormatting.DARK_GRAY);
                return Screenshots.capture().get(10, TimeUnit.SECONDS);
            case "check_code": {
                String code = parsed.has("code") ? parsed.get("code").getAsString() : null;
                if (code == null) {
                    if (!parsed.has("path")) {
                        return "Give path or code.";
                    }
                    code = serverTool("read_file", args);
                }
                List<Checker.Problem> problems = Checker.check(List.of(code.split("\n", -1)), Docs.moduleExports());
                if (problems.isEmpty()) {
                    return "No problems found (it isn't run, so mistakes that only show when it runs can't be seen).";
                }
                StringBuilder out = new StringBuilder();
                for (Checker.Problem problem : problems) {
                    out.append("line ").append(problem.line() + 1).append(": ").append(problem.severity().name().toLowerCase(java.util.Locale.ROOT))
                            .append(": ").append(problem.message()).append('\n');
                }
                return out.toString();
            }
            case "search_manual":
                return Manual.search(parsed.has("query") ? parsed.get("query").getAsString() : "");
            case "mods":
                return GameDocs.mods(parsed.has("query") ? parsed.get("query").getAsString() : "");
            case "mod_docs":
                return GameDocs.docs(parsed.has("query") ? parsed.get("query").getAsString() : "");
            case "controls":
                return GameDocs.controls(parsed.has("query") ? parsed.get("query").getAsString() : "");
            case "write_file": {
                String path = parsed.has("path") ? parsed.get("path").getAsString() : "?";
                String content = parsed.has("content") ? parsed.get("content").getAsString() : "";
                if (!settings.auto && !approve("write " + path + on + " (" + content.lines().count() + " lines)", content)) {
                    return "The player said no: the file wasn't written. Show them the code instead, or ask what they want.";
                }
                say("(writing " + path + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            }
            case "python": {
                String code = parsed.has("code") ? parsed.get("code").getAsString() : "";
                boolean change = parsed.has("change") && parsed.get("change").getAsString().equalsIgnoreCase("true");
                if (change && !settings.auto && !approve("run Python that may change things" + on + " (" + code.lines().count() + " lines)", code)) {
                    return "The player said no: it wasn't run. Try it look-only (leave out change), or show them the code.";
                }
                say("(" + (change ? "running" : "looking with") + " Python" + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            }
            case "stop_program": {
                if (!settings.auto && !approve("stop the program running" + (on.isEmpty() ? " on your computer" : on) + " to test there", null)) {
                    return "The player said no: it is still running. Use another computer, or try look-only elsewhere.";
                }
                say("(stopping the program" + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            }
            case "start_program": {
                String command = parsed.has("command") ? parsed.get("command").getAsString() : "";
                if (!settings.auto && !approve("start " + (command.isEmpty() ? "the program it stopped" : "\"" + command + "\"") + on, null)) {
                    return "The player said no: nothing was started.";
                }
                say("(starting " + (command.isEmpty() ? "the program again" : command) + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            }
            case "run": {
                String command = parsed.has("command") ? parsed.get("command").getAsString() : "";
                if (!settings.auto && !approve("run \"" + command + "\"" + on, null)) {
                    return "The player said no: it wasn't run.";
                }
                say("(running \"" + command + "\"" + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            }
            case "find_items", "item_info", "recipes", "plan":
                say("(" + name.replace('_', ' ') + " " + (parsed.has("item") ? parsed.get("item").getAsString()
                        : parsed.has("query") ? parsed.get("query").getAsString() : "") + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            case "read_file", "list_files", "devices", "computers", "look":
                say("(" + name.replace('_', ' ') + (parsed.has("path") ? " " + parsed.get("path").getAsString() : "") + on + ")", ChatFormatting.DARK_GRAY);
                return serverTool(name, args);
            default:
                return "There is no tool called " + name + ".";
        }
    }

    /** Asks the player in chat: [Allow] or [Deny]. A preview (the file) shows when the mouse is over the question. */
    private static boolean approve(String what, String preview) throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        approval = answer;
        pendingWhat = what;
        MutableComponent line = Component.literal("[AI] wants to " + what + ". ").withStyle(ChatFormatting.GOLD);
        if (preview != null) {
            String shown = preview.length() > 3000 ? preview.substring(0, 3000) + "\n..." : preview;
            line.withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withHoverEvent(new HoverEvent.ShowText(Component.literal(shown))));
            line.append(Component.literal("(point here to see it) ").withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal("[Allow]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/ai allow"))));
        line.append(Component.literal(" "));
        line.append(Component.literal("[Deny]").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/ai deny"))));
        show(List.of(line));
        try {
            return answer.get(5, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            say("(no answer in 5 minutes: taken as no)", ChatFormatting.GRAY);
            return false;
        } finally {
            approval = null;
            pendingWhat = null;
        }
    }

    /** A tool done by the server, as this player. */
    private static String serverTool(String name, String args) throws Exception {
        if (!serverEnabled) {
            return "This server has turned the agent's tools off.";
        }
        if (Minecraft.getInstance().getConnection() == null) {
            return "Not in a world.";
        }
        int id = NEXT_ID.getAndIncrement();
        CompletableFuture<String> result = new CompletableFuture<>();
        PENDING.put(id, result);
        Qbotics.platform().sendToServer(new AgentPayloads.ToolCall(id, name, args.length() > AgentPayloads.MAX_TEXT - 100 ? "{}" : args));
        try {
            return result.get(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "The game didn't answer in time.";
        } finally {
            PENDING.remove(id);
        }
    }

    // ---- the conversation ------------------------------------------------------------------

    private static String systemPrompt() {
        String name = Minecraft.getInstance().getUser().getName();
        String guide = "";
        try (InputStream in = ClientAgent.class.getResourceAsStream("/qbotics/helpme/AGENTS.md")) {
            if (in != null) {
                guide = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int start = guide.indexOf("## Where the code runs");
                guide = start >= 0 ? guide.substring(start) : guide;
                guide = guide.replaceAll("(?s)## Seeing the game from outside.*?(?=## The shape)", "");
            }
        } catch (java.io.IOException e) {
            // Without the guide: the manual can still be searched.
        }
        return "You are the Qbotics helper, an assistant inside Minecraft for the Qbotics mod, talking with " + name
                + " in the game's chat. You help them write, fix and understand Python programs for the mod's in-game computers, "
                + "and work out how their devices are set up.\n\n"
                + "How to work:\n"
                + "- Look before answering. Use the tools to see their computers, devices, files and what they are looking at; "
                + "don't guess names or addresses. A device's name in code must be one the devices tool lists.\n"
                + "- To change a program, read it, write the whole new file with write_file, check it with check_code, and, when it "
                + "is safe to try, run it with run. Programs that never end are stopped after " + qbotics.agent.AgentTools.RUN_SECONDS
                + " seconds; that is fine for a test.\n"
                + "- The player may have to allow writes and runs. If they say no, explain or show the code instead.\n"
                + "- Your python tool is a scratchpad on the player's computer: look-only, it can read anything their devices can "
                + "(item counts and contents, storage groups, what an Entity Sensor or Block Sensor sees, env for time and weather) and "
                + "changes nothing, so the player isn't asked. Use it to check facts and to try code before you answer.\n"
                + "- A computer running a program (computers shows running_program) can't run your code. Ask to stop it with "
                + "stop_program, do your tests, and always put it back with start_program afterwards.\n"
                + "- When unsure how something in the mod works, use search_manual.\n"
                + "- For other mods in this game: mods says what is installed; mod_docs searches their own guidebooks and tooltips, "
                + "for how their things work in this version; controls gives the player's actual keys (\"how do I turn on my jetpack\"). "
                + "Use them before answering from memory, and say when an answer comes from memory instead, since mods change between "
                + "versions.\n"
                + "- For items, tags and crafting, look them up in this game with find_items, item_info and recipes: it has other "
                + "mods, and its recipes may differ from plain Minecraft's. Use the exact item names they give in code.\n"
                + "- Replies appear in Minecraft chat, which is narrow: be brief (a few sentences), explain as to a beginner, and "
                + "put code in ``` blocks only when showing code. No tables.\n\n"
                + "About the computers' Python:\n" + guide;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject imagePart(String dataUrl) {
        JsonObject url = new JsonObject();
        url.addProperty("url", dataUrl);
        JsonObject part = new JsonObject();
        part.addProperty("type", "image_url");
        part.add("image_url", url);
        return part;
    }

    private static void addHistory(JsonObject message) {
        synchronized (history) {
            history.add(message);
            // Keep the system prompt; drop the oldest, starting the rest at a question.
            while (history.size() > MAX_HISTORY + 1) {
                history.remove(1);
                while (history.size() > 1 && !"user".equals(history.get(1).get("role").getAsString())) {
                    history.remove(1);
                }
            }
        }
    }

    /** After a turn, pictures are dropped from the conversation: they are large, and were for that question. */
    private static void stripImages() {
        synchronized (history) {
            for (JsonObject m : history) {
                if (m.has("content") && m.get("content").isJsonArray()) {
                    StringBuilder text = new StringBuilder();
                    for (JsonElement part : m.getAsJsonArray("content")) {
                        JsonObject p = part.getAsJsonObject();
                        if (p.has("text")) {
                            text.append(p.get("text").getAsString());
                        }
                    }
                    m.addProperty("content", text + " [a screenshot was shown here]");
                }
            }
        }
    }

    // ---- chat ------------------------------------------------------------------------------

    private static void say(String text, ChatFormatting color) {
        show(List.of(Component.literal("[AI] " + text).withStyle(color)));
    }

    private static void show(List<? extends Component> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            for (Component line : lines) {
                qbotics.client.ide.ChatLog.add(line); // the editor's Chat panel shows it too
                if (minecraft.player != null) {
                    minecraft.gui.getChat().addClientSystemMessage(line); // only in this player's chat
                }
            }
        });
    }

    private static String describe(Exception e) {
        Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static Path configFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("qbotics-ai.json");
    }

    /** For the screenshot run: talk to a stand-in model at this address. */
    public static void useForShots(String url) {
        AgentSettings settings = AgentSettings.get();
        settings.url = url;
        settings.model = "stand-in";
        settings.auto = false;
        settings.vision = true;
    }

    /** The player's settings, kept in their game folder (config/qbotics-ai.json), never sent to a server. */
    static final class AgentSettings {
        String url = "http://localhost:11434/v1";
        String model = "";
        String key = "";
        boolean auto;
        boolean vision = true;
        /** How much models that think before answering may: off, low (the default), medium or high. */
        String thinking = "low";
        /** "own": the player's own model even where the server offers one; otherwise the server's when offered. */
        String use = "server";

        private static AgentSettings loaded;

        static synchronized AgentSettings get() {
            if (loaded == null) {
                try {
                    loaded = GSON.fromJson(Files.readString(configFile()), AgentSettings.class);
                } catch (Exception e) {
                    loaded = null;
                }
                if (loaded == null) {
                    loaded = new AgentSettings();
                }
            }
            return loaded;
        }

        synchronized void save() {
            try {
                Files.createDirectories(configFile().getParent());
                Files.writeString(configFile(), new GsonBuilder().setPrettyPrinting().create().toJson(this));
            } catch (java.io.IOException e) {
                Qbotics.LOGGER.warn("Couldn't save the AI agent's settings", e);
            }
        }
    }

    /** The manual (REFERENCE.md), searched by section. */
    static final class Manual {
        private static List<String[]> sections;

        static synchronized String search(String query) {
            if (sections == null) {
                sections = new ArrayList<>();
                try (InputStream in = ClientAgent.class.getResourceAsStream("/qbotics/helpme/REFERENCE.md")) {
                    String all = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
                    String title = "";
                    StringBuilder body = new StringBuilder();
                    for (String line : all.split("\n")) {
                        if (line.startsWith("## ") || line.startsWith("### ")) {
                            if (!body.isEmpty()) {
                                sections.add(new String[] {title, body.toString()});
                            }
                            title = line.replaceFirst("^#+\\s*", "");
                            body = new StringBuilder();
                        }
                        body.append(line).append('\n');
                    }
                    if (!body.isEmpty()) {
                        sections.add(new String[] {title, body.toString()});
                    }
                } catch (java.io.IOException e) {
                    // No manual.
                }
            }
            String[] words = query.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9_.]+");
            List<Map.Entry<Integer, String[]>> scored = new ArrayList<>();
            for (String[] section : sections) {
                String title = section[0].toLowerCase(java.util.Locale.ROOT);
                String body = section[1].toLowerCase(java.util.Locale.ROOT);
                int score = 0;
                for (String word : words) {
                    if (word.length() < 2) {
                        continue;
                    }
                    score += title.contains(word) ? 10 : 0;
                    for (int at = body.indexOf(word); at >= 0 && score < 1000; at = body.indexOf(word, at + word.length())) {
                        score++;
                    }
                }
                if (score > 0) {
                    scored.add(Map.entry(score, section));
                }
            }
            if (scored.isEmpty()) {
                return "Nothing in the manual matches \"" + query + "\". Sections: "
                        + String.join(", ", sections.stream().map(s -> s[0]).limit(80).toList());
            }
            scored.sort((a, b) -> b.getKey() - a.getKey());
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(2, scored.size()); i++) {
                String body = scored.get(i).getValue()[1];
                out.append(body.length() > 3500 ? body.substring(0, 3500) + "\n...\n" : body).append('\n');
            }
            return out.toString();
        }
    }
}
