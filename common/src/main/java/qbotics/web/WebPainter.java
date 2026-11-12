package qbotics.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.Function;

/**
 * Paints a page's display list (see engine/web/bridge.cpp) with Java 2D: fills, gradients,
 * borders with rounded corners, text, list markers, images and clips.
 */
public final class WebPainter {
    private WebPainter() {
    }

    private static final int OP_FILL = 1;
    private static final int OP_TEXT = 2;
    private static final int OP_BORDERS = 3;
    private static final int OP_MARKER = 4;
    private static final int OP_IMAGE = 5;
    private static final int OP_LINEAR = 6;
    private static final int OP_RADIAL = 7;
    private static final int OP_CLIP = 8;
    private static final int OP_UNCLIP = 9;

    /**
     * Something the game draws over the page itself, where the page left room for it: an item's
     * icon ({@code <img src="item:minecraft:iron_ingot">}), drawn by the game's own item renderer.
     * In CSS pixels of the panel (scrolling already taken off).
     */
    public record Overlay(float x, float y, float width, float height, String url) {
    }

    /** Paints a page into a new image, scale pixels per CSS pixel. */
    public static BufferedImage paint(WebPage page, float scrollY, int scale, Function<String, BufferedImage> images) {
        return paint(page, scrollY, scale, images, null);
    }

    /** As paint, and adds the item icons to draw over it to {@code overlays}. */
    public static BufferedImage paint(WebPage page, float scrollY, int scale, Function<String, BufferedImage> images,
            java.util.@org.jspecify.annotations.Nullable List<Overlay> overlays) {
        int w = Math.max(1, Math.round(page.width() * scale));
        int h = Math.max(1, Math.round(page.height() * scale));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.scale(scale, scale);
            paint(g, page, page.displayList(scrollY), images, overlays);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static Color color(int argb) {
        return new Color(argb, true);
    }

    private static Rectangle2D.Float box(ByteBuffer b) {
        return new Rectangle2D.Float(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static float[] radii(ByteBuffer b) {
        float[] r = new float[8];
        for (int i = 0; i < 8; i++) {
            r[i] = b.getFloat();
        }
        return r;
    }

    private static String string(ByteBuffer b) {
        int length = b.getInt();
        byte[] bytes = new byte[length];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A box with rounded corners (top-left x y, top-right, bottom-right, bottom-left), as CSS draws them. */
    static Shape rounded(Rectangle2D.Float r, float[] k) {
        boolean square = true;
        for (float v : k) {
            square &= v <= 0;
        }
        if (square) {
            return r;
        }
        float x = r.x, y = r.y, w = r.width, h = r.height;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x + k[0], y);
        p.lineTo(x + w - k[2], y);
        p.quadTo(x + w, y, x + w, y + k[3]);
        p.lineTo(x + w, y + h - k[5]);
        p.quadTo(x + w, y + h, x + w - k[4], y + h);
        p.lineTo(x + k[6], y + h);
        p.quadTo(x, y + h, x, y + h - k[7]);
        p.lineTo(x, y + k[1]);
        p.quadTo(x, y, x + k[0], y);
        p.closePath();
        return p;
    }

    private static MultipleGradientPaint.CycleMethod none() {
        return MultipleGradientPaint.CycleMethod.NO_CYCLE;
    }

    /** Gradient stops: Java wants strictly rising offsets from 0 to 1. */
    private static Object[] stops(ByteBuffer b) {
        int n = b.getInt();
        float[] at = new float[Math.max(2, n)];
        Color[] colors = new Color[Math.max(2, n)];
        float last = -1;
        for (int i = 0; i < n; i++) {
            float offset = Math.clamp(b.getFloat(), 0f, 1f);
            if (offset <= last) {
                offset = Math.min(1f, last + 0.0001f);
            }
            at[i] = offset;
            colors[i] = color(b.getInt());
            last = offset;
        }
        if (n < 2) {
            Color only = n == 1 ? colors[0] : new Color(0, 0, 0, 0);
            at = new float[] {0, 1};
            colors = new Color[] {only, only};
        }
        return new Object[] {at, colors};
    }

    private static void paint(Graphics2D g, WebPage page, ByteBuffer b, Function<String, BufferedImage> images,
            java.util.@org.jspecify.annotations.Nullable List<Overlay> overlays) {
        Deque<Shape> clips = new ArrayDeque<>();
        while (b.hasRemaining()) {
            int op = b.get();
            switch (op) {
                case OP_FILL -> {
                    Rectangle2D.Float r = box(b);
                    float[] k = radii(b);
                    g.setColor(color(b.getInt()));
                    g.fill(rounded(r, k));
                }
                case OP_TEXT -> {
                    Rectangle2D.Float r = box(b);
                    int font = b.getInt();
                    int argb = b.getInt();
                    int decoration = b.getInt();
                    String text = string(b);
                    Font f = page.fonts.get(font);
                    if ((decoration & 1) != 0) {
                        f = f.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
                    }
                    if ((decoration & 4) != 0) {
                        f = f.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
                    }
                    g.setFont(f);
                    g.setColor(color(argb));
                    float ascent = f.getLineMetrics(text, WebPage.FRC).getAscent();
                    g.drawString(text, r.x, r.y + ascent);
                }
                case OP_BORDERS -> {
                    Rectangle2D.Float r = box(b);
                    float[] k = radii(b);
                    float[] widths = new float[4];
                    int[] styles = new int[4];
                    int[] colors = new int[4];
                    for (int side = 0; side < 4; side++) {
                        widths[side] = b.getFloat();
                        styles[side] = b.getInt();
                        colors[side] = b.getInt();
                    }
                    borders(g, r, k, widths, styles, colors);
                }
                case OP_MARKER -> {
                    Rectangle2D.Float r = box(b);
                    int type = b.getInt();
                    g.setColor(color(b.getInt()));
                    // circle, disc, square are litehtml's list_style_type 1, 2, 3; numbers are drawn as text by litehtml.
                    if (type == 1) {
                        g.setStroke(new BasicStroke(1));
                        g.draw(new Ellipse2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1));
                    } else if (type == 3) {
                        g.fill(r);
                    } else {
                        g.fill(new Ellipse2D.Float(r.x, r.y, r.width, r.height));
                    }
                }
                case OP_IMAGE -> {
                    Rectangle2D.Float r = box(b);
                    Rectangle2D.Float clip = box(b);
                    radii(b);
                    String url = string(b);
                    if (url.startsWith("item:")) {
                        // Left for the game to draw, if it is mostly in sight (within the clip and the panel).
                        Rectangle2D shown = g.getClip() == null ? r : g.getClip().getBounds2D().createIntersection(r);
                        shown = shown.createIntersection(new Rectangle2D.Float(0, 0, page.width(), page.height()));
                        if (overlays != null && shown.getWidth() * shown.getHeight() >= r.width * r.height * 0.6f && r.width > 0) {
                            overlays.add(new Overlay(r.x, r.y, r.width, r.height, url.substring(5)));
                        }
                        continue;
                    }
                    BufferedImage image = images.apply(url);
                    if (image != null) {
                        Shape before = g.getClip();
                        g.clip(clip);
                        g.drawImage(image, Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height), null);
                        g.setClip(before);
                    }
                }
                case OP_LINEAR -> {
                    Rectangle2D.Float r = box(b);
                    float[] k = radii(b);
                    Point2D.Float from = new Point2D.Float(r.x + b.getFloat(), r.y + b.getFloat());
                    Point2D.Float to = new Point2D.Float(r.x + b.getFloat(), r.y + b.getFloat());
                    Object[] s = stops(b);
                    if (from.equals(to)) {
                        to = new Point2D.Float(to.x, to.y + 0.01f);
                    }
                    g.setPaint(new LinearGradientPaint(from, to, (float[]) s[0], (Color[]) s[1], none()));
                    g.fill(rounded(r, k));
                }
                case OP_RADIAL -> {
                    Rectangle2D.Float r = box(b);
                    float[] k = radii(b);
                    float cx = r.x + b.getFloat();
                    float cy = r.y + b.getFloat();
                    float rx = Math.max(0.01f, b.getFloat());
                    b.getFloat();
                    Object[] s = stops(b);
                    g.setPaint(new RadialGradientPaint(cx, cy, rx, (float[]) s[0], (Color[]) s[1], none()));
                    g.fill(rounded(r, k));
                }
                case OP_CLIP -> {
                    Rectangle2D.Float r = box(b);
                    float[] k = radii(b);
                    clips.push(g.getClip() == null ? new Rectangle2D.Float(-1e6f, -1e6f, 2e6f, 2e6f) : g.getClip());
                    g.clip(rounded(r, k));
                }
                case OP_UNCLIP -> {
                    if (!clips.isEmpty()) {
                        g.setClip(clips.pop());
                    }
                }
                default -> {
                    return; // a step this painter doesn't know: stop rather than misread the rest
                }
            }
        }
    }

    /** CSS border styles litehtml uses: 0 none, 1 hidden, 2 dotted, 3 dashed, 4 solid, 5 double, 6 groove, 7 ridge, 8 inset, 9 outset. */
    private static void borders(Graphics2D g, Rectangle2D.Float r, float[] k, float[] widths, int[] styles, int[] colors) {
        boolean same = true;
        for (int side = 1; side < 4; side++) {
            same &= widths[side] == widths[0] && styles[side] == styles[0] && colors[side] == colors[0];
        }
        if (same && widths[0] > 0 && styles[0] > 1) {
            // One border all round: stroke the rounded outline, inset by half its width.
            float w = widths[0];
            Rectangle2D.Float inner = new Rectangle2D.Float(r.x + w / 2, r.y + w / 2, r.width - w, r.height - w);
            float[] ki = new float[8];
            for (int i = 0; i < 8; i++) {
                ki[i] = Math.max(0, k[i] - w / 2);
            }
            g.setColor(color(colors[0]));
            g.setStroke(stroke(w, styles[0]));
            g.draw(rounded(inner, ki));
            return;
        }
        // Sides apart (left, top, right, bottom): each as a band along its edge.
        for (int side = 0; side < 4; side++) {
            float w = widths[side];
            if (w <= 0 || styles[side] <= 1) {
                continue;
            }
            g.setColor(color(colors[side]));
            switch (side) {
                case 0 -> g.fill(new Rectangle2D.Float(r.x, r.y, w, r.height));
                case 1 -> g.fill(new Rectangle2D.Float(r.x, r.y, r.width, w));
                case 2 -> g.fill(new Rectangle2D.Float(r.x + r.width - w, r.y, w, r.height));
                default -> g.fill(new Rectangle2D.Float(r.x, r.y + r.height - w, r.width, w));
            }
        }
    }

    private static BasicStroke stroke(float width, int style) {
        return switch (style) {
            case 2 -> new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {0.01f, width * 2}, 0);
            case 3 -> new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {width * 3, width * 2}, 0);
            default -> new BasicStroke(width);
        };
    }
}
