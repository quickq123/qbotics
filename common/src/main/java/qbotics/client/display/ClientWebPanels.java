package qbotics.client.display;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.DisplayBlock;
import qbotics.block.DisplayBlockEntity;
import qbotics.net.DisplayPayloads;
import qbotics.web.WebInputs;
import qbotics.web.WebPage;
import qbotics.web.WebPainter;
import qbotics.web.WebScript;
import qbotics.web.WebTemplate;

/**
 * Web Panels in the player's game (design doc §10.3). Each panel's page is laid out by its
 * own {@link WebPage} and painted into a texture on one background thread, so the game never
 * waits for it; the renderer draws the texture on the panel's face, and item icons over it.
 * Clicks, typing and scrolling are handled here, where the page is laid out: what each player
 * has typed and how far they have scrolled is their own; the server is told what was clicked.
 */
public final class ClientWebPanels {
    private ClientWebPanels() {
    }

    /** The game's own CSS for panels, under the page's: dark, like the other screens. */
    static final String PANEL_CSS = "html { background: #0e1216; color: #e6e8ea; } "
            + "body { margin: 8px; font-family: sans-serif; font-size: 14px; } a { color: #8cc8ff; } "
            + "button { background: #2c3440; color: #e6e8ea; border: 1px solid #56606c; border-radius: 3px; padding: 3px 8px; } "
            + "img { vertical-align: middle; } " + WebInputs.CSS;
    /** Views not drawn for this long let go of their texture and page (they come back when seen again). */
    private static final long FORGET_MS = 30_000;
    /** Typing is sent to the program this long after the last key (Enter sends at once). */
    private static final long TYPING_PAUSE_MS = 300;

    /** One panel as this game has it. Fields set on the render thread unless noted. */
    public static final class WebView {
        final BlockPos origin;
        int width;
        int height;
        String html = "";
        String state = "{}";
        /** Something changed since the last picture was asked for that needs a new layout. */
        boolean dirty = true;
        /** Only the drawing changed (hover, scrolling): paint again without laying out. */
        boolean repaint;
        /** A picture is being made on the worker. */
        boolean busy;
        /** How far down the page this player has scrolled, in CSS pixels. */
        float scrollY;
        /** How tall the page is (from the worker's last layout). */
        volatile float contentHeight;
        /** What this player has typed, by field name, and the field with the keyboard (and its script node). */
        final Map<String, String> typed = new java.util.concurrent.ConcurrentHashMap<>();
        @Nullable String focused;
        @Nullable Integer focusedNode;
        /** The field with the keyboard is a textarea: Enter adds a line. */
        boolean focusedArea;
        /** The options of each select on the page (from its last layout). */
        volatile Map<String, List<WebInputs.Option>> selects = Map.of();
        /** Goes up each time the program gives a new page (its scripts start again). */
        int generation;
        /** The page's scripts, if it has any. Worker only. */
        @Nullable WebScript script;
        int scriptGeneration = -1;
        @Nullable String scriptState;
        /** Things for the scripts to do on the worker before the next picture (clicks, typing, timers). */
        final java.util.concurrent.ConcurrentLinkedQueue<java.util.function.Consumer<WebScript>> scriptActions =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        /** A timer is set to wake the scripts; or one came due while nobody was looking. */
        boolean tickQueued;
        boolean tickWaiting;
        /** The scripts' last error, shown in the close-up. */
        volatile @Nullable String lastError;
        long lastErrorSent;
        /** Typing not yet sent to the program, and when the last key was. */
        boolean typingPending;
        long lastKey;
        /** The pictures the page shows as image:name, coloured. */
        volatile Map<String, BufferedImage> pictures = Map.of();
        /** Worker only. */
        @Nullable WebPage page;
        /** A finished picture and its item icons, handed from the worker to the render thread. */
        volatile @Nullable NativeImage ready;
        volatile @Nullable List<WebPainter.Overlay> readyOverlays;
        /** The item icons to draw over the picture shown now. */
        List<WebPainter.Overlay> overlays = List.of();
        @Nullable DynamicTexture texture;
        @Nullable Identifier textureId;
        long lastSeen = System.currentTimeMillis();

        WebView(BlockPos origin) {
            this.origin = origin;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public List<WebPainter.Overlay> overlays() {
            return overlays;
        }

        public @Nullable String focused() {
            return focused;
        }

        public @Nullable String lastError() {
            return lastError;
        }
    }

    private static final Map<BlockPos, WebView> VIEWS = new HashMap<>();
    private static final Map<BlockPos, Integer> DEBUG_FRAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static long debugLast;
    private static @Nullable Level viewsLevel;
    private static long lastSweep;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qbotics-web");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** Called once by the client: clicks on Web Panels come here. */
    public static void install() {
        DisplayBlock.webTouch = ClientWebPanels::touched;
    }

    private static void sameLevel() {
        Level now = Minecraft.getInstance().level;
        if (now != viewsLevel) {
            VIEWS.values().forEach(ClientWebPanels::release);
            VIEWS.clear();
            viewsLevel = now;
        }
    }

    private static void release(WebView view) {
        if (view.textureId != null) {
            Minecraft.getInstance().getTextureManager().release(view.textureId);
            view.textureId = null;
            view.texture = null;
        }
        NativeImage pending = view.ready;
        view.ready = null;
        if (pending != null) {
            pending.close();
        }
        view.page = null;
        view.script = null;
        view.scriptGeneration = -1;
    }

    public static @Nullable WebView view(BlockPos origin) {
        sameLevel();
        return VIEWS.get(origin);
    }

    public static void onWeb(DisplayPayloads.Web payload) {
        sameLevel();
        BlockPos origin = payload.origin().immutable();
        WebView view = VIEWS.computeIfAbsent(origin, WebView::new);
        view.width = payload.width();
        view.height = payload.height();
        if (payload.hasPage()) {
            view.html = payload.html();
            view.generation++;
        }
        view.state = payload.state();
        if (payload.images() != null) {
            Map<String, BufferedImage> pictures = new HashMap<>();
            payload.images().forEach((name, picture) -> pictures.put(name, PictureColors.colorize(picture)));
            view.pictures = pictures;
        }
        view.dirty = true;
        schedule(view);
    }

    /** A picture's natural size on the page: two CSS pixels to each of its pixels. */
    private static float @Nullable [] imageSize(WebView view, String url) {
        if (url.startsWith("canvas:")) {
            BufferedImage canvas = image(view, url);
            return canvas == null ? null : new float[] {canvas.getWidth(), canvas.getHeight()};
        }
        BufferedImage picture = url.startsWith("image:") ? view.pictures.get(url.substring(6)) : null;
        return picture == null ? null : new float[] {picture.getWidth() * 2, picture.getHeight() * 2};
    }

    /** A picture the page shows: a Qbot's (image:name) or a canvas its scripts draw on (canvas:N). Worker. */
    private static @Nullable BufferedImage image(WebView view, String url) {
        if (url.startsWith("canvas:")) {
            WebScript script = view.script;
            try {
                return script == null ? null : script.canvas(Integer.parseInt(url.substring(7)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return url.startsWith("image:") ? view.pictures.get(url.substring(6)) : null;
    }

    /** What a page's scripts ask of the game. Called on the worker. */
    private static WebScript.Hooks hooks(WebView view) {
        return new WebScript.Hooks() {
            @Override
            public void send(String name, String dataJson) {
                JsonObject element = new JsonObject();
                element.addProperty("tag", "script");
                element.addProperty("id", "");
                element.addProperty("name", "");
                element.addProperty("event", name);
                element.addProperty("value", "");
                element.addProperty("data", dataJson.length() > 3000 ? "null" : dataJson);
                Minecraft.getInstance().execute(() -> Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(view.origin, element.toString())));
            }

            @Override
            public void log(String line) {
                Qbotics.LOGGER.info("Web panel at {}: {}", view.origin.toShortString(), line.length() > 500 ? line.substring(0, 500) : line);
            }

            @Override
            public void error(String message) {
                view.lastError = message.length() > 300 ? message.substring(0, 300) : message;
                Qbotics.LOGGER.warn("Web panel at {}: {}", view.origin.toShortString(), view.lastError);
                long now = System.currentTimeMillis();
                if (now - view.lastErrorSent > 2000) {
                    // The program hears it as an "error" event, so its author sees what went wrong.
                    view.lastErrorSent = now;
                    JsonObject element = new JsonObject();
                    element.addProperty("tag", "script");
                    element.addProperty("id", "");
                    element.addProperty("name", "");
                    element.addProperty("event", "error");
                    element.addProperty("value", view.lastError.length() > 200 ? view.lastError.substring(0, 200) : view.lastError);
                    Minecraft.getInstance().execute(() -> Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(view.origin, element.toString())));
                }
            }

            @Override
            public @Nullable String value(String field) {
                return view.typed.get(field);
            }

            @Override
            public void setValue(String field, String value) {
                view.typed.put(field, value.length() > WebInputs.MAX_LENGTH ? value.substring(0, WebInputs.MAX_LENGTH) : value);
                Minecraft.getInstance().execute(() -> {
                    view.dirty = true;
                    schedule(view);
                });
            }
        };
    }

    /** Texture pixels per CSS pixel: twice for small panels, so their text is sharp. */
    static int scale(WebView view) {
        return Math.max(view.width, view.height) <= 512 ? 2 : 1;
    }

    /** Asks the worker for a new picture if one is wanted and none is being made. Render thread. */
    private static void schedule(WebView view) {
        if (view.busy || !(view.dirty || view.repaint || !view.scriptActions.isEmpty())) {
            return;
        }
        boolean relayout = view.dirty || view.page == null;
        view.busy = true;
        view.dirty = false;
        view.repaint = false;
        String html = view.html;
        String state = view.state;
        int generation = view.generation;
        Map<String, String> typed = Map.copyOf(view.typed);
        String focused = view.focused;
        int width = view.width;
        int height = view.height;
        int scale = scale(view);
        float scroll = view.scrollY;
        WORKER.execute(() -> {
            NativeImage image = null;
            List<WebPainter.Overlay> overlays = new ArrayList<>();
            float shownScroll = scroll;
            long next = -1;
            try {
                if (view.page == null) {
                    view.page = new WebPage(url -> imageSize(view, url));
                }
                boolean layout = relayout;
                String source;
                if (WebScript.hasScripts(html)) {
                    // The page's scripts own the page: they start with it, hear the state and the player, and change it.
                    if (view.script == null || view.scriptGeneration != generation) {
                        view.script = new WebScript(hooks(view));
                        view.script.pictures(url -> url.startsWith("item:") ? WebItemImages.icon(url.substring(5)) : image(view, url));
                        view.scriptGeneration = generation;
                        view.scriptState = state;
                        view.script.load(WebTemplate.fill(html, state), width, height, state);
                    } else if (!state.equals(view.scriptState)) {
                        view.scriptState = state;
                        view.script.setState(state);
                    }
                    for (java.util.function.Consumer<WebScript> action; (action = view.scriptActions.poll()) != null; ) {
                        action.accept(view.script);
                    }
                    layout |= view.script.takeHtmlChanged();
                    view.script.takeCanvasesChanged();
                    source = view.script.html();
                    next = view.script.next();
                } else {
                    view.script = null;
                    view.scriptActions.clear();
                    source = WebTemplate.fill(html, state);
                }
                if (Boolean.getBoolean("qbotics.webDebug")) {
                    DEBUG_FRAMES.merge(view.origin, 1, Integer::sum);
                    if (System.currentTimeMillis() - debugLast > 2000) {
                        debugLast = System.currentTimeMillis();
                        Qbotics.LOGGER.info("Web panel frames in 2 s: {}", DEBUG_FRAMES);
                        DEBUG_FRAMES.clear();
                    }
                }
                if (layout) {
                    WebInputs.Result controls = WebInputs.rewriteAll(source, typed, focused);
                    view.selects = controls.selects();
                    view.page.load(controls.html(), PANEL_CSS);
                    view.contentHeight = view.page.layout(width, height);
                }
                shownScroll = Math.clamp(scroll, 0, Math.max(0, view.contentHeight - height));
                image = toNative(WebPainter.paint(view.page, shownScroll, scale, url -> image(view, url), overlays));
            } catch (RuntimeException e) {
                Qbotics.LOGGER.warn("A web panel's page couldn't be drawn", e);
            }
            NativeImage made = image;
            float clamped = shownScroll;
            long wake = next;
            Minecraft.getInstance().execute(() -> {
                view.busy = false;
                if (wake >= 0) {
                    wakeLater(view, wake);
                }
                if (view.scrollY == scroll) {
                    view.scrollY = clamped; // a page that got shorter scrolls back
                }
                NativeImage old = view.ready;
                view.ready = made;
                view.readyOverlays = overlays;
                if (old != null) {
                    old.close();
                }
                schedule(view); // anything that changed meanwhile
            });
        });
    }

    /** How many panels' scripts run at once in this game: the nearest. Others wait (a wall of scripted panels can't lag the game). */
    private static final int MAX_RUNNING_SCRIPTS = 8;

    /** True if this panel is among the nearest few with scripts, so its timers and animations may run. Render thread. */
    private static boolean mayRunScripts(WebView view) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        double mine = player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(view.origin));
        int nearer = 0;
        long now = System.currentTimeMillis();
        for (WebView other : VIEWS.values()) {
            if (other != view && WebScript.hasScripts(other.html) && now - other.lastSeen < 3000
                    && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(other.origin)) < mine) {
                nearer++;
            }
        }
        return nearer < MAX_RUNNING_SCRIPTS;
    }

    /** Animation and timers: the scripts are woken again, at most 20 times a second, while someone is looking. */
    private static final java.util.concurrent.ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qbotics-web-timers");
        thread.setDaemon(true);
        return thread;
    });

    private static void wakeLater(WebView view, long ms) {
        if (view.tickQueued) {
            return;
        }
        view.tickQueued = true;
        TICKER.schedule(() -> Minecraft.getInstance().execute(() -> {
            view.tickQueued = false;
            if (System.currentTimeMillis() - view.lastSeen > 3000 || !mayRunScripts(view)) {
                view.tickWaiting = true; // nobody is looking, or nearer panels are running: wait
                return;
            }
            view.scriptActions.add(WebScript::tick);
            schedule(view);
        }), Math.max(50, ms), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static NativeImage toNative(BufferedImage image) {
        NativeImage out = new NativeImage(image.getWidth(), image.getHeight(), false);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            for (int x = 0; x < row.length; x++) {
                out.setPixel(x, y, row[x] | 0xFF000000); // panels are opaque
            }
        }
        return out;
    }

    /** The texture to draw, after taking in a finished picture. Render thread. */
    public static @Nullable Identifier texture(WebView view) {
        view.lastSeen = System.currentTimeMillis();
        NativeImage ready = view.ready;
        if (ready != null) {
            view.ready = null;
            List<WebPainter.Overlay> overlays = view.readyOverlays;
            view.overlays = overlays == null ? List.of() : overlays;
            if (view.texture != null && view.texture.getPixels() != null && view.texture.getPixels().getWidth() == ready.getWidth()
                    && view.texture.getPixels().getHeight() == ready.getHeight()) {
                view.texture.setPixels(ready);
                view.texture.upload();
            } else {
                if (view.textureId != null) {
                    Minecraft.getInstance().getTextureManager().release(view.textureId);
                }
                view.textureId = Qbotics.id("web/" + Long.toHexString(view.origin.asLong() & 0xFFFFFFFFFFFFL));
                view.texture = new DynamicTexture(() -> "Qbotics web panel", ready);
                Minecraft.getInstance().getTextureManager().register(view.textureId, view.texture);
            }
        }
        sweep();
        sendTyping(view, false);
        if (view.tickWaiting && mayRunScripts(view)) {
            view.tickWaiting = false;
            view.scriptActions.add(WebScript::tick);
        }
        schedule(view);
        return view.textureId;
    }

    /** Lets go of panels nobody has looked at for a while. */
    private static void sweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 1000) {
            return;
        }
        lastSweep = now;
        for (Iterator<WebView> it = VIEWS.values().iterator(); it.hasNext(); ) {
            WebView view = it.next();
            if (now - view.lastSeen > FORGET_MS && !view.busy) {
                release(view);
                it.remove();
            }
        }
    }

    // ---- touching, typing, scrolling ------------------------------------------------------------

    /** A right-click on a panel in the world; sneaking with an empty hand opens it close up. */
    private static boolean touched(DisplayBlockEntity block, double fx, double fy, boolean closeUp) {
        BlockPos origin = block.originPos();
        WebView view = view(origin);
        if (view == null) {
            return false;
        }
        if (closeUp) {
            Minecraft.getInstance().setScreen(new WebCloseUpScreen(origin));
            return true;
        }
        click(view, (float) fx * view.width, (float) fy * view.height, false);
        return true;
    }

    /**
     * A click at a point of the panel (CSS pixels, as it is shown). A text field takes the
     * keyboard if there is somewhere to type (the close-up); anything else goes to the server.
     */
    static void click(WebView view, float x, float y, boolean canType) {
        BlockPos origin = view.origin;
        float pageY = y + view.scrollY;
        WORKER.execute(() -> {
            if (view.page == null) {
                return;
            }
            String hit = view.page.hit(x, pageY);
            JsonObject parsed;
            try {
                parsed = hit == null ? null : JsonParser.parseString(hit).getAsJsonObject();
            } catch (RuntimeException e) {
                return;
            }
            // The page's scripts hear the click first; preventDefault() keeps it from the program.
            boolean goesOn = true;
            Integer node = null;
            if (parsed != null && view.script != null && !parsed.get("qbn").getAsString().isEmpty()) {
                node = Integer.parseInt(parsed.get("qbn").getAsString());
                goesOn = view.script.click(node, x, pageY);
            }
            JsonObject element = parsed;
            boolean toProgram = goesOn;
            Integer clickedNode = node;
            Minecraft.getInstance().execute(() -> {
                if (view.script != null) {
                    view.repaint = true; // the scripts may have changed the page
                }
                String input = element == null ? "" : element.get("input").getAsString();
                if (!input.isEmpty()) {
                    control(view, input, element, clickedNode, canType, x, y);
                    schedule(view);
                    return;
                }
                focus(view, null);
                if (element != null && toProgram && !(element.get("event").getAsString().isEmpty() && element.get("id").getAsString().isEmpty()
                        && !element.get("tag").getAsString().equals("button") && !element.get("tag").getAsString().equals("a"))) {
                    element.remove("qbn");
                    Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(origin, element.toString()));
                }
                schedule(view);
            });
        });
    }

    /**
     * A click on a form control ("kind:name"): a text field takes the keyboard (in the close-up)
     * or opens a box to type in (in the world); a checkbox or radio button flips; a select opens
     * its list. Render thread.
     */
    private static void control(WebView view, String input, JsonObject element, @Nullable Integer node, boolean canType, float x, float y) {
        int colon = input.indexOf(':');
        String kind = colon < 0 ? "text" : input.substring(0, colon);
        String name = colon < 0 ? input : input.substring(colon + 1);
        switch (kind) {
            case "text", "area" -> {
                if (canType) {
                    focus(view, name);
                    view.focusedNode = node;
                    view.focusedArea = kind.equals("area");
                } else {
                    // On a panel in the world: a box to type in, over the game.
                    focus(view, null);
                    Minecraft.getInstance().setScreen(WebFieldScreen.text(view, name, node, kind.equals("area")));
                }
            }
            case "check", "check-on" -> choose(view, name, kind.equals("check") ? element.get("value").getAsString() : "", node);
            case "radio" -> choose(view, name, element.get("value").getAsString(), node);
            case "select" -> {
                List<WebInputs.Option> options = view.selects.getOrDefault(name, List.of());
                if (options.isEmpty()) {
                    return;
                }
                if (canType && Minecraft.getInstance().screen instanceof WebCloseUpScreen closeUp) {
                    closeUp.dropdown(name, node, options, x, y + view.scrollY);
                } else {
                    Minecraft.getInstance().setScreen(WebFieldScreen.choose(view, name, node, options, Minecraft.getInstance().screen));
                }
            }
            default -> {
            }
        }
    }

    /** A checkbox, radio button or select was set: shown at once, and the program (and the page's script) told. */
    static void choose(WebView view, String name, String value, @Nullable Integer node) {
        view.typed.put(name, value);
        view.dirty = true;
        if (node != null) {
            view.scriptActions.add(script -> script.input(node, "change"));
        }
        JsonObject element = new JsonObject();
        element.addProperty("tag", "input");
        element.addProperty("id", "");
        element.addProperty("name", name);
        element.addProperty("event", name);
        element.addProperty("value", value);
        element.addProperty("enter", true);
        Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(view.origin, element.toString()));
        schedule(view);
    }

    /** For the screenshot run: the box for typing in a panel's search field from the world. */
    public static net.minecraft.client.gui.screens.Screen fieldScreenForShots(WebView view) {
        return WebFieldScreen.text(view, "search", null, false);
    }

    /** Text typed in the world's box (WebFieldScreen): shown, and sent as if Enter was pressed. */
    static void entered(WebView view, String name, String text, @Nullable Integer node) {
        view.typed.put(name, text);
        view.dirty = true;
        if (node != null) {
            view.scriptActions.add(script -> script.input(node, "change"));
        }
        send(view, name, true);
        schedule(view);
    }

    /** Gives a field the keyboard (or takes it away, with null). Render thread. */
    static void focus(WebView view, @Nullable String field) {
        if (field == null ? view.focused == null : field.equals(view.focused)) {
            return;
        }
        sendTyping(view, true);
        view.focused = field;
        if (field == null) {
            view.focusedNode = null;
        }
        view.dirty = true;
        schedule(view);
    }

    /** A key typed into the field with the keyboard: a character, or backspace (\b). Render thread. */
    static void type(WebView view, char c) {
        String field = view.focused;
        if (field == null) {
            return;
        }
        String now = view.typed.getOrDefault(field, "");
        if (c == '\b') {
            if (now.isEmpty()) {
                return;
            }
            now = now.substring(0, now.offsetByCodePoints(now.length(), -1));
        } else if (c == '\n' && view.focusedArea && now.length() < WebInputs.MAX_AREA_LENGTH) {
            now = now + c;
        } else if (now.length() < (view.focusedArea ? WebInputs.MAX_AREA_LENGTH : WebInputs.MAX_LENGTH) && c >= ' ') {
            now = now + c;
        } else {
            return;
        }
        view.typed.put(field, now);
        Integer node = view.focusedNode;
        if (node != null) {
            view.scriptActions.add(script -> script.input(node, "input"));
        }
        view.typingPending = true;
        view.lastKey = System.currentTimeMillis();
        view.dirty = true;
        schedule(view);
    }

    /** Enter in a field: what it holds goes to the program now, marked as entered. */
    static void enter(WebView view) {
        if (view.focusedArea) {
            type(view, '\n'); // in a textarea, Enter starts a new line
            return;
        }
        Integer node = view.focusedNode;
        if (node != null) {
            view.scriptActions.add(script -> script.input(node, "change"));
            schedule(view);
        }
        if (view.focused != null) {
            send(view, view.focused, true);
        }
    }

    /** Typing goes to the program once the player pauses (or at once, with now). */
    private static void sendTyping(WebView view, boolean now) {
        if (view.typingPending && view.focused != null && (now || System.currentTimeMillis() - view.lastKey >= TYPING_PAUSE_MS)) {
            send(view, view.focused, false);
        }
    }

    private static void send(WebView view, String field, boolean entered) {
        view.typingPending = false;
        JsonObject element = new JsonObject();
        element.addProperty("tag", "input");
        element.addProperty("id", "");
        element.addProperty("name", field);
        element.addProperty("event", field);
        element.addProperty("value", view.typed.getOrDefault(field, ""));
        element.addProperty("enter", entered);
        Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(view.origin, element.toString()));
    }

    /** The wheel over a point of the panel: a box there that scrolls moves, otherwise the page. */
    static void scroll(WebView view, float dy, float x, float y) {
        float pageY = y + view.scrollY;
        WORKER.execute(() -> {
            float moved = view.page == null ? 0 : view.page.scroll(dy, x, pageY);
            Minecraft.getInstance().execute(() -> {
                if (moved == 0) {
                    float most = Math.max(0, view.contentHeight - view.height);
                    float next = Math.clamp(view.scrollY + dy, 0, most);
                    if (next == view.scrollY) {
                        return;
                    }
                    view.scrollY = next;
                }
                view.repaint = true;
                schedule(view);
            });
        });
    }

    /** The pointer moved (0), went down (1) or up (2) over the panel: :hover and :active show. */
    static void pointer(WebView view, int kind, float x, float y) {
        float pageY = y + view.scrollY;
        WORKER.execute(() -> {
            if (view.page != null && view.page.pointer(kind, x, pageY)) {
                Minecraft.getInstance().execute(() -> {
                    view.repaint = true;
                    schedule(view);
                });
            }
        });
    }
}
