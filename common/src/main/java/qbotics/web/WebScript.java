package qbotics.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A web page's scripts (design doc §10.3): its DOM and JavaScript, in a {@link JsRuntime} with
 * prelude.js, the browser the scripts see. The page's HTML comes from here once it has scripts:
 * whenever they change the DOM, {@link #html()} has the new page to lay out. Canvases are drawn
 * here too. Not thread-safe: use it from one thread (the web panels' worker).
 */
public final class WebScript {
    /** What scripts may use, and how long one run may take. */
    public static final int MEMORY = 16 << 20;
    private static final double LOAD_BUDGET_MS = 400;
    private static final double RUN_BUDGET_MS = 60;
    private static final Gson GSON = new Gson();
    private static String prelude;

    /** What a page's scripts ask of the game. */
    public interface Hooks {
        /** panel.send(name, data): to the program. dataJson is JSON. */
        void send(String name, String dataJson);

        void log(String line);

        void error(String message);

        /** What the player has typed in a field (null: nothing yet). */
        @Nullable String value(String field);

        /** A script set a field's value. */
        void setValue(String field, String value);
    }

    private final JsRuntime js;
    private final Hooks hooks;
    private final Map<Integer, CanvasPainter> canvases = new HashMap<>();
    /** Pictures a script draws (item:..., image:...); none until the game gives a way to find them. */
    private java.util.function.Function<String, @Nullable BufferedImage> pictures = url -> null;

    /** How the game finds the pictures a script draws with drawImage (on the worker). */
    public void pictures(java.util.function.Function<String, @Nullable BufferedImage> find) {
        pictures = find;
    }
    private final long start = System.nanoTime();
    private String html = "";
    private boolean htmlChanged;
    private boolean canvasesChanged;
    private long next = -1;

    private static synchronized String prelude() {
        if (prelude == null) {
            try (InputStream in = WebScript.class.getResourceAsStream("prelude.js")) {
                if (in == null) {
                    throw new IllegalStateException("prelude.js is missing from the mod jar");
                }
                prelude = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return prelude;
    }

    public WebScript(Hooks hooks) {
        this.hooks = hooks;
        this.js = new JsRuntime(MEMORY, this::host);
        JsRuntime.Result loaded = js.eval(prelude(), "prelude.js", 0);
        if (!loaded.ok()) {
            throw new IllegalStateException("The web panel's script runtime failed to start: " + loaded.text());
        }
    }

    /** True if a page has scripts to run. */
    public static boolean hasScripts(String html) {
        return html.contains("<script") || html.contains("<SCRIPT");
    }

    private double now() {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String quote(String text) {
        return GSON.toJson(text);
    }

    private @Nullable String host(String op, String arg) {
        try {
            switch (op) {
                case "log" -> hooks.log(arg);
                case "error" -> hooks.error(arg);
                case "send" -> {
                    JsonArray message = JsonParser.parseString(arg).getAsJsonArray();
                    hooks.send(message.get(0).getAsString(), message.get(1).toString());
                }
                case "measure" -> {
                    JsonArray request = JsonParser.parseString(arg).getAsJsonArray();
                    java.awt.Font font = CanvasPainter.font(request.get(0).getAsString(), new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10));
                    return Double.toString(font.getStringBounds(request.get(1).getAsString(), WebPage.FRC).getWidth());
                }
                case "value" -> {
                    return hooks.value(arg);
                }
                case "image_size" -> {
                    BufferedImage picture = pictures.apply(arg);
                    return picture == null ? null : picture.getWidth() + "," + picture.getHeight();
                }
                case "set_value" -> {
                    JsonArray request = JsonParser.parseString(arg).getAsJsonArray();
                    hooks.setValue(request.get(0).getAsString(), request.get(1).getAsString());
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            hooks.error("The game couldn't answer " + op + ": " + e.getMessage());
        }
        return null;
    }

    private @Nullable String run(String code, String what, double budget) {
        JsRuntime.Result result = js.eval(code, what, budget);
        if (!result.ok()) {
            hooks.error(result.text().contains("interrupted")
                    ? "The page's script ran too long and was stopped (" + what + ")." : result.text());
            return null;
        }
        return result.text();
    }

    /** A new page (its state already filled in): its scripts run. */
    public void load(String page, int width, int height, String stateJson) {
        run("__qb.load(" + quote(page) + "," + width + "," + height + "," + quote(stateJson) + "," + now() + ")", "the page's scripts", LOAD_BUDGET_MS);
        afterRun();
    }

    /** The program changed the state: panel.onState listeners run. */
    public void setState(String stateJson) {
        run("__qb.setState(" + quote(stateJson) + ")", "panel.onState", RUN_BUDGET_MS);
        afterRun();
    }

    /** A click on an element the script knows. True if the page's default should go on (preventDefault wasn't called). */
    public boolean click(int number, float x, float y) {
        String ok = run("__qb.click(" + number + "," + x + "," + y + ")", "a click", RUN_BUDGET_MS);
        afterRun();
        return !"false".equals(ok);
    }

    /** Typing in a field: "input" while typing, "change" on Enter. */
    public void input(int number, String type) {
        run("__qb.input(" + number + "," + quote(type) + ")", "typing", RUN_BUDGET_MS);
        afterRun();
    }

    /** Time passes: timers and animation frames run. */
    public void tick() {
        afterRun(); // tick is part of afterRun
    }

    /** After scripts ran: due timers, then what changed. */
    private void afterRun() {
        String wait = run("__qb.tick(" + now() + ")", "timers", RUN_BUDGET_MS);
        try {
            next = wait == null ? -1 : (long) Double.parseDouble(wait);
        } catch (NumberFormatException e) {
            next = -1;
        }
        String flushed = run("__qb.flush()", "the page", RUN_BUDGET_MS);
        if (flushed == null || flushed.isEmpty()) {
            return;
        }
        JsonObject out = JsonParser.parseString(flushed).getAsJsonObject();
        if (out.has("html") && !out.get("html").isJsonNull()) {
            html = out.get("html").getAsString();
            htmlChanged = true;
        }
        for (JsonElement entry : out.getAsJsonArray("canvases")) {
            JsonArray canvas = entry.getAsJsonArray();
            int number = canvas.get(0).getAsInt();
            int width = canvas.get(1).getAsInt();
            int height = canvas.get(2).getAsInt();
            CanvasPainter painter = canvases.get(number);
            if (painter == null) {
                painter = new CanvasPainter(width, height);
                canvases.put(number, painter);
            } else if (canvas.get(3).getAsBoolean()) {
                painter.reset(width, height);
            }
            painter.run(canvas.get(4).getAsJsonArray(), n -> {
                CanvasPainter other = canvases.get(n);
                return other == null ? null : other.image();
            }, pictures);
            canvasesChanged = true;
        }
    }

    /** The page as the scripts have it now, ready to lay out. */
    public String html() {
        return html;
    }

    /** True once if the page changed since last asked (it must be laid out again). */
    public boolean takeHtmlChanged() {
        boolean was = htmlChanged;
        htmlChanged = false;
        return was;
    }

    /** True once if a canvas was drawn on since last asked (the page must be painted again). */
    public boolean takeCanvasesChanged() {
        boolean was = canvasesChanged;
        canvasesChanged = false;
        return was;
    }

    /** Milliseconds until a timer or animation frame is due, or -1 if none is waiting. */
    public long next() {
        return next;
    }

    /** A canvas's picture, for {@code canvas:N} in the page. */
    public @Nullable BufferedImage canvas(int number) {
        CanvasPainter painter = canvases.get(number);
        return painter == null ? null : painter.image();
    }

    public int memoryUsed() {
        return js.memoryUsed();
    }
}
