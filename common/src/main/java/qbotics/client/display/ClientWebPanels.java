package qbotics.client.display;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
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
import qbotics.web.WebPage;
import qbotics.web.WebPainter;

/**
 * Web Panels in the player's game (design doc §10.3). Each panel's page is laid out by its
 * own {@link WebPage} and painted into a texture on one background thread, so the game never
 * waits for it; the renderer draws the texture on the panel's face. Clicks are found here,
 * where the page is laid out, and the server is told what was clicked.
 */
public final class ClientWebPanels {
    private ClientWebPanels() {
    }

    /** The game's own CSS for panels, under the page's: dark, like the other screens. */
    static final String PANEL_CSS = "html { background: #0e1216; color: #e6e8ea; } "
            + "body { margin: 8px; font-family: sans-serif; font-size: 14px; } a { color: #8cc8ff; } "
            + "button { background: #2c3440; color: #e6e8ea; border: 1px solid #56606c; border-radius: 3px; padding: 3px 8px; }";
    /** Views not drawn for this long let go of their texture and page (they come back when seen again). */
    private static final long FORGET_MS = 30_000;

    /** One panel as this game has it. Fields set on the render thread unless noted. */
    public static final class WebView {
        final BlockPos origin;
        int width;
        int height;
        String html = "";
        String state = "{}";
        /** Something changed since the last picture was asked for. */
        boolean dirty = true;
        /** A picture is being made on the worker. */
        boolean busy;
        /** Only the pointer changed (hover): paint again without laying out. */
        boolean repaint;
        /** Worker only. */
        @Nullable WebPage page;
        /** A finished picture, handed from the worker to the render thread. */
        volatile @Nullable NativeImage ready;
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

        public @Nullable Identifier texture() {
            return textureId;
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
        view.dirty = true;
        schedule(view);
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
        boolean relayout = view.dirty;
        view.busy = true;
        view.dirty = false;
        view.repaint = false;
        String page = qbotics.web.WebTemplate.fill(view.html, view.state);
        int width = view.width;
        int height = view.height;
        int scale = scale(view);
        WORKER.execute(() -> {
            NativeImage image = null;
            try {
                if (view.page == null) {
                    view.page = new WebPage(url -> null);
                    relayoutNeeded(view, page, width, height);
                } else if (relayout) {
                    relayoutNeeded(view, page, width, height);
                }
                image = toNative(WebPainter.paint(view.page, 0, scale, url -> null));
            } catch (RuntimeException e) {
                Qbotics.LOGGER.warn("A web panel's page couldn't be drawn", e);
            }
            NativeImage made = image;
            Minecraft.getInstance().execute(() -> {
                view.busy = false;
                NativeImage old = view.ready;
                view.ready = made;
                if (old != null) {
                    old.close();
                }
                schedule(view); // anything that changed meanwhile
            });
        });
    }

    private static void relayoutNeeded(WebView view, String page, int width, int height) {
        view.page.load(page, PANEL_CSS);
        view.page.layout(width, height);
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

    // ---- touching ----------------------------------------------------------------------------

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
        click(view, (float) fx * view.width, (float) fy * view.height);
        return true;
    }

    /** A click at a point of the page (CSS pixels): what it is on goes to the server. */
    static void click(WebView view, float x, float y) {
        BlockPos origin = view.origin;
        WORKER.execute(() -> {
            if (view.page == null) {
                return;
            }
            String hit = view.page.hit(x, y);
            if (hit != null) {
                Qbotics.platform().sendToServer(new DisplayPayloads.WebClick(origin, hit));
            }
        });
    }

    /** The pointer moved (0), went down (1) or up (2) over the page: :hover and :active show. */
    static void pointer(WebView view, int kind, float x, float y) {
        WORKER.execute(() -> {
            if (view.page != null && view.page.pointer(kind, x, y)) {
                Minecraft.getInstance().execute(() -> {
                    view.repaint = true;
                    schedule(view);
                });
            }
        });
    }
}
