package qbotics.client.display;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import qbotics.web.WebPainter;

/**
 * A Web Panel close up (sneak and right-click it with an empty hand): the page as large as the
 * window allows. The pointer hovers as in a browser, clicks reach the program, a text field
 * takes typing, and the wheel scrolls.
 */
public class WebCloseUpScreen extends Screen {
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_KP_ENTER = 335;

    private final BlockPos origin;
    private float left;
    private float top;
    private float scale = 1;
    private long lastMove;

    public WebCloseUpScreen(BlockPos origin) {
        super(Component.translatable("screen.qbotics.close_up"));
        this.origin = origin;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (minecraft != null && minecraft.player != null
                && minecraft.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(origin)) > 12 * 12) {
            onClose();
        }
    }

    @Override
    public void removed() {
        super.removed();
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (view != null) {
            ClientWebPanels.focus(view, null); // leaving sends what was typed
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        Identifier texture = view == null ? null : ClientWebPanels.texture(view);
        if (view == null || texture == null) {
            graphics.centeredText(font, Component.translatable("screen.qbotics.close_up_waiting"), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        scale = Math.max(0.1f, Math.min((width - 24) / (float) view.width(), (height - 40) / (float) view.height()));
        left = (width - view.width() * scale) / 2;
        top = (height - 16 - view.height() * scale) / 2;
        int x0 = Math.round(left);
        int y0 = Math.round(top);
        int x1 = Math.round(left + view.width() * scale);
        int y1 = Math.round(top + view.height() * scale);
        graphics.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, 0xFF3C4148);
        graphics.blit(texture, x0, y0, x1, y1, 0, 1, 0, 1);
        // Item icons, drawn by the game where the page left room for them.
        for (WebPainter.Overlay overlay : view.overlays()) {
            ItemStack stack = WebItems.stack(overlay.url());
            if (stack.isEmpty()) {
                continue;
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate(left + overlay.x() * scale, top + overlay.y() * scale);
            graphics.pose().scale(overlay.width() * scale / 16f, overlay.height() * scale / 16f);
            graphics.item(stack, 0, 0);
            graphics.pose().popMatrix();
        }
        graphics.centeredText(font, Component.translatable(view.focused() != null ? "screen.qbotics.web.typing" : "screen.qbotics.web.hint"),
                width / 2, height - 14, 0xFFB0B0B0);
    }

    /** A mouse point on the panel, in its CSS pixels, or null if off it. */
    private float[] at(double x, double y) {
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (view == null) {
            return null;
        }
        float px = (float) ((x - left) / scale);
        float py = (float) ((y - top) / scale);
        return px < 0 || py < 0 || px >= view.width() || py >= view.height() ? null : new float[] {px, py};
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(x, y);
        long now = System.currentTimeMillis();
        float[] at = at(x, y);
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (at != null && view != null && now - lastMove > 50) {
            lastMove = now;
            ClientWebPanels.pointer(view, 0, at[0], at[1]);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        float[] at = at(event.x(), event.y());
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (view == null || event.button() != 0) {
            return false;
        }
        if (at == null) {
            ClientWebPanels.focus(view, null);
            return false;
        }
        ClientWebPanels.pointer(view, 1, at[0], at[1]);
        ClientWebPanels.click(view, at[0], at[1], true);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        float[] at = at(event.x(), event.y());
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (at != null && view != null) {
            ClientWebPanels.pointer(view, 2, at[0], at[1]);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        float[] at = at(x, y);
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (at != null && view != null && scrollY != 0) {
            ClientWebPanels.scroll(view, (float) (-scrollY * 40), at[0], at[1]);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (view != null && view.focused() != null) {
            for (char c : Character.toChars(event.codepoint())) {
                ClientWebPanels.type(view, c);
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        if (view != null && view.focused() != null) {
            switch (event.key()) {
                case KEY_BACKSPACE -> {
                    ClientWebPanels.type(view, '\b');
                    return true;
                }
                case KEY_ENTER, KEY_KP_ENTER -> {
                    ClientWebPanels.enter(view);
                    return true;
                }
                case KEY_ESCAPE -> {
                    ClientWebPanels.focus(view, null); // the first Escape leaves the field
                    return true;
                }
                default -> {
                    return true; // other keys (E, the hotbar numbers) don't reach the game while typing
                }
            }
        }
        return super.keyPressed(event);
    }
}
