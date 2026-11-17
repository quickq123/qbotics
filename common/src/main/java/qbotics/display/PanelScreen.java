package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Control Panel's screen (design doc §10.2): ready-made widgets on a grid, four cells to a
 * block each way. Programs place them and set their values; players press and slide them.
 */
public final class PanelScreen extends ScreenContent {
    public static final int CELLS_PER_BLOCK = 4;
    public static final int MAX_WIDGETS = 128;
    public static final int GRAPH_POINTS = 60;
    public static final Set<String> TYPES = Set.of("button", "toggle", "slider", "gauge", "lamp", "label", "readout", "graph",
            "dial", "number", "item");
    /** The looks a panel can have. */
    public static final java.util.List<String> THEMES = java.util.List.of("dark", "light", "industrial", "blueprint", "terminal");

    /** One widget. Values are doubles; a toggle or a lamp is on at 1. */
    static final class Widget {
        int id;
        String type;
        String label = "";
        int x;
        int y;
        int w = 1;
        int h = 1;
        int color = -1;
        double min;
        double max = 100;
        double step;
        double value;
        String unit = "";
        /** For an item display: the item shown ("minecraft:coal"). */
        String item = "";
        List<Double> history = new ArrayList<>();
        /** Goes up with each press, so players' games can flash the button. */
        int presses;

        boolean contains(double gx, double gy) {
            return gx >= x && gx < x + w && gy >= y && gy < y + h;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("type", type);
            json.addProperty("label", label);
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("w", w);
            json.addProperty("h", h);
            json.addProperty("color", color);
            json.addProperty("min", min);
            json.addProperty("max", max);
            json.addProperty("step", step);
            json.addProperty("value", value);
            json.addProperty("unit", unit);
            json.addProperty("presses", presses);
            if (!item.isEmpty()) {
                json.addProperty("item", item);
            }
            if (type.equals("graph")) {
                JsonArray points = new JsonArray();
                history.forEach(points::add);
                json.add("history", points);
            }
            return json;
        }
    }

    private final Map<Integer, Widget> widgets = new LinkedHashMap<>();
    private int nextId = 1;
    private int cols = CELLS_PER_BLOCK;
    private int rows = CELLS_PER_BLOCK;
    private String theme = "dark";

    @Override
    protected void relayout() {
        cols = widthBlocks * CELLS_PER_BLOCK;
        rows = heightBlocks * CELLS_PER_BLOCK;
    }

    public synchronized int cols() {
        return cols;
    }

    public synchronized int rows() {
        return rows;
    }

    /** Everything players need to draw it. */
    public synchronized JsonObject state() {
        JsonObject state = new JsonObject();
        state.addProperty("cols", cols);
        state.addProperty("rows", rows);
        state.addProperty("theme", theme);
        JsonArray list = new JsonArray();
        widgets.values().forEach(widget -> list.add(widget.toJson()));
        state.add("widgets", list);
        return state;
    }

    private Widget widget(JsonArray args) {
        int id = intArg(args, 0, "A widget's number");
        Widget widget = widgets.get(id);
        if (widget == null) {
            throw new IllegalArgumentException("That widget is no longer on the panel (it was cleared or removed).");
        }
        return widget;
    }

    /** Sets a widget's fields from what a program gave. */
    private void apply(Widget widget, JsonObject spec) {
        // A change that is refused changes nothing: a widget left half set (its min above its max)
        // would fail for the next player who touched it.
        String label = widget.label;
        String unit = widget.unit;
        String item = widget.item;
        int x = widget.x;
        int y = widget.y;
        int w = widget.w;
        int h = widget.h;
        int color = widget.color;
        double min = widget.min;
        double max = widget.max;
        double step = widget.step;
        double value = widget.value;
        try {
            applyFields(widget, spec);
        } catch (RuntimeException e) {
            widget.label = label;
            widget.unit = unit;
            widget.item = item;
            widget.x = x;
            widget.y = y;
            widget.w = w;
            widget.h = h;
            widget.color = color;
            widget.min = min;
            widget.max = max;
            widget.step = step;
            widget.value = value;
            throw e;
        }
    }

    /** The longest label or unit a widget keeps (it has to fit a panel, and be sent to every player near). */
    private static final int MOST_TEXT = 200;

    private static String shortText(JsonElement value) {
        String text = printable(value.isJsonNull() ? "" : value.getAsString()).replace('\n', ' ');
        return text.length() > MOST_TEXT ? text.substring(0, MOST_TEXT) : text;
    }

    private void applyFields(Widget widget, JsonObject spec) {
        for (Map.Entry<String, JsonElement> entry : spec.entrySet()) {
            JsonElement value = entry.getValue();
            switch (entry.getKey()) {
                case "label" -> widget.label = shortText(value);
                case "x" -> widget.x = value.getAsInt();
                case "y" -> widget.y = value.getAsInt();
                case "w" -> widget.w = Math.max(1, value.getAsInt());
                case "h" -> widget.h = Math.max(1, value.getAsInt());
                case "color" -> widget.color = value.isJsonNull() ? -1 : color(value, -1);
                case "min" -> widget.min = value.getAsDouble();
                case "max" -> widget.max = value.getAsDouble();
                case "step" -> widget.step = Math.max(0, value.getAsDouble());
                case "unit" -> widget.unit = shortText(value);
                case "item" -> {
                    String id = value.isJsonNull() ? "" : value.getAsString().strip();
                    if (id.length() > 100) {
                        throw new IllegalArgumentException("That item name is too long.");
                    }
                    widget.item = id.isEmpty() || id.contains(":") ? id : "minecraft:" + id;
                }
                case "value" -> setValue(widget, value);
                case "type" -> {
                }
                default -> throw new IllegalArgumentException("A widget has no " + entry.getKey() + ".");
            }
        }
        if (widget.max < widget.min) {
            throw new IllegalArgumentException("A widget's max (" + widget.max + ") is below its min (" + widget.min + ").");
        }
        if (widget.type.equals("slider") || widget.type.equals("dial") || widget.type.equals("number")) {
            widget.value = snap(widget, widget.value);
        }
    }

    private void setValue(Widget widget, JsonElement value) {
        double number;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            number = value.getAsBoolean() ? 1 : 0;
        } else if (value.isJsonNull()) {
            number = 0;
        } else {
            number = value.getAsDouble();
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("A widget's value must be a number.");
        }
        if (widget.type.equals("graph")) {
            widget.history.add(number);
            if (widget.history.size() > GRAPH_POINTS) {
                widget.history.removeFirst();
            }
        }
        widget.value = widget.type.equals("toggle") || widget.type.equals("lamp") ? (number != 0 ? 1 : 0) : number;
    }

    /** A slider's value kept in its range, on its steps. */
    private static double snap(Widget widget, double value) {
        if (!(widget.min <= widget.max)) {
            return value; // never asked of a sound widget; a broken one must not stop the game
        }
        double clamped = Math.clamp(value, widget.min, widget.max);
        if (widget.step > 0) {
            clamped = widget.min + Math.round((clamped - widget.min) / widget.step) * widget.step;
            clamped = Math.clamp(clamped, widget.min, widget.max);
        }
        return clamped;
    }

    @Override
    public synchronized JsonElement op(String method, JsonArray args) {
        JsonElement answer = JsonNull.INSTANCE;
        switch (method) {
            case "size" -> {
                JsonArray size = new JsonArray();
                size.add(cols);
                size.add(rows);
                return size;
            }
            case "clear" -> widgets.clear();
            case "theme" -> {
                JsonElement name = arg(args, 0);
                if (name == null || name.isJsonNull()) {
                    return new JsonPrimitive(theme);
                }
                String wanted = name.getAsString().toLowerCase(java.util.Locale.ROOT);
                if (!THEMES.contains(wanted)) {
                    throw new IllegalArgumentException("There is no theme called " + name.getAsString() + ". The themes are: " + String.join(", ", THEMES) + ".");
                }
                theme = wanted;
            }
            case "add" -> {
                JsonElement specElement = arg(args, 0);
                if (specElement == null || !specElement.isJsonObject()) {
                    throw new IllegalArgumentException("add needs the widget's description.");
                }
                JsonObject spec = specElement.getAsJsonObject();
                String type = spec.has("type") ? spec.get("type").getAsString() : "";
                if (!TYPES.contains(type)) {
                    throw new IllegalArgumentException("There is no widget called " + type + ".");
                }
                if (widgets.size() >= MAX_WIDGETS) {
                    throw new IllegalArgumentException("A panel holds at most " + MAX_WIDGETS + " widgets; clear() it, or remove some.");
                }
                Widget widget = new Widget();
                widget.type = type;
                if (type.equals("toggle") || type.equals("lamp")) {
                    widget.max = 1;
                }
                if (type.equals("number")) {
                    widget.step = 1;
                }
                apply(widget, spec);
                widget.id = nextId++;
                widgets.put(widget.id, widget);
                answer = new JsonPrimitive(widget.id);
            }
            case "set" -> {
                Widget widget = widget(args);
                JsonElement spec = arg(args, 1);
                if (spec == null || !spec.isJsonObject()) {
                    throw new IllegalArgumentException("set needs what to change.");
                }
                apply(widget, spec.getAsJsonObject());
            }
            case "get" -> {
                return new JsonPrimitive(widget(args).value);
            }
            case "remove" -> widgets.remove(intArg(args, 0, "A widget's number"));
            case "clear_graph" -> widget(args).history.clear();
            default -> throw new IllegalArgumentException("A control panel has no " + method + "().");
        }
        version++;
        return answer;
    }

    /**
     * A player clicked the panel at a point given in grid cells (fractions allowed). Returns the
     * event for programs, or null if nothing there takes clicks.
     */
    public synchronized JsonObject click(double gx, double gy) {
        Widget hit = null;
        for (Widget widget : widgets.values()) {
            if (widget.contains(gx, gy)) {
                hit = widget; // the last one placed is on top
            }
        }
        if (hit == null) {
            return null;
        }
        JsonObject event = new JsonObject();
        event.addProperty("widget", hit.id);
        event.addProperty("label", hit.label);
        switch (hit.type) {
            case "button", "item" -> {
                hit.presses++;
                event.addProperty("event", "press");
            }
            case "dial" -> {
                // Round from bottom left (min) clockwise to bottom right (max): 270 degrees.
                double cx = hit.x + hit.w / 2.0;
                double cy = hit.y + hit.h / 2.0;
                double angle = Math.toDegrees(Math.atan2(-(gy - cy), gx - cx));
                double turned = 225 - angle;
                turned = ((turned % 360) + 360) % 360;
                if (turned > 270) {
                    turned = turned > 315 ? 0 : 270; // in the gap at the bottom: the nearer end
                }
                double before = hit.value;
                hit.value = snap(hit, hit.min + turned / 270 * (hit.max - hit.min));
                if (hit.value == before) {
                    return null;
                }
                event.addProperty("event", "change");
            }
            case "number" -> {
                // The left third takes a step off, the right third adds one.
                double along = (gx - hit.x) / hit.w;
                double step = hit.step > 0 ? hit.step : 1;
                double before = hit.value;
                if (along < 1 / 3.0) {
                    hit.value = snap(hit, hit.value - step);
                } else if (along > 2 / 3.0) {
                    hit.value = snap(hit, hit.value + step);
                }
                if (hit.value == before) {
                    return null;
                }
                event.addProperty("event", "change");
            }
            case "toggle" -> {
                hit.value = hit.value != 0 ? 0 : 1;
                event.addProperty("event", "change");
            }
            case "slider" -> {
                boolean upright = hit.h > hit.w;
                double along = upright ? 1 - (gy - hit.y) / hit.h : (gx - hit.x) / hit.w;
                double before = hit.value;
                hit.value = snap(hit, hit.min + Math.clamp(along, 0, 1) * (hit.max - hit.min));
                if (hit.value == before) {
                    return null;
                }
                event.addProperty("event", "change");
            }
            default -> {
                return null;
            }
        }
        event.addProperty("value", hit.value);
        version++;
        return event;
    }

    @Override
    public synchronized JsonObject save() {
        JsonObject saved = state();
        saved.addProperty("next_id", nextId);
        return saved;
    }

    @Override
    public synchronized void load(JsonObject saved) {
        widgets.clear();
        if (saved.has("widgets")) {
            for (JsonElement element : saved.getAsJsonArray("widgets")) {
                JsonObject json = element.getAsJsonObject();
                Widget widget = new Widget();
                widget.id = json.get("id").getAsInt();
                widget.type = json.get("type").getAsString();
                if (!TYPES.contains(widget.type)) {
                    continue;
                }
                widget.label = json.get("label").getAsString();
                widget.x = json.get("x").getAsInt();
                widget.y = json.get("y").getAsInt();
                widget.w = json.get("w").getAsInt();
                widget.h = json.get("h").getAsInt();
                widget.color = json.get("color").getAsInt();
                widget.min = json.get("min").getAsDouble();
                widget.max = json.get("max").getAsDouble();
                widget.step = json.get("step").getAsDouble();
                widget.value = json.get("value").getAsDouble();
                widget.unit = json.get("unit").getAsString();
                widget.item = json.has("item") ? json.get("item").getAsString() : "";
                if (json.has("history")) {
                    json.getAsJsonArray("history").forEach(point -> widget.history.add(point.getAsDouble()));
                }
                widgets.put(widget.id, widget);
            }
        }
        theme = saved.has("theme") && THEMES.contains(saved.get("theme").getAsString()) ? saved.get("theme").getAsString() : "dark";
        nextId = saved.has("next_id") ? saved.get("next_id").getAsInt() : widgets.keySet().stream().mapToInt(i -> i + 1).max().orElse(1);
        version++;
    }
}
