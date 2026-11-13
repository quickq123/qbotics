package qbotics.client.ide;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import qbotics.Qbotics;

/**
 * Draws the editor with Minecraft's GUI renderer.
 *
 * <p>Text is in two fonts made for screens (assets/qbotics/font): JetBrains Mono for code and
 * Inter for everything else. Each comes in one version per zoom level ({@code code_3} is drawn
 * from glyphs three times as fine as an editor unit), so at any size every glyph pixel is one
 * pixel of the window and the text stays sharp. With crisp off, it is Minecraft's own font.
 */
final class GameUi extends Ui {
    /** The finest zoom level the fonts are made for (make_assets.py writes code_1 to this). */
    static final int FINEST = 12;

    private final GuiGraphicsExtractor graphics;
    private final Font font;
    private final Style uiStyle;
    private final Style codeStyle;
    private final Object codeFont;

    /** @param pixelSize window pixels per editor unit; crisp: the screen fonts, not Minecraft's */
    GameUi(GuiGraphicsExtractor graphics, Font font, int pixelSize, boolean crisp) {
        this.graphics = graphics;
        this.font = font;
        int level = Math.clamp(pixelSize, 1, FINEST);
        this.uiStyle = crisp ? Style.EMPTY.withFont(new FontDescription.Resource(Qbotics.id("ui_" + level))) : Style.EMPTY;
        this.codeStyle = crisp ? Style.EMPTY.withFont(new FontDescription.Resource(Qbotics.id("code_" + level))) : Style.EMPTY;
        this.codeFont = crisp ? "code_" + level : "minecraft";
    }

    @Override
    void fill(int x, int y, int width, int height, int color) {
        if (width > 0 && height > 0) {
            graphics.fill(x, y, x + width, y + height, color);
        }
    }

    @Override
    void text(String text, int x, int y, int color) {
        graphics.text(font, FormattedCharSequence.forward(text, uiStyle), x, y, color, false);
    }

    @Override
    int textWidth(String text) {
        return font.width(FormattedCharSequence.forward(text, uiStyle));
    }

    @Override
    void code(String text, int x, int y, int color) {
        graphics.text(font, FormattedCharSequence.forward(text, codeStyle), x, y, color, false);
    }

    @Override
    int codeWidth(String text) {
        return font.width(FormattedCharSequence.forward(text, codeStyle));
    }

    @Override
    Object codeFont() {
        return codeFont;
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
