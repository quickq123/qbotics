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
    /** The tablet this is the view of, or null for a Web Panel block. */
    private final java.util.@org.jspecify.annotations.Nullable UUID tablet;
    private float left;
    private float top;
    private float scale = 1;
    private long lastMove;
    /** An open select's list: its field, options and where (page pixels), or null. */
    private @org.jspecify.annotations.Nullable String dropdownName;
    private @org.jspecify.annotations.Nullable Integer dropdownNode;
    private java.util.List<qbotics.web.WebInputs.Option> dropdownOptions = java.util.List.of();
    private float dropdownX;
    private float dropdownY;
    private static final int OPTION_HEIGHT = 12;

    /** For the screenshot run: a select's list, open. */
    public void dropdownForShots(String name, java.util.List<qbotics.web.WebInputs.Option> options, float x, float y) {
        dropdown(name, null, options, x, y);
    }

    /** Opens a select's list under the point clicked (page pixels). */
    void dropdown(String name, @org.jspecify.annotations.Nullable Integer node, java.util.List<qbotics.web.WebInputs.Option> options, float x, float y) {
        dropdownName = name;
        dropdownNode = node;
        dropdownOptions = options;
        dropdownX = x;
        dropdownY = y;
    }

    /** Where the open list is on this screen: x0, y0, width (screen pixels). */
    private int[] dropdownBox() {
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        float scrollY = view == null ? 0 : view.scrollY;
        int width = 80;
        for (qbotics.web.WebInputs.Option option : dropdownOptions) {
            width = Math.max(width, font.width(option.label()) + 12);
        }
        int x0 = Math.min(Math.round(left + dropdownX * scale), this.width - width - 4);
        int y0 = Math.round(top + (dropdownY - scrollY) * scale) + 6;
        y0 = Math.min(y0, this.height - 4 - OPTION_HEIGHT * dropdownOptions.size());
        return new int[] {Math.max(4, x0), Math.max(4, y0), width};
    }

    public WebCloseUpScreen(BlockPos origin) {
        this(origin, null);
    }

    /** A tablet's view: its tabs above the page. */
    public WebCloseUpScreen(BlockPos origin, java.util.@org.jspecify.annotations.Nullable UUID tablet) {
        super(Component.translatable(tablet == null ? "screen.qbotics.close_up" : "item.qbotics.tablet"));
        this.origin = origin;
        this.tablet = tablet;
    }

    private int bar() {
        return tablet == null ? 0 : ClientTablets.BAR;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (tablet != null) {
            ClientTablets.tick(this, tablet, origin, true);
            return;
        }
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
        if (tablet != null) {
            ClientTablets.drawBar(graphics, font, tablet, width, mouseX, mouseY);
        }
        ClientWebPanels.WebView view = ClientWebPanels.view(origin);
        Identifier texture = view == null ? null : ClientWebPanels.texture(view);
        if (view == null || texture == null) {
            graphics.centeredText(font, Component.translatable("screen.qbotics.close_up_waiting"), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        scale = Math.max(0.1f, Math.min((width - 24) / (float) view.width(), (height - 40 - bar()) / (float) view.height()));
        left = (width - view.width() * scale) / 2;
        top = bar() + (height - 16 - bar() - view.height() * scale) / 2;
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
        if (dropdownName != null) {
            int[] box = dropdownBox();
            graphics.fill(box[0] - 1, box[1] - 1, box[0] + box[2] + 1, box[1] + OPTION_HEIGHT * dropdownOptions.size() + 1, 0xFF8CC8FF);
            for (int i = 0; i < dropdownOptions.size(); i++) {
                int y = box[1] + i * OPTION_HEIGHT;
                boolean over = mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= y && mouseY < y + OPTION_HEIGHT;
                graphics.fill(box[0], y, box[0] + box[2], y + OPTION_HEIGHT, over ? 0xFF2F5D8A : 0xFF1A2129);
                graphics.text(font, dropdownOptions.get(i).label(), box[0] + 5, y + 2, 0xFFE6E8EA, false);
            }
        }
        graphics.centeredText(font, Component.translatable(view.focused() != null ? "screen.qbotics.web.typing" : "screen.qbotics.web.hint"),
                width / 2, height - 14, 0xFFB0B0B0);
        String error = view.lastError();
        if (error != null) {
            // A script's last error, for whoever is writing the page.
            String line = error.lines().findFirst().orElse("");
            graphics.centeredText(font, Component.literal(line.length() > 120 ? line.substring(0, 120) + "..." : line), width / 2, 4 + bar(), 0xFFFF7070);
        }
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
        if (dropdownName != null) {
            // A click picks an option, or anywhere else closes the list.
            int[] box = dropdownBox();
            int i = (int) Math.floor((event.y() - box[1]) / OPTION_HEIGHT);
            ClientWebPanels.WebView view = ClientWebPanels.view(origin);
            if (view != null && event.x() >= box[0] && event.x() < box[0] + box[2] && i >= 0 && i < dropdownOptions.size()) {
                ClientWebPanels.choose(view, dropdownName, dropdownOptions.get(i).value(), dropdownNode);
            }
            dropdownName = null;
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (tablet != null && ClientTablets.clickBar(font, tablet, event.x(), event.y())) {
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
        if (tablet != null && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
            ClientTablets.deletePressed(tablet); // unpair the tab showing (pressed twice)
            return true;
        }
        return super.keyPressed(event);
    }
}
