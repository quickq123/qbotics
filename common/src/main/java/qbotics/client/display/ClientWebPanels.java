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
        /** What this player has typed, by field name, and the field with the keyboard. */
        final Map<String, String> typed = new HashMap<>();
        @Nullable String focused;
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
    }

    private static final Map<BlockPos, WebView> VIEWS = new HashMap<>();
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
        BufferedImage picture = url.startsWith("image:") ? view.pictures.get(url.substring(6)) : null;
        return picture == null ? null : new float[] {picture.getWidth() * 2, picture.getHeight() * 2};
    }

    private static @Nullable BufferedImage image(WebView view, String url) {
        return url.startsWith("image:") ? view.pictures.get(url.substring(6)) : null;
    }

    /** Texture pixels per CSS pixel: twice for small panels, so their text is sharp. */
    static int scale(WebView view) {
        return Math.max(view.width, view.height) <= 512 ? 2 : 1;
    }

    /** Asks the worker for a new picture if one is wanted and none is being made. Render thread. */
    private static void schedule(WebView view) {
        if (view.busy || !(view.dirty || view.repaint)) {
            return;
        }
        boolean relayout = view.dirty || view.page == null;
        view.busy = true;
        view.dirty = false;
        view.repaint = false;
        String page = relayout ? WebInputs.rewrite(WebTemplate.fill(view.html, view.state), Map.copyOf(view.typed), view.focused) : null;
        int width = view.width;
        int height = view.height;
        int scale = scale(view);
        float scroll = view.scrollY;
        WORKER.execute(() -> {
            NativeImage image = null;
            List<WebPainter.Overlay> overlays = new ArrayList<>();
            float shownScroll = scroll;
            try {
                if (view.page == null) {
                    view.page = new WebPage(url -> imageSize(view, url));
                }
                if (page != null) {
                    view.page.load(page, PANEL_CSS);
                    view.contentHeight = view.page.layout(width, height);
                }
                shownScroll = Math.clamp(scroll, 0, Math.max(0, view.contentHeight - height));
                image = toNative(WebPainter.paint(view.page, shownScroll, scale, url -> image(view, url), overlays));
            } catch (RuntimeException e) {
                Qbotics.LOGGER.warn("A web panel's page couldn't be drawn", e);
            }
            NativeImage made = image;
            float clamped = shownScroll;
            Minecraft.getInstance().execute(() -> {
                view.busy = false;
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
            Minecraft.getInstance().execute(() -> {
                JsonObject element = null;
                try {
                    element = hit == null ? null : JsonParser.parseString(hit).getAsJsonObject();
                } catch (RuntimeException e) {
                    return;
                }
                String input = element == null ? "" : element.get("input").getAsString();
                if (!input.isEmpty()) {
                    if (canType) {
                        focus(view, input);
                    }
                    return;
                }
                focus(view, null);
                if (element != null) {
                    Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(origin, element.toString()));
                }
            });
        });
    }

    /** Gives a field the keyboard (or takes it away, with null). Render thread. */
    static void focus(WebView view, @Nullable String field) {
        if (field == null ? view.focused == null : field.equals(view.focused)) {
            return;
        }
        sendTyping(view, true);
        view.focused = field;
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
        } else if (now.length() < WebInputs.MAX_LENGTH && c >= ' ') {
            now = now + c;
        } else {
            return;
        }
        view.typed.put(field, now);
        view.typingPending = true;
        view.lastKey = System.currentTimeMillis();
        view.dirty = true;
        schedule(view);
    }

    /** Enter in a field: what it holds goes to the program now, marked as entered. */
    static void enter(WebView view) {
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
