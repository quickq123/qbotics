package qbotics.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Form controls on Web Panels. The layout engine draws none, so before a page is laid out each
 * control becomes something it can draw, which the game then works itself:
 * <ul>
 *   <li>{@code <input>} (text, search, number, password...): a box of text the player types in.</li>
 *   <li>{@code <textarea>}: the same, over several lines.</li>
 *   <li>{@code <input type="checkbox">} and {@code type="radio"}: a box (or circle) a click flips.</li>
 *   <li>{@code <select>}: a box showing the chosen option; a click opens the list.</li>
 *   <li>{@code <input type="button|submit|reset">}: a {@code <button>}.</li>
 * </ul>
 * What each player has typed or chosen is their own: {@code values}, by field name (a
 * checkbox's is "on" or "", a radio group's the chosen value). Each control is marked
 * {@code data-input="kind:name"} (kind: text, area, check, radio, select) so a click on it can
 * tell what it is.
 */
public final class WebInputs {
    private WebInputs() {
    }

    /** The game's own CSS for the controls it makes. */
    public static final String CSS = ".qb-input { display: inline-block; min-width: 120px; min-height: 1.2em; padding: 2px 5px; "
            + "background: #1a2129; color: #e6e8ea; border: 1px solid #56606c; border-radius: 3px; white-space: pre; } "
            + ".qb-area { white-space: pre-wrap; vertical-align: top; } "
            + ".qb-focus { border-color: #8cc8ff; } .qb-placeholder { color: #7a8490; } .qb-caret { color: #8cc8ff; } "
            + ".qb-check { display: inline-block; width: 12px; height: 12px; border: 1px solid #8a96a3; background: #1a2129; "
            + "border-radius: 2px; vertical-align: middle; margin: 0 4px 0 0; } .qb-radio { border-radius: 7px; } "
            + ".qb-on { background: #4a9eff; border-color: #8cc8ff; } "
            + ".qb-select { display: inline-block; min-width: 100px; padding: 2px 5px; background: #1a2129; color: #e6e8ea; "
            + "border: 1px solid #56606c; border-radius: 3px; } .qb-arrow { color: #8a96a3; }";

    /** The longest text a field takes. */
    public static final int MAX_LENGTH = 256;
    public static final int MAX_AREA_LENGTH = 2000;

    /** One option of a select: what the program hears, and what the player sees. */
    public record Option(String value, String label) {
    }

    /** A page with its controls made drawable, and the options of each select, by name. */
    public record Result(String html, Map<String, List<Option>> selects) {
    }

    /** A field's name: its name, else its id, else "input". */
    static String fieldName(Map<String, String> attributes) {
        String name = attributes.getOrDefault("name", "");
        if (name.isEmpty()) {
            name = attributes.getOrDefault("id", "");
        }
        return name.isEmpty() ? "input" : name;
    }

    /** The page with its inputs made drawable (for callers that don't need the selects' options). */
    public static String rewrite(String html, Map<String, String> values, @Nullable String focused) {
        return rewriteAll(html, values, focused).html();
    }

    /**
     * @param values  what this player has typed or chosen, by field name
     * @param focused the text field with the keyboard (its name), or null
     */
    public static Result rewriteAll(String html, Map<String, String> values, @Nullable String focused) {
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.indexOf("<input") < 0 && lower.indexOf("<textarea") < 0 && lower.indexOf("<select") < 0) {
            return new Result(html, Map.of());
        }
        Map<String, List<Option>> selects = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(html.length() + 128);
        int at = 0;
        while (true) {
            int start = next(lower, at);
            if (start < 0) {
                out.append(html, at, html.length());
                return new Result(out.toString(), selects);
            }
            int end = tagEnd(html, start);
            if (end < 0) {
                out.append(html, at, html.length());
                return new Result(out.toString(), selects);
            }
            out.append(html, at, start);
            String tag = lower.startsWith("<input", start) ? "input" : lower.startsWith("<textarea", start) ? "textarea" : "select";
            Map<String, String> attributes = attributes(html.substring(start + 1 + tag.length(), end));
            at = end + 1;
            String name = fieldName(attributes);
            String common = attribute("id", attributes.get("id")) + attribute("style", attributes.get("style"))
                    + attribute("data-qbn", attributes.get("data-qbn")) + attribute("title", attributes.get("title"));
            String event = attributes.getOrDefault("data-event", name);
            String extraClass = attributes.containsKey("class") ? " " + attributes.get("class") : "";
            switch (tag) {
                case "textarea" -> {
                    int close = lower.indexOf("</textarea", at);
                    String inside = close < 0 ? "" : decode(html.substring(at, close));
                    int closeEnd = close < 0 ? -1 : html.indexOf('>', close);
                    at = closeEnd < 0 ? html.length() : closeEnd + 1;
                    String value = values.containsKey(name) ? values.get(name) : inside;
                    int rows = number(attributes.get("rows"), 3);
                    int cols = number(attributes.get("cols"), 30);
                    out.append(textBox("area", name, event, common, extraClass + " qb-area",
                            "min-height: " + (rows * 1.25f) + "em; min-width: " + (cols * 0.55f) + "em; ",
                            value, attributes.getOrDefault("placeholder", ""), name.equals(focused), false));
                }
                case "select" -> {
                    int close = lower.indexOf("</select", at);
                    String inside = close < 0 ? "" : html.substring(at, close);
                    int closeEnd = close < 0 ? -1 : html.indexOf('>', close);
                    at = closeEnd < 0 ? html.length() : closeEnd + 1;
                    List<Option> options = new ArrayList<>();
                    String selected = null;
                    String innerLower = inside.toLowerCase(Locale.ROOT);
                    int o = 0;
                    while ((o = innerLower.indexOf("<option", o)) >= 0) {
                        int oEnd = tagEnd(inside, o);
                        if (oEnd < 0) {
                            break;
                        }
                        Map<String, String> optionAttributes = attributes(inside.substring(o + 7, oEnd));
                        int next = innerLower.indexOf('<', oEnd + 1);
                        String label = decode(inside.substring(oEnd + 1, next < 0 ? inside.length() : next)).strip();
                        String value = optionAttributes.getOrDefault("value", label);
                        options.add(new Option(value, label));
                        if (optionAttributes.containsKey("selected") && selected == null) {
                            selected = value;
                        }
                        o = oEnd;
                    }
                    selects.put(name, options);
                    String chosen = values.containsKey(name) ? values.get(name)
                            : selected != null ? selected : options.isEmpty() ? "" : options.getFirst().value();
                    String label = options.stream().filter(opt -> opt.value().equals(chosen)).map(Option::label).findFirst().orElse(chosen);
                    out.append("<span class=\"qb-select").append(WebTemplate.escape(extraClass)).append("\" data-input=\"select:")
                            .append(WebTemplate.escape(name)).append("\" data-event=\"").append(WebTemplate.escape(event)).append('"')
                            .append(attribute("name", name)).append(attribute("value", chosen)).append(common).append('>')
                            .append(WebTemplate.escape(label)).append(" <span class=\"qb-arrow\">&#9662;</span></span>");
                }
                default -> {
                    String type = attributes.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
                    switch (type) {
                        case "hidden" -> {
                        }
                        case "button", "submit", "reset" -> out.append("<button data-event=\"").append(WebTemplate.escape(event)).append('"')
                                .append(attribute("name", attributes.get("name"))).append(attribute("class", attributes.get("class"))).append(common)
                                .append('>').append(WebTemplate.escape(attributes.getOrDefault("value", "Submit"))).append("</button>");
                        case "checkbox", "radio" -> {
                            boolean radio = type.equals("radio");
                            String own = attributes.getOrDefault("value", "on");
                            boolean on;
                            if (values.containsKey(name)) {
                                on = radio ? values.get(name).equals(own) : !values.get(name).isEmpty();
                            } else {
                                on = attributes.containsKey("checked");
                            }
                            out.append("<span class=\"qb-check").append(radio ? " qb-radio" : "").append(on ? " qb-on" : "")
                                    .append(WebTemplate.escape(extraClass)).append("\" data-input=\"").append(radio ? "radio:" : on ? "check-on:" : "check:")
                                    .append(WebTemplate.escape(name)).append("\" data-event=\"").append(WebTemplate.escape(event)).append('"')
                                    .append(attribute("name", name)).append(attribute("value", own)).append(common).append("></span>");
                        }
                        default -> {
                            String value = values.containsKey(name) ? values.get(name) : attributes.getOrDefault("value", "");
                            out.append(textBox("text", name, event, common, extraClass, "", value, attributes.getOrDefault("placeholder", ""),
                                    name.equals(focused), type.equals("password")));
                        }
                    }
                }
            }
        }
    }

    private static String textBox(String kind, String name, String event, String common, String extraClass, String style, String value,
            String placeholder, boolean focus, boolean password) {
        StringBuilder out = new StringBuilder();
        String classes = "qb-input" + (focus ? " qb-focus" : "") + extraClass;
        out.append("<span class=\"").append(WebTemplate.escape(classes)).append("\" data-input=\"").append(kind).append(':')
                .append(WebTemplate.escape(name)).append("\" data-event=\"").append(WebTemplate.escape(event)).append('"')
                .append(attribute("name", name)).append(common);
        if (!style.isEmpty()) {
            // The control's own size goes before the page's style for it, so the page can change it.
            int at = out.indexOf(" style=\"");
            if (at >= 0) {
                out.insert(at + 8, WebTemplate.escape(style));
            } else {
                out.append(" style=\"").append(WebTemplate.escape(style)).append('"');
            }
        }
        out.append('>');
        if (value.isEmpty() && !focus) {
            out.append("<span class=\"qb-placeholder\">").append(WebTemplate.escape(placeholder)).append("</span>");
        } else {
            out.append(WebTemplate.escape(password ? "*".repeat(value.length()) : value));
        }
        if (focus) {
            out.append("<span class=\"qb-caret\">|</span>");
        }
        out.append("</span>");
        return out.toString();
    }

    private static int next(String lower, int from) {
        int best = -1;
        for (String tag : new String[] {"<input", "<textarea", "<select"}) {
            int at = lower.indexOf(tag, from);
            while (at >= 0) {
                char after = at + tag.length() < lower.length() ? lower.charAt(at + tag.length()) : '>';
                if (Character.isWhitespace(after) || after == '>' || after == '/') {
                    break;
                }
                at = lower.indexOf(tag, at + 1);
            }
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static int number(@Nullable String text, int fallback) {
        try {
            return text == null ? fallback : Math.clamp(Integer.parseInt(text.strip()), 1, 200);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String attribute(String name, @Nullable String value) {
        return value == null ? "" : " " + name + "=\"" + WebTemplate.escape(value) + "\"";
    }

    /** The '>' that ends a tag, skipping any inside quoted attribute values. */
    private static int tagEnd(String html, int from) {
        char quote = 0;
        for (int i = from; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** A tag's attributes, names in lower case; HTML entities in values are decoded. */
    static Map<String, String> attributes(String inside) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = inside.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(inside.charAt(i)) || inside.charAt(i) == '/')) {
                i++;
            }
            int nameStart = i;
            while (i < n && !Character.isWhitespace(inside.charAt(i)) && inside.charAt(i) != '=' && inside.charAt(i) != '/') {
                i++;
            }
            if (i == nameStart) {
                break;
            }
            String name = inside.substring(nameStart, i).toLowerCase(Locale.ROOT);
            while (i < n && Character.isWhitespace(inside.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < n && inside.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(inside.charAt(i))) {
                    i++;
                }
                if (i < n && (inside.charAt(i) == '"' || inside.charAt(i) == '\'')) {
                    char quote = inside.charAt(i++);
                    int valueStart = i;
                    while (i < n && inside.charAt(i) != quote) {
                        i++;
                    }
                    value = inside.substring(valueStart, i);
                    i++;
                } else {
                    int valueStart = i;
                    while (i < n && !Character.isWhitespace(inside.charAt(i))) {
                        i++;
                    }
                    value = inside.substring(valueStart, i);
                }
            }
            out.put(name, decode(value));
        }
        return out;
    }

    private static String decode(String value) {
        return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
