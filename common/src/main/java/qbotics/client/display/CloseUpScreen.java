package qbotics.client.display;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.net.DisplayPayloads;

/**
 * A Text Monitor or a Control Panel close up (sneak and right-click it): the screen as large as
 * the window allows. Clicks touch it as clicks on the block do, and a slider can be dragged.
 */
public class CloseUpScreen extends Screen {
    private final BlockPos origin;
    // Where the picture is, in this screen's coordinates, and its scale from the display's own pixels.
    private float left;
    private float top;
    private float scale = 1;
    private int virtualW = 1;
    private int virtualH = 1;
    /** The slider being dragged, or null. */
    private ClientDisplays.@Nullable Widget dragging;
    private long lastSent;

    public CloseUpScreen(BlockPos origin) {
        super(Component.translatable("screen.qbotics.close_up"));
        this.origin = origin;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        // Walking away closes it.
        if (minecraft != null && minecraft.player != null
                && minecraft.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(origin)) > 12 * 12) {
            onClose();
        }
    }

    private void layOut(Object view) {
        if (view instanceof ClientDisplays.TextView text) {
            virtualW = DisplayDrawing.textWidth(text);
            virtualH = DisplayDrawing.textHeight(text);
        } else if (view instanceof ClientDisplays.PanelView panel) {
            virtualW = DisplayDrawing.panelWidth(panel);
            virtualH = DisplayDrawing.panelHeight(panel);
        }
        float room = Math.min((width - 24) / (float) virtualW, (height - 40) / (float) virtualH);
        scale = Math.max(0.1f, room);
        left = (width - virtualW * scale) / 2;
        top = (height - 16 - virtualH * scale) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Object view = ClientDisplays.view(origin);
        if (view == null) {
            graphics.centeredText(font, Component.translatable("screen.qbotics.close_up_waiting"), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        layOut(view);
        int frameX0 = (int) left - 4;
        int frameY0 = (int) top - 4;
        int frameX1 = (int) (left + virtualW * scale) + 4;
        int frameY1 = (int) (top + virtualH * scale) + 4;
        graphics.fill(frameX0, frameY0, frameX1, frameY1, 0xFF3C4148);
        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);
        GuiCanvas canvas = new GuiCanvas(graphics);
        if (view instanceof ClientDisplays.TextView text) {
            DisplayDrawing.drawText(canvas, text);
        } else {
            DisplayDrawing.drawPanel(canvas, (ClientDisplays.PanelView) view);
        }
        graphics.pose().popMatrix();
        graphics.centeredText(font, Component.translatable("screen.qbotics.close_up_hint"), width / 2, height - 14, 0xFFB0B0B0);
    }

    /** Where a mouse point is on the display, as fractions of its width and height, or null if off it. */
    private float @Nullable [] at(double x, double y) {
        float fx = (float) ((x - left) / (virtualW * scale));
        float fy = (float) ((y - top) / (virtualH * scale));
        return fx < 0 || fy < 0 || fx >= 1 || fy >= 1 ? null : new float[] {fx, fy};
    }

    private void send(float[] at) {
        Qbotics.platform().sendToServer(new DisplayPayloads.Touch(origin, at[0], at[1]));
        lastSent = System.currentTimeMillis();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        float[] at = at(event.x(), event.y());
        if (at == null || event.button() != 0) {
            return false;
        }
        dragging = null;
        if (ClientDisplays.view(origin) instanceof ClientDisplays.PanelView panel) {
            double gx = at[0] * panel.cols;
            double gy = at[1] * panel.rows;
            for (ClientDisplays.Widget widget : panel.widgets) {
                if (gx >= widget.x() && gx < widget.x() + widget.w() && gy >= widget.y() && gy < widget.y() + widget.h()) {
                    dragging = widget.type().equals("slider") ? widget : null;
                }
            }
        }
        send(at);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null && System.currentTimeMillis() - lastSent > 50
                && ClientDisplays.view(origin) instanceof ClientDisplays.PanelView panel) {
            // Kept within the slider, so dragging past its end doesn't touch what is beside it.
            float fx = (float) ((event.x() - left) / (virtualW * scale));
            float fy = (float) ((event.y() - top) / (virtualH * scale));
            float x0 = dragging.x() / (float) panel.cols;
            float x1 = (dragging.x() + dragging.w()) / (float) panel.cols - 0.0001f;
            float y0 = dragging.y() / (float) panel.rows;
            float y1 = (dragging.y() + dragging.h()) / (float) panel.rows - 0.0001f;
            send(new float[] {Math.clamp(fx, x0, x1), Math.clamp(fy, y0, y1)});
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return super.mouseReleased(event);
    }

    /** Draws onto the screen in the display's own pixels (the pose is scaled). */
    private final class GuiCanvas implements DisplayDrawing.Canvas {
        private final GuiGraphicsExtractor graphics;

        GuiCanvas(GuiGraphicsExtractor graphics) {
            this.graphics = graphics;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            graphics.fill(x0, y0, x1, y1, argb);
        }

        @Override
        public void text(String text, float x, float y, int argb, float size) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            if (size != 1) {
                graphics.pose().scale(size, size);
            }
            graphics.text(font, text, 0, 0, argb, false);
            graphics.pose().popMatrix();
        }

        @Override
        public int width(String text) {
            return font.width(text);
        }

        @Override
        public void item(String id, int x, int y, int size) {
            net.minecraft.world.item.ItemStack stack = WebItems.stack(id);
            if (!stack.isEmpty()) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y);
                graphics.pose().scale(size / 16f, size / 16f);
                graphics.item(stack, 0, 0);
                graphics.pose().popMatrix();
            }
        }
    }
}
