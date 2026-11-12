package qbotics.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Text fields on Web Panels. The layout engine draws no form controls, so before a page is
 * laid out each {@code <input>} becomes a box of text the game draws and edits itself: what
 * this player has typed there (each player types into their own copy), its placeholder, and a
 * caret when it has the keyboard. A button-like input becomes a {@code <button>}.
 */
public final class WebInputs {
    private WebInputs() {
    }

    /** The game's own CSS for the fields it makes. */
    public static final String CSS = ".qb-input { display: inline-block; min-width: 120px; min-height: 1.2em; padding: 2px 5px; "
            + "background: #1a2129; color: #e6e8ea; border: 1px solid #56606c; border-radius: 3px; white-space: pre; } "
            + ".qb-focus { border-color: #8cc8ff; } .qb-placeholder { color: #7a8490; } .qb-caret { color: #8cc8ff; }";

    /** The longest text a field takes. */
    public static final int MAX_LENGTH = 256;

    /** A field's name: its name, else its id, else "input". */
    static String fieldName(Map<String, String> attributes) {
        String name = attributes.getOrDefault("name", "");
        if (name.isEmpty()) {
            name = attributes.getOrDefault("id", "");
        }
        return name.isEmpty() ? "input" : name;
    }

    /**
     * The page with its inputs made into fields.
     *
     * @param typed   what this player has typed, by field name (a field not typed into shows its value attribute)
     * @param focused the field that has the keyboard, or null
     */
    public static String rewrite(String html, Map<String, String> typed, @Nullable String focused) {
        if (html.indexOf("<input") < 0 && html.indexOf("<INPUT") < 0) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + 128);
        int at = 0;
        String lower = html.toLowerCase(Locale.ROOT);
        while (true) {
            int start = lower.indexOf("<input", at);
            if (start < 0) {
                out.append(html, at, html.length());
                return out.toString();
            }
            int end = tagEnd(html, start);
            if (end < 0) {
                out.append(html, at, html.length());
                return out.toString();
            }
            out.append(html, at, start);
            at = end + 1;
            Map<String, String> attributes = attributes(html.substring(start + 6, end));
            String type = attributes.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
            String name = fieldName(attributes);
            String common = attribute("id", attributes.get("id")) + attribute("style", attributes.get("style"));
            switch (type) {
                case "hidden" -> {
                }
                case "button", "submit", "reset" -> {
                    String event = attributes.getOrDefault("data-event", name);
                    out.append("<button data-event=\"").append(WebTemplate.escape(event)).append('"')
                            .append(attribute("name", attributes.get("name"))).append(attribute("class", attributes.get("class"))).append(common)
                            .append('>').append(WebTemplate.escape(attributes.getOrDefault("value", "Submit"))).append("</button>");
                }
                default -> {
                    String value = typed.containsKey(name) ? typed.get(name) : attributes.getOrDefault("value", "");
                    boolean focus = name.equals(focused);
                    String event = attributes.getOrDefault("data-event", name);
                    String classes = "qb-input" + (focus ? " qb-focus" : "")
                            + (attributes.containsKey("class") ? " " + attributes.get("class") : "");
                    out.append("<span class=\"").append(WebTemplate.escape(classes)).append("\" data-input=\"")
                            .append(WebTemplate.escape(name)).append("\" data-event=\"").append(WebTemplate.escape(event)).append('"')
                            .append(attribute("name", name)).append(common).append('>');
                    if (value.isEmpty() && !focus) {
                        out.append("<span class=\"qb-placeholder\">").append(WebTemplate.escape(attributes.getOrDefault("placeholder", "")))
                                .append("</span>");
                    } else {
                        out.append(WebTemplate.escape("password".equals(type) ? "*".repeat(value.length()) : value));
                    }
                    if (focus) {
                        out.append("<span class=\"qb-caret\">|</span>");
                    }
                    out.append("</span>");
                }
            }
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
