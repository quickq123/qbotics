package qbotics.client.ide;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws the editor with Minecraft's GUI renderer. */
final class GameUi extends Ui {
    private final GuiGraphicsExtractor graphics;
    private final Font font;

    GameUi(GuiGraphicsExtractor graphics, Font font) {
        this.graphics = graphics;
        this.font = font;
    }

    @Override
    void fill(int x, int y, int width, int height, int color) {
        if (width > 0 && height > 0) {
            graphics.fill(x, y, x + width, y + height, color);
        }
    }

    @Override
    void text(String text, int x, int y, int color) {
        graphics.text(font, text, x, y, color, false);
    }

    @Override
    int textWidth(String text) {
        return font.width(text);
    }

    @Override
    void clip(int x, int y, int width, int height) {
        graphics.enableScissor(x, y, x + Math.max(0, width), y + Math.max(0, height));
    }

    @Override
    void unclip() {
        graphics.disableScissor();
    }
}
