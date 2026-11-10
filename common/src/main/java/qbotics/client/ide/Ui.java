package qbotics.client.ide;

/**
 * What the editor draws with, and its colour theme. The four abstract methods are all a
 * surface has to provide: {@link GameUi} draws in Minecraft; the offline preview tool has one
 * that draws into an image.
 */
abstract class Ui {
    /** One character cell of code. Minecraft's font is proportional; cells make a fixed grid. */
    static final int CELL_W = 6;
    static final int CELL_H = 10;

    // A dark theme in the style of popular code editors.
    static final int EDITOR_BG = 0xFF1E1E1E;
    static final int SIDE_BG = 0xFF252526;
    static final int BAR_BG = 0xFF333333;
    static final int TAB_BG = 0xFF2D2D2D;
    static final int LINE_HIGHLIGHT = 0xFF2A2D2E;
    static final int SELECTION = 0xFF264F78;
    static final int BORDER = 0xFF3C3C3C;
    static final int HOVER = 0xFF37373D;
    static final int ACTIVE_ROW = 0xFF094771;
    static final int STATUS_BG = 0xFF007ACC;
    static final int ACCENT = 0xFF0E639C;
    static final int TEXT = 0xFFD4D4D4;
    static final int TEXT_DIM = 0xFF858585;
    static final int TEXT_BRIGHT = 0xFFFFFFFF;
    static final int ERROR = 0xFFF14C4C;
    static final int OK_GREEN = 0xFF89D185;
    static final int FOLDER = 0xFFDCB67A;
    static final int MENU_BG = 0xFF2B2B2C;
    static final int SHADOW = 0x80000000;

    static final int SYN_KEYWORD = 0xFF569CD6;
    static final int SYN_CONTROL = 0xFFC586C0;
    static final int SYN_BUILTIN = 0xFF4EC9B0;
    static final int SYN_FUNCTION = 0xFFDCDCAA;
    static final int SYN_STRING = 0xFFCE9178;
    static final int SYN_NUMBER = 0xFFB5CEA8;
    static final int SYN_COMMENT = 0xFF6A9955;
    static final int SYN_KEY = 0xFF9CDCFE;

    private static final String[] GLYPHS = new String[128];
    private static final int[] GLYPH_OFFSET = new int[128];
    private static boolean glyphsReady;

    /** A filled rectangle. Colours are 0xAARRGGBB. */
    abstract void fill(int x, int y, int width, int height, int color);

    /** Ordinary (proportional) text, for labels, menus and file names. */
    abstract void text(String text, int x, int y, int color);

    abstract int textWidth(String text);

    /** Until unclip(), nothing is drawn outside this rectangle. */
    abstract void clip(int x, int y, int width, int height);

    abstract void unclip();

    private void prepareGlyphs() {
        for (char c = 0; c < 128; c++) {
            GLYPHS[c] = String.valueOf(c);
            GLYPH_OFFSET[c] = (CELL_W - textWidth(GLYPHS[c])) / 2;
        }
        glyphsReady = true;
    }

    void outline(int x, int y, int width, int height, int color) {
        fill(x, y, width, 1, color);
        fill(x, y + height - 1, width, 1, color);
        fill(x, y, 1, height, color);
        fill(x + width - 1, y, 1, height, color);
    }

    /** Text cut short with "..." if it would be wider than maxWidth. */
    String fit(String text, int maxWidth) {
        if (textWidth(text) <= maxWidth) {
            return text;
        }
        int end = text.length();
        while (end > 0 && textWidth(text.substring(0, end) + "...") > maxWidth) {
            end--;
        }
        return text.substring(0, end) + "...";
    }

    /** Code text on the character grid: characters from..to of text, the first drawn at (x, y). */
    void mono(CharSequence text, int from, int to, int x, int y, int color) {
        if (!glyphsReady) {
            prepareGlyphs();
        }
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                continue;
            }
            int cellX = x + (i - from) * CELL_W;
            if (c < 128) {
                text(GLYPHS[c], cellX + GLYPH_OFFSET[c], y + 1, color);
            } else {
                String glyph = String.valueOf(c);
                text(glyph, cellX + (CELL_W - textWidth(glyph)) / 2, y + 1, color);
            }
        }
    }

    void mono(CharSequence text, int x, int y, int color) {
        mono(text, 0, text.length(), x, y, color);
    }

    /** A small right-pointing "play" triangle, about 5 wide and 7 tall. */
    void playIcon(int x, int y, int color) {
        for (int i = 0; i < 4; i++) {
            fill(x + i, y + i, 1, 7 - 2 * i, color);
        }
    }

    /** A small arrow for folders: pointing down when open, right when closed. */
    void arrow(int x, int y, boolean open, int color) {
        if (open) {
            fill(x, y + 2, 5, 1, color);
            fill(x + 1, y + 3, 3, 1, color);
            fill(x + 2, y + 4, 1, 1, color);
        } else {
            fill(x + 1, y, 1, 5, color);
            fill(x + 2, y + 1, 1, 3, color);
            fill(x + 3, y + 2, 1, 1, color);
        }
    }

    /** A simple rectangle with position and size; all the screen's layout is made of these. */
    record Rect(int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }
}
