package qbotics.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * A page's {@code <canvas>}: the 2D drawing calls its script made (batched by prelude.js), done
 * with Java 2D on the canvas's picture, which keeps what was drawn, as a canvas does.
 */
public final class CanvasPainter {
    /** The largest canvas side, in pixels. */
    public static final int MAX_SIDE = 1024;

    private BufferedImage image;
    private final Deque<DrawState> saved = new ArrayDeque<>();
    private DrawState state = new DrawState();
    private Path2D.Double path = new Path2D.Double();

    /** What save() keeps. */
    private static final class DrawState {
        Paint fill = Color.BLACK;
        Paint stroke = Color.BLACK;
        float lineWidth = 1;
        int cap = BasicStroke.CAP_BUTT;
        int join = BasicStroke.JOIN_MITER;
        float @Nullable [] dash;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        String align = "start";
        String baseline = "alphabetic";
        float alpha = 1;
        AffineTransform transform = new AffineTransform();
        @Nullable Shape clip;

        DrawState copy() {
            DrawState c = new DrawState();
            c.fill = fill;
            c.stroke = stroke;
            c.lineWidth = lineWidth;
            c.cap = cap;
            c.join = join;
            c.dash = dash;
            c.font = font;
            c.align = align;
            c.baseline = baseline;
            c.alpha = alpha;
            c.transform = new AffineTransform(transform);
            c.clip = clip;
            return c;
        }
    }

    public CanvasPainter(int width, int height) {
        image = blank(width, height);
    }

    private static BufferedImage blank(int width, int height) {
        return new BufferedImage(Math.clamp(width, 1, MAX_SIDE), Math.clamp(height, 1, MAX_SIDE), BufferedImage.TYPE_INT_ARGB);
    }

    public BufferedImage image() {
        return image;
    }

    /** Starts afresh (the canvas was resized): blank, and its drawing state back to the defaults. */
    public void reset(int width, int height) {
        image = blank(width, height);
        saved.clear();
        state = new DrawState();
        path = new Path2D.Double();
    }

    /** Carries out drawing calls: [name, arguments...] each. Other canvases (for drawImage) by number. */
    public void run(JsonArray ops, IntFunction<@Nullable BufferedImage> canvases) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            for (JsonElement element : ops) {
                JsonArray op = element.getAsJsonArray();
                try {
                    step(g, op, canvases);
                } catch (RuntimeException e) {
                    // A call with wrong arguments is skipped, as a browser ignores it.
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static double d(JsonArray op, int i) {
        return i < op.size() && op.get(i).isJsonPrimitive() ? op.get(i).getAsDouble() : 0;
    }

    private void apply(Graphics2D g) {
        g.setTransform(new AffineTransform());
        g.setClip(state.clip); // kept in canvas pixels
        g.setTransform(state.transform);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(state.alpha, 0f, 1f)));
    }

    private void step(Graphics2D g, JsonArray op, IntFunction<@Nullable BufferedImage> canvases) {
        String name = op.get(0).getAsString();
        switch (name) {
            case "save" -> saved.push(state.copy());
            case "restore" -> {
                if (!saved.isEmpty()) {
                    state = saved.pop();
                }
            }
            case "translate" -> state.transform.translate(d(op, 1), d(op, 2));
            case "rotate" -> state.transform.rotate(d(op, 1));
            case "scale" -> state.transform.scale(d(op, 1), d(op, 2));
            case "transform" -> state.transform = new AffineTransform(d(op, 1), d(op, 2), d(op, 3), d(op, 4), d(op, 5), d(op, 6));
            case "clear" -> {
                apply(g);
                g.setComposite(AlphaComposite.Clear);
                g.fill(new Rectangle2D.Double(d(op, 1), d(op, 2), d(op, 3), d(op, 4)));
            }
            case "fillRect" -> {
                apply(g);
                g.setPaint(state.fill);
                g.fill(new Rectangle2D.Double(d(op, 1), d(op, 2), d(op, 3), d(op, 4)));
            }
            case "strokeRect" -> {
                apply(g);
                g.setPaint(state.stroke);
                g.setStroke(stroke());
                g.draw(new Rectangle2D.Double(d(op, 1), d(op, 2), d(op, 3), d(op, 4)));
            }
            case "begin" -> path = new Path2D.Double();
            case "close" -> path.closePath();
            case "move" -> path.moveTo(at(op, 1).getX(), at(op, 1).getY());
            case "line" -> {
                Point2D p = at(op, 1);
                if (path.getCurrentPoint() == null) {
                    path.moveTo(p.getX(), p.getY());
                } else {
                    path.lineTo(p.getX(), p.getY());
                }
            }
            case "rect" -> path.append(state.transform.createTransformedShape(new Rectangle2D.Double(d(op, 1), d(op, 2), d(op, 3), d(op, 4))), false);
            case "roundRect" -> path.append(state.transform.createTransformedShape(
                    new RoundRectangle2D.Double(d(op, 1), d(op, 2), d(op, 3), d(op, 4), 2 * d(op, 5), 2 * d(op, 5))), false);
            case "arc" -> arc(op, d(op, 3), d(op, 3), 0, 4);
            case "ellipse" -> arc(op, d(op, 3), d(op, 4), d(op, 5), 6);
            case "quad" -> {
                Point2D c = at(op, 1);
                Point2D p = at(op, 3);
                path.quadTo(c.getX(), c.getY(), p.getX(), p.getY());
            }
            case "bezier" -> {
                Point2D c1 = at(op, 1);
                Point2D c2 = at(op, 3);
                Point2D p = at(op, 5);
                path.curveTo(c1.getX(), c1.getY(), c2.getX(), c2.getY(), p.getX(), p.getY());
            }
            case "fill" -> {
                apply(g);
                g.setTransform(new AffineTransform()); // the path is already in canvas pixels
                g.setPaint(state.fill instanceof Color ? state.fill : transformedPaint(state.fill));
                g.fill(path);
            }
            case "stroke" -> {
                apply(g);
                g.setTransform(new AffineTransform());
                g.setPaint(state.stroke instanceof Color ? state.stroke : transformedPaint(state.stroke));
                double scale = Math.sqrt(Math.abs(state.transform.getDeterminant()));
                BasicStroke s = stroke();
                g.setStroke(new BasicStroke((float) (s.getLineWidth() * scale), s.getEndCap(), s.getLineJoin(), s.getMiterLimit(),
                        s.getDashArray(), 0));
                g.draw(path);
            }
            case "clip" -> {
                Shape now = path;
                if (state.clip != null) {
                    java.awt.geom.Area area = new java.awt.geom.Area(state.clip);
                    area.intersect(new java.awt.geom.Area(path));
                    now = area;
                }
                // The clip is kept in canvas pixels, so the transform at the time doesn't apply to it later.
                state.clip = now;
            }
            case "fillText", "strokeText" -> {
                apply(g);
                g.setFont(state.font);
                String text = op.get(1).getAsString();
                float width = (float) state.font.getStringBounds(text, WebPage.FRC).getWidth();
                var metrics = state.font.getLineMetrics(text, WebPage.FRC);
                float x = (float) d(op, 2);
                float y = (float) d(op, 3);
                x -= switch (state.align) {
                    case "center" -> width / 2;
                    case "right", "end" -> width;
                    default -> 0;
                };
                y += switch (state.baseline) {
                    case "top", "hanging" -> metrics.getAscent();
                    case "middle" -> (metrics.getAscent() - metrics.getDescent()) / 2;
                    case "bottom", "ideographic" -> -metrics.getDescent();
                    default -> 0;
                };
                if (name.equals("fillText")) {
                    g.setPaint(state.fill);
                    g.drawString(text, x, y);
                } else {
                    g.setPaint(state.stroke);
                    g.setStroke(stroke());
                    g.draw(state.font.createGlyphVector(WebPage.FRC, text).getOutline(x, y));
                }
            }
            case "drawCanvas" -> {
                BufferedImage other = canvases.apply((int) d(op, 1));
                if (other != null) {
                    apply(g);
                    if (op.size() >= 6) {
                        g.drawImage(other, (int) d(op, 2), (int) d(op, 3), (int) d(op, 4), (int) d(op, 5), null);
                    } else {
                        g.drawImage(other, (int) d(op, 2), (int) d(op, 3), null);
                    }
                }
            }
            case "dash" -> {
                JsonArray list = op.get(1).getAsJsonArray();
                float[] dash = new float[list.size()];
                for (int i = 0; i < dash.length; i++) {
                    dash[i] = Math.max(0.01f, list.get(i).getAsFloat());
                }
                state.dash = dash.length == 0 ? null : dash;
            }
            case "fillStyle" -> state.fill = paint(op.get(1), state.fill);
            case "strokeStyle" -> state.stroke = paint(op.get(1), state.stroke);
            case "lineWidth" -> state.lineWidth = (float) Math.max(0, d(op, 1));
            case "globalAlpha" -> state.alpha = (float) d(op, 1);
            case "font" -> state.font = font(op.get(1).getAsString(), state.font);
            case "textAlign" -> state.align = op.get(1).getAsString();
            case "textBaseline" -> state.baseline = op.get(1).getAsString();
            case "lineCap" -> state.cap = switch (op.get(1).getAsString()) {
                case "round" -> BasicStroke.CAP_ROUND;
                case "square" -> BasicStroke.CAP_SQUARE;
                default -> BasicStroke.CAP_BUTT;
            };
            case "lineJoin" -> state.join = switch (op.get(1).getAsString()) {
                case "round" -> BasicStroke.JOIN_ROUND;
                case "bevel" -> BasicStroke.JOIN_BEVEL;
                default -> BasicStroke.JOIN_MITER;
            };
            default -> {
            }
        }
    }

    /** A point given in the current transform, in canvas pixels (paths are kept in pixels). */
    private Point2D at(JsonArray op, int i) {
        return state.transform.transform(new Point2D.Double(d(op, i), d(op, i + 1)), null);
    }

    private void arc(JsonArray op, double rx, double ry, double rotation, int angles) {
        double x = d(op, 1);
        double y = d(op, 2);
        double a0 = d(op, angles);
        double a1 = d(op, angles + 1);
        boolean ccw = op.size() > angles + 2 && op.get(angles + 2).getAsBoolean();
        double sweep = a1 - a0;
        if (!ccw && sweep < 0) {
            sweep = sweep % (2 * Math.PI) + 2 * Math.PI;
        } else if (ccw && sweep > 0) {
            sweep = sweep % (2 * Math.PI) - 2 * Math.PI;
        }
        if (Math.abs(a1 - a0) >= 2 * Math.PI) {
            sweep = ccw ? -2 * Math.PI : 2 * Math.PI;
        }
        // Java's arcs turn the other way (y up), so the angles are negated.
        Arc2D.Double arc = new Arc2D.Double(x - rx, y - ry, 2 * rx, 2 * ry, -Math.toDegrees(a0), -Math.toDegrees(sweep), Arc2D.OPEN);
        AffineTransform t = new AffineTransform(state.transform);
        if (rotation != 0) {
            t.rotate(rotation, x, y);
        }
        path.append(t.createTransformedShape(arc), path.getCurrentPoint() != null);
    }

    private BasicStroke stroke() {
        return new BasicStroke(state.lineWidth, state.cap, state.join, 10, state.dash, 0);
    }

    private Paint transformedPaint(Paint paint) {
        if (paint instanceof MultipleGradientPaint gradient && !state.transform.isIdentity()) {
            if (gradient instanceof LinearGradientPaint l) {
                return new LinearGradientPaint(l.getStartPoint(), l.getEndPoint(), l.getFractions(), l.getColors(), l.getCycleMethod(),
                        l.getColorSpace(), state.transform);
            }
            if (gradient instanceof RadialGradientPaint r) {
                return new RadialGradientPaint(r.getCenterPoint(), r.getRadius(), r.getFocusPoint(), r.getFractions(), r.getColors(),
                        r.getCycleMethod(), r.getColorSpace(), state.transform);
            }
        }
        return paint;
    }

    private static Paint paint(JsonElement value, Paint fallback) {
        if (value.isJsonObject()) {
            JsonObject g = value.getAsJsonObject();
            JsonArray at = g.getAsJsonArray("at");
            JsonArray stops = g.getAsJsonArray("stops");
            int n = Math.max(2, stops.size());
            float[] fractions = new float[n];
            Color[] colors = new Color[n];
            float last = -1;
            for (int i = 0; i < stops.size(); i++) {
                float f = Math.clamp(stops.get(i).getAsJsonArray().get(0).getAsFloat(), 0f, 1f);
                if (f <= last) {
                    f = Math.min(1f, last + 0.0001f);
                }
                fractions[i] = f;
                colors[i] = color(stops.get(i).getAsJsonArray().get(1).getAsString(), Color.BLACK);
                last = f;
            }
            if (stops.size() < 2) {
                Color only = stops.size() == 1 ? colors[0] : Color.BLACK;
                fractions = new float[] {0, 1};
                colors = new Color[] {only, only};
            }
            if (g.get("kind").getAsString().equals("radial")) {
                return new RadialGradientPaint((float) at.get(0).getAsDouble(), (float) at.get(1).getAsDouble(),
                        Math.max(0.01f, at.get(2).getAsFloat()), fractions, colors, MultipleGradientPaint.CycleMethod.NO_CYCLE);
            }
            Point2D.Float p0 = new Point2D.Float(at.get(0).getAsFloat(), at.get(1).getAsFloat());
            Point2D.Float p1 = new Point2D.Float(at.get(2).getAsFloat(), at.get(3).getAsFloat());
            if (p0.equals(p1)) {
                p1 = new Point2D.Float(p1.x + 0.01f, p1.y);
            }
            return new LinearGradientPaint(p0, p1, fractions, colors, MultipleGradientPaint.CycleMethod.NO_CYCLE);
        }
        return color(value.getAsString(), fallback instanceof Color c ? c : Color.BLACK);
    }

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("white", 0xFFFFFF), Map.entry("red", 0xFF0000), Map.entry("lime", 0x00FF00),
            Map.entry("green", 0x008000), Map.entry("blue", 0x0000FF), Map.entry("yellow", 0xFFFF00), Map.entry("cyan", 0x00FFFF),
            Map.entry("aqua", 0x00FFFF), Map.entry("magenta", 0xFF00FF), Map.entry("fuchsia", 0xFF00FF), Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080), Map.entry("silver", 0xC0C0C0), Map.entry("maroon", 0x800000), Map.entry("olive", 0x808000),
            Map.entry("purple", 0x800080), Map.entry("teal", 0x008080), Map.entry("navy", 0x000080), Map.entry("orange", 0xFFA500),
            Map.entry("pink", 0xFFC0CB), Map.entry("brown", 0xA52A2A), Map.entry("gold", 0xFFD700), Map.entry("darkgray", 0xA9A9A9),
            Map.entry("lightgray", 0xD3D3D3), Map.entry("darkgreen", 0x006400), Map.entry("darkred", 0x8B0000), Map.entry("skyblue", 0x87CEEB),
            Map.entry("steelblue", 0x4682B4), Map.entry("tomato", 0xFF6347), Map.entry("coral", 0xFF7F50), Map.entry("salmon", 0xFA8072),
            Map.entry("crimson", 0xDC143C), Map.entry("indigo", 0x4B0082), Map.entry("violet", 0xEE82EE), Map.entry("tan", 0xD2B48C),
            Map.entry("khaki", 0xF0E68C), Map.entry("limegreen", 0x32CD32), Map.entry("dodgerblue", 0x1E90FF), Map.entry("slategray", 0x708090));

    /** A CSS colour: #rgb, #rgba, #rrggbb, #rrggbbaa, rgb(), rgba(), hsl(), hsla(), a name, or transparent. */
    public static Color color(String css, Color fallback) {
        String s = css.strip().toLowerCase(Locale.ROOT);
        try {
            if (s.equals("transparent")) {
                return new Color(0, 0, 0, 0);
            }
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 3 || hex.length() == 4) {
                    StringBuilder full = new StringBuilder();
                    for (char c : hex.toCharArray()) {
                        full.append(c).append(c);
                    }
                    hex = full.toString();
                }
                long v = Long.parseLong(hex, 16);
                return hex.length() == 8 ? new Color((int) (v >> 24) & 0xFF, (int) (v >> 16) & 0xFF, (int) (v >> 8) & 0xFF, (int) v & 0xFF)
                        : new Color((int) v);
            }
            if (s.startsWith("rgb") || s.startsWith("hsl")) {
                String[] parts = s.substring(s.indexOf('(') + 1, s.lastIndexOf(')')).split("[,\\s/]+");
                float a = parts.length > 3 ? part(parts[3], 1) : 1;
                if (s.startsWith("rgb")) {
                    return new Color(clamp255(part(parts[0], 255)), clamp255(part(parts[1], 255)), clamp255(part(parts[2], 255)),
                            clamp255(a * 255));
                }
                float h = Float.parseFloat(parts[0].replace("deg", "")) / 360f;
                Color rgb = Color.getHSBColor(h, 0, 0);
                float sat = part(parts[1], 1);
                float light = part(parts[2], 1);
                // HSL to RGB
                float q = light < 0.5f ? light * (1 + sat) : light + sat - light * sat;
                float p = 2 * light - q;
                rgb = new Color(clamp255(hue(p, q, h + 1f / 3) * 255), clamp255(hue(p, q, h) * 255), clamp255(hue(p, q, h - 1f / 3) * 255),
                        clamp255(a * 255));
                return rgb;
            }
            Integer named = NAMES.get(s);
            return named == null ? fallback : new Color(named);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1f / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5f) {
            return q;
        }
        if (t < 2f / 3) {
            return p + (q - p) * (2f / 3 - t) * 6;
        }
        return p;
    }

    /** A number, or a percentage of {@code full}. */
    private static float part(String text, float full) {
        return text.endsWith("%") ? Float.parseFloat(text.substring(0, text.length() - 1)) / 100f * full : Float.parseFloat(text);
    }

    private static int clamp255(float v) {
        return Math.clamp(Math.round(v), 0, 255);
    }

    /** A CSS font: "bold 16px sans-serif", "italic 12px monospace". */
    public static Font font(String css, Font fallback) {
        String s = css.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9.]+)px\\s*(.*)").matcher(s);
        if (!m.find()) {
            return fallback;
        }
        float size = Float.parseFloat(m.group(1));
        int style = (s.contains("bold") || s.matches(".*\\b[6-9]00\\b.*") ? Font.BOLD : Font.PLAIN) | (s.contains("italic") ? Font.ITALIC : Font.PLAIN);
        return new Font(WebPage.javaFamily(m.group(2)), style, 1).deriveFont(Math.clamp(size, 1f, 400f));
    }
}
