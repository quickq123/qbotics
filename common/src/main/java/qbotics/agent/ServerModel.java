package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import qbotics.Qbotics;
import qbotics.QboticsConfig;

/**
 * The AI model a server offers its players (design doc §17.8): set up once by its owner
 * (ai.url, ai.model, ai.key in qbotics-server.toml) and called by the server for them, so
 * players never see the address or the key. A player's game sends the conversation (its
 * messages and the tools it offers) and gets back the model's answer; everything else about
 * the call is decided here.
 *
 * <p>A small test when the server starts (and /qbotics ai test) decides whether it is offered:
 * one that doesn't answer isn't, and players set up their own.
 */
public final class ServerModel {
    private ServerModel() {
    }

    public enum State { NOT_SET_UP, TESTING, WORKING, FAILED }

    /** What the last test found. */
    public record Status(State state, String detail) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile Status status = new Status(State.NOT_SET_UP, "");
    private static volatile Semaphore parallel = new Semaphore(4);
    private static volatile int parallelSize = 4;
    private static final Map<UUID, Deque<Long>> RECENT = new ConcurrentHashMap<>();
    /** Set when the model's server refused the thinking settings: they aren't sent again. */
    private static volatile boolean noThinkingSettings;

    /** What players are told: what the agent may do here, and the model offered (its name only). */
    public static qbotics.net.AgentPayloads.Settings settings() {
        boolean offered = offered();
        return new qbotics.net.AgentPayloads.Settings(QboticsConfig.aiEnabled, QboticsConfig.aiShareReplies, offered,
                offered ? QboticsConfig.aiModel : "", offered && QboticsConfig.aiVision);
    }

    /** Tells every player online what is offered now. On the server thread. */
    public static void tellEveryone(net.minecraft.server.MinecraftServer server) {
        qbotics.net.AgentPayloads.Settings now = settings();
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            Qbotics.platform().sendToPlayer(player, now);
        }
    }

    /** For /qbotics ai: how it is, in words. With details (operators), what went wrong. */
    public static java.util.List<String> report(boolean details) {
        Status now = status();
        return switch (now.state()) {
            case NOT_SET_UP -> java.util.List.of("This server offers no AI model: players set up their own (/ai in chat).",
                    "An operator sets one up in qbotics-server.toml, under [ai]: url and model (and key if it needs one).");
            case TESTING -> java.util.List.of("This server's AI model (" + QboticsConfig.aiModel + ") is being tested...");
            case WORKING -> java.util.List.of("This server's AI model works: " + QboticsConfig.aiModel + ", " + now.detail()
                    + ". Players ask it with @ai in chat, or the Chat panel in a computer's editor.");
            case FAILED -> details
                    ? java.util.List.of("This server's AI model (" + QboticsConfig.aiModel + ") isn't working: " + now.detail(),
                            "Players use their own until it does. /qbotics ai test tries again.")
                    : java.util.List.of("This server's AI model isn't working now: players can set up their own (/ai in chat).");
        };
    }

    public static boolean configured() {
        return !QboticsConfig.aiUrl.isEmpty() && !QboticsConfig.aiModel.isEmpty();
    }

    /** Whether players are offered it now: set up, and its last test passed. */
    public static boolean offered() {
        return configured() && status.state() == State.WORKING;
    }

    public static Status status() {
        return configured() ? status : new Status(State.NOT_SET_UP, "");
    }

    /**
     * Tests it: one short question, thinking off. Completes with the outcome, which is also
     * kept for offered(). Then everyone is told (onChange).
     */
    public static CompletableFuture<Status> test(Runnable onChange) {
        noThinkingSettings = false;
        if (!configured()) {
            status = new Status(State.NOT_SET_UP, "");
            onChange.run();
            return CompletableFuture.completedFuture(status);
        }
        status = new Status(State.TESTING, "");
        JsonObject body = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("role", "user");
        question.addProperty("content", "Reply with the single word OK.");
        messages.add(question);
        body.add("messages", messages);
        long started = System.nanoTime();
        return post(body, "off", Duration.ofSeconds(120)).handle((response, error) -> {
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (error != null) {
                status = new Status(State.FAILED, describe(error));
            } else if (response.statusCode() != 200) {
                status = new Status(State.FAILED, "it answered " + response.statusCode() + ": " + shorten(response.body()));
            } else {
                try {
                    JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                    status = new Status(State.WORKING, "answered in " + ms + " ms");
                } catch (RuntimeException e) {
                    status = new Status(State.FAILED, "its answer wasn't an OpenAI-style reply: " + shorten(response.body()));
                }
            }
            Qbotics.LOGGER.info("The server's AI model ({}): {} {}", QboticsConfig.aiModel, status.state(), status.detail());
            onChange.run();
            return status;
        });
    }

    /**
     * One call for a player: their conversation (a JSON object with "messages" and "tools").
     * Answers with the model's reply (the HTTP status, and its body), or a refusal explained.
     */
    public static CompletableFuture<Reply> relay(UUID player, String requestJson) {
        if (!offered()) {
            return CompletableFuture.completedFuture(new Reply(503, error("This server's AI isn't available now.")));
        }
        // Fair use: so many calls a minute for each player.
        long now = System.currentTimeMillis();
        Deque<Long> recent = RECENT.computeIfAbsent(player, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) {
                recent.removeFirst();
            }
            if (recent.size() >= QboticsConfig.aiRequestsPerMinute) {
                return CompletableFuture.completedFuture(new Reply(429, error("You have asked this server's AI a lot this minute ("
                        + QboticsConfig.aiRequestsPerMinute + " calls). Wait a moment and ask again.")));
            }
            recent.addLast(now);
        }
        JsonObject asked;
        try {
            asked = JsonParser.parseString(requestJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new Reply(400, error("The request wasn't JSON.")));
        }
        // Only the conversation and the tools come from the player; the rest is the server's.
        JsonObject body = new JsonObject();
        if (asked.get("messages") instanceof JsonArray messages) {
            body.add("messages", messages);
        }
        if (asked.get("tools") instanceof JsonArray tools) {
            body.add("tools", tools);
        }
        Semaphore gate = gate();
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!gate.tryAcquire(3, TimeUnit.MINUTES)) {
                    return new Reply(503, error("This server's AI is busy with other players' questions. Try again soon."));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Reply(503, error("Interrupted."));
            }
            try {
                HttpResponse<String> response = post(body, QboticsConfig.aiThinking, Duration.ofMinutes(5)).get(6, TimeUnit.MINUTES);
                if (response.statusCode() == 400 && !noThinkingSettings
                        && (response.body().contains("reasoning_effort") || response.body().contains("chat_template_kwargs"))) {
                    noThinkingSettings = true; // a server without them: ask again plainly
                    response = post(body, QboticsConfig.aiThinking, Duration.ofMinutes(5)).get(6, TimeUnit.MINUTES);
                }
                return forPlayer(response.statusCode(), response.body());
            } catch (Exception e) {
                Qbotics.LOGGER.warn("The server's AI model ({}) didn't answer a player's question: {}", QboticsConfig.aiModel, describe(e));
                return new Reply(502, error(PROBLEM));
            } finally {
                gate.release();
            }
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    private static final String PROBLEM = "This server's AI had a problem answering. Try again in a moment; "
            + "if it keeps happening, tell the server's owner (the details are in the server's log).";

    /**
     * What a player is sent of the model's answer: only the message (its text and tool calls),
     * never the rest of what the model's server said (its build, its timings) and never its
     * errors, which can name files and settings on the machine running it. Those go to the
     * server's log, for its owner.
     */
    static Reply forPlayer(int status, String body) {
        if (status == 200) {
            try {
                JsonObject message = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0)
                        .getAsJsonObject().getAsJsonObject("message");
                JsonObject kept = new JsonObject();
                kept.addProperty("role", "assistant");
                kept.add("content", message.has("content") ? message.get("content") : com.google.gson.JsonNull.INSTANCE);
                if (message.get("tool_calls") instanceof JsonArray calls) {
                    kept.add("tool_calls", calls);
                }
                JsonObject choice = new JsonObject();
                choice.add("message", kept);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject reply = new JsonObject();
                reply.add("choices", choices);
                return new Reply(200, reply.toString());
            } catch (RuntimeException e) {
                Qbotics.LOGGER.warn("The server's AI model ({}) gave an answer that isn't an OpenAI-style reply: {}", QboticsConfig.aiModel, shorten(body));
                return new Reply(502, error(PROBLEM));
            }
        }
        Qbotics.LOGGER.warn("The server's AI model ({}) answered a player's question with {}: {}", QboticsConfig.aiModel, status, shorten(body));
        return new Reply(502, error(PROBLEM));
    }

    /** The model's answer: an HTTP status, and the body (an OpenAI-style reply, or an error). */
    public record Reply(int status, String body) {
    }

    private static synchronized Semaphore gate() {
        if (parallelSize != QboticsConfig.aiMaxParallel) {
            parallelSize = QboticsConfig.aiMaxParallel;
            parallel = new Semaphore(parallelSize);
        }
        return parallel;
    }

    private static CompletableFuture<HttpResponse<String>> post(JsonObject conversation, String thinking, Duration timeout) {
        JsonObject body = conversation.deepCopy();
        body.addProperty("model", QboticsConfig.aiModel);
        body.addProperty("stream", false);
        if (!noThinkingSettings) {
            switch (thinking) {
                case "off" -> {
                    JsonObject switches = new JsonObject();
                    switches.addProperty("enable_thinking", false);
                    body.add("chat_template_kwargs", switches);
                }
                case "low", "medium" -> body.addProperty("reasoning_effort", thinking);
                default -> {
                }
            }
        }
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(QboticsConfig.aiUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ai.url isn't an address: " + QboticsConfig.aiUrl));
        }
        if (!QboticsConfig.aiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + QboticsConfig.aiKey);
        }
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        JsonObject body = new JsonObject();
        body.add("error", error);
        return body.toString();
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException
                ? error.getCause() : error;
        if (cause instanceof java.net.ConnectException) {
            return "couldn't connect (is the model's server running, and can this server reach it?)";
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "it didn't answer in time";
        }
        return cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
    }

    private static String shorten(String text) {
        String one = text.replaceAll("\\s+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

}
