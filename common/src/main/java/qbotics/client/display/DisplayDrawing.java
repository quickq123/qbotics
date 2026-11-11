package qbotics.client.display;

import qbotics.display.ScreenContent;

/**
 * Draws a Text Monitor's or a Control Panel's screen onto a {@link Canvas}: the same drawing
 * on the block in the world and in the close-up view. Sizes are in the canvas's own pixels.
 */
public final class DisplayDrawing {
    /** A text screen's character cell. */
    public static final int CHAR_W = 6;
    public static final int CHAR_H = 9;
    /** A control panel's grid cell. */
    public static final int CELL = 32;

    public static final int PANEL_BG = 0xFF1B1F24;
    private static final int WIDGET_BG = 0xFF262B33;
    private static final int TRACK = 0xFF11141A;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF9AA3AE;
    private static final int BLUE = 0xFF3A6EA5;
    private static final int GREEN = 0xFF4CAF50;
    private static final int RED = 0xFFE04848;
    private static final int CYAN = 0xFF4CC3D9;

    private DisplayDrawing() {
    }

    /** Where drawing goes. */
    public interface Canvas {
        void fill(int x0, int y0, int x1, int y1, int argb);

        /** Text with its top left at (x, y), drawn at a size (1 is the font's own). */
        void text(String text, float x, float y, int argb, float size);

        int width(String text);
    }

    public static int textWidth(ClientDisplays.TextView view) {
        return view.cols * CHAR_W;
    }

    public static int textHeight(ClientDisplays.TextView view) {
        return view.rows * CHAR_H;
    }

    public static int panelWidth(ClientDisplays.PanelView view) {
        return Math.max(1, view.cols) * CELL;
    }

    public static int panelHeight(ClientDisplays.PanelView view) {
        return Math.max(1, view.rows) * CELL;
    }

    static int rgb(int index) {
        return 0xFF000000 | ScreenContent.COLOR_RGB[index & 15];
    }

    // ---- text monitor ----------------------------------------------------------------------

    public static void drawText(Canvas canvas, ClientDisplays.TextView view) {
        for (int y = 0; y < view.rows; y++) {
            // Backgrounds, a run of one colour at a time.
            int start = 0;
            for (int x = 1; x <= view.cols; x++) {
                if (x == view.cols || (view.colors[y][x] & 15) != (view.colors[y][start] & 15)) {
                    canvas.fill(start * CHAR_W, y * CHAR_H, x * CHAR_W, (y + 1) * CHAR_H, rgb(view.colors[y][start]));
                    start = x;
                }
            }
        }
        for (int y = 0; y < view.rows; y++) {
            for (int x = 0; x < view.cols; x++) {
                char c = view.text[y][x];
                if (c != ' ') {
                    String s = String.valueOf(c);
                    int w = canvas.width(s);
                    canvas.text(s, x * CHAR_W + (CHAR_W - w + 1) / 2f, y * CHAR_H + 1, rgb((view.colors[y][x] >> 4) & 15), 1);
                }
            }
        }
    }

    // ---- control panel ---------------------------------------------------------------------

    public static void drawPanel(Canvas canvas, ClientDisplays.PanelView view) {
        canvas.fill(0, 0, panelWidth(view), panelHeight(view), PANEL_BG);
        for (ClientDisplays.Widget widget : view.widgets) {
            if (widget.x() < 0 || widget.y() < 0 || widget.x() >= view.cols || widget.y() >= view.rows) {
                continue; // off the panel (it may have been made smaller)
            }
            int x0 = widget.x() * CELL + 2;
            int y0 = widget.y() * CELL + 2;
            int x1 = Math.min(widget.x() + widget.w(), view.cols) * CELL - 2;
            int y1 = Math.min(widget.y() + widget.h(), view.rows) * CELL - 2;
            drawWidget(canvas, view, widget, x0, y0, x1, y1);
        }
    }

    private static int accent(ClientDisplays.Widget widget, int fallback) {
        return widget.color() >= 0 ? rgb(widget.color()) : fallback;
    }

    private static int darker(int argb) {
        int r = ((argb >> 16) & 255) / 3;
        int g = ((argb >> 8) & 255) / 3;
        int b = (argb & 255) / 3;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lighter(int argb) {
        int r = Math.min(255, ((argb >> 16) & 255) + 50);
        int g = Math.min(255, ((argb >> 8) & 255) + 50);
        int b = Math.min(255, (argb & 255) + 50);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Text cut to fit a width, with "..." if it had to be cut. */
    private static String fit(Canvas canvas, String text, int width, float size) {
        if (canvas.width(text) * size <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && (canvas.width(cut + "..") * size) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.isEmpty() ? "" : cut + "..";
    }

    private static void centered(Canvas canvas, String text, int x0, int x1, float y, int color, float size) {
        String shown = fit(canvas, text, x1 - x0 - 2, size);
        canvas.text(shown, (x0 + x1 - canvas.width(shown) * size) / 2f, y, color, size);
    }

    /** A number as people write it: no ".0" on whole numbers, at most two decimals otherwise. */
    public static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        String text = String.format(java.util.Locale.ROOT, "%.2f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }

    private static String withUnit(ClientDisplays.Widget widget) {
        return widget.unit().isEmpty() ? number(widget.value()) : number(widget.value()) + " " + widget.unit();
    }

    private static double fraction(ClientDisplays.Widget widget, double value) {
        return widget.max() > widget.min() ? Math.clamp((value - widget.min()) / (widget.max() - widget.min()), 0, 1) : 0;
    }

    private static void drawWidget(Canvas c, ClientDisplays.PanelView view, ClientDisplays.Widget w, int x0, int y0, int x1, int y1) {
        int width = x1 - x0;
        int height = y1 - y0;
        float midY = (y0 + y1) / 2f - 4;
        switch (w.type()) {
            case "button" -> {
                boolean pressed = view.flashing(w);
                int color = accent(w, BLUE);
                c.fill(x0, y0, x1, y1, pressed ? lighter(color) : color);
                c.fill(x0, y0, x1, y0 + 2, lighter(color));
                c.fill(x0, y1 - 2, x1, y1, darker(color));
                centered(c, w.label(), x0, x1, midY + (pressed ? 1 : 0), TEXT, 1);
            }
            case "toggle" -> {
                boolean on = w.value() != 0;
                c.fill(x0, y0, x1, y1, WIDGET_BG);
                int trackW = Math.min(20, width - 4);
                int tx = x0 + 3;
                int ty = (int) ((y0 + y1) / 2f - 5);
                c.fill(tx, ty, tx + trackW, ty + 10, on ? accent(w, GREEN) : TRACK);
                int knob = on ? tx + trackW - 9 : tx + 1;
                c.fill(knob, ty + 1, knob + 8, ty + 9, TEXT);
                String label = fit(c, w.label(), x1 - (tx + trackW + 4), 1);
                c.text(label, tx + trackW + 4, midY, on ? TEXT : TEXT_DIM, 1);
            }
            case "slider" -> {
                c.fill(x0, y0, x1, y1, WIDGET_BG);
                double f = fraction(w, w.value());
                int color = accent(w, BLUE);
                if (height > width) {
                    centered(c, w.label(), x0, x1, y0 + 2, TEXT_DIM, 1);
                    centered(c, number(w.value()), x0, x1, y1 - 10, TEXT, 1);
                    int top = y0 + 13;
                    int bottom = y1 - 13;
                    int cx = (x0 + x1) / 2;
                    c.fill(cx - 2, top, cx + 2, bottom, TRACK);
                    int knobY = (int) (bottom - f * (bottom - top));
                    c.fill(cx - 2, knobY, cx + 2, bottom, color);
                    c.fill(cx - 7, knobY - 3, cx + 7, knobY + 3, TEXT);
                } else {
                    String value = withUnit(w);
                    c.text(fit(c, w.label(), width - c.width(value) - 8, 1), x0 + 3, y0 + 3, TEXT_DIM, 1);
                    c.text(value, x1 - 3 - c.width(value), y0 + 3, TEXT, 1);
                    int left = x0 + 6;
                    int right = x1 - 6;
                    int cy = Math.max(y0 + 18, (y0 + y1) / 2 + 4);
                    c.fill(left, cy - 2, right, cy + 2, TRACK);
                    int knobX = (int) (left + f * (right - left));
                    c.fill(left, cy - 2, knobX, cy + 2, color);
                    c.fill(knobX - 3, cy - 7, knobX + 3, cy + 7, TEXT);
                }
            }
            case "gauge" -> {
                c.fill(x0, y0, x1, y1, WIDGET_BG);
                double f = fraction(w, w.value());
                int color = accent(w, GREEN);
                String value = withUnit(w);
                if (height > width) {
                    centered(c, w.label(), x0, x1, y0 + 2, TEXT_DIM, 1);
                    centered(c, value, x0, x1, y1 - 10, TEXT, 1);
                    int top = y0 + 13;
                    int bottom = y1 - 13;
                    c.fill(x0 + 6, top, x1 - 6, bottom, TRACK);
                    c.fill(x0 + 6, (int) (bottom - f * (bottom - top)), x1 - 6, bottom, color);
                } else {
                    c.text(fit(c, w.label(), width - c.width(value) - 8, 1), x0 + 3, y0 + 3, TEXT_DIM, 1);
                    c.text(value, x1 - 3 - c.width(value), y0 + 3, TEXT, 1);
                    int top = Math.min(y0 + 14, y1 - 8);
                    c.fill(x0 + 3, top, x1 - 3, y1 - 3, TRACK);
                    c.fill(x0 + 3, top, (int) (x0 + 3 + f * (width - 6)), y1 - 3, color);
                }
            }
            case "lamp" -> {
                boolean on = w.value() != 0;
                int color = accent(w, RED);
                int size = Math.min(12, Math.min(width, height) - 4);
                int lx = x0 + 3;
                int ly = (int) ((y0 + y1) / 2f - size / 2f);
                c.fill(lx - 1, ly - 1, lx + size + 1, ly + size + 1, TRACK);
                c.fill(lx, ly, lx + size, ly + size, on ? color : darker(color));
                if (on) {
                    c.fill(lx + 2, ly + 2, lx + size / 2, ly + size / 2, lighter(color));
                }
                c.text(fit(c, w.label(), x1 - (lx + size + 4), 1), lx + size + 4, midY, on ? TEXT : TEXT_DIM, 1);
            }
            case "label" -> {
                float size = height >= 30 && c.width(w.label()) * 2 <= width ? 2 : 1;
                c.text(fit(c, w.label(), width - 2, size), x0 + 1, (y0 + y1) / 2f - 4 * size, accent(w, TEXT), size);
            }
            case "readout" -> {
                c.fill(x0, y0, x1, y1, WIDGET_BG);
                centered(c, w.label(), x0, x1, y0 + 2, TEXT_DIM, 1);
                String value = withUnit(w);
                float size = height >= 30 && c.width(value) * 2 + 4 <= width ? 2 : 1;
                centered(c, value, x0, x1, Math.max(y0 + 12, (y0 + y1) / 2f - 4 * size + 4), accent(w, TEXT), size);
            }
            case "graph" -> {
                c.fill(x0, y0, x1, y1, WIDGET_BG);
                String value = w.history().length > 0 ? withUnit(w) : "";
                c.text(fit(c, w.label(), width - c.width(value) - 8, 1), x0 + 3, y0 + 3, TEXT_DIM, 1);
                c.text(value, x1 - 3 - c.width(value), y0 + 3, TEXT, 1);
                int left = x0 + 3;
                int right = x1 - 3;
                int top = Math.min(y0 + 14, y1 - 6);
                int bottom = y1 - 3;
                c.fill(left, top, right, bottom, TRACK);
                double[] points = w.history();
                if (points.length > 0) {
                    double min = w.min();
                    double max = w.max();
                    if (!(max > min)) {
                        min = Double.MAX_VALUE;
                        max = -Double.MAX_VALUE;
                        for (double p : points) {
                            min = Math.min(min, p);
                            max = Math.max(max, p);
                        }
                        if (max <= min) {
                            max = min + 1;
                        }
                    }
                    int color = accent(w, CYAN);
                    int slots = qbotics.display.PanelScreen.GRAPH_POINTS;
                    float step = (right - left) / (float) slots;
                    int first = slots - points.length;
                    for (int i = 0; i < points.length; i++) {
                        double f = Math.clamp((points[i] - min) / (max - min), 0, 1);
                        int px0 = (int) (left + (first + i) * step);
                        int px1 = Math.max(px0 + 1, (int) (left + (first + i + 1) * step));
                        int py = (int) (bottom - f * (bottom - top));
                        c.fill(px0, py, px1, bottom, darker(color));
                        c.fill(px0, py, px1, Math.min(bottom, py + 2), color);
                    }
                }
            }
            default -> {
            }
        }
    }
}
