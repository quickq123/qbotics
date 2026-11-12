package qbotics.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Web Panel's state in its page (design doc §10.3), in the style of Mustache templates:
 * <ul>
 *   <li>{{name}}: the value, as text (HTML in it is shown, not used); {{{name}}}: as HTML.
 *       A name reaches inside: {{ores.iron}}, {{items.0}}; {{.}} is the item in a loop.</li>
 *   <li>{{#name}}...{{/name}}: for a list, the inside once per item, whose fields are then
 *       names of their own (the state's names still work); for anything else true, once.</li>
 *   <li>{{^name}}...{{/name}}: the inside only when the value is missing, false, 0, "" or an
 *       empty list ("nothing found").</li>
 * </ul>
 */
public final class WebTemplate {
    private WebTemplate() {
    }

    /** Pages may loop over a long list: the filled page stops growing at this size. */
    private static final int MAX_OUTPUT = 512 * 1024;

    public static String fill(String html, String stateJson) {
        if (html.indexOf("{{") < 0) {
            return html;
        }
        JsonObject state;
        try {
            state = JsonParser.parseString(stateJson).getAsJsonObject();
        } catch (RuntimeException e) {
            state = new JsonObject();
        }
        List<JsonElement> scopes = new ArrayList<>();
        scopes.add(state);
        StringBuilder out = new StringBuilder(html.length() + 64);
        render(html, scopes, out);
        return out.toString();
    }

    private static void render(String html, List<JsonElement> scopes, StringBuilder out) {
        int at = 0;
        while (out.length() < MAX_OUTPUT) {
            int open = html.indexOf("{{", at);
            if (open < 0) {
                out.append(html, at, html.length());
                return;
            }
            out.append(html, at, open);
            boolean raw = html.startsWith("{{{", open);
            String close = raw ? "}}}" : "}}";
            int end = html.indexOf(close, open + close.length());
            if (end < 0) {
                out.append(html, open, html.length());
                return;
            }
            String tag = html.substring(open + close.length(), end).strip();
            at = end + close.length();
            if (!raw && (tag.startsWith("#") || tag.startsWith("^"))) {
                String name = tag.substring(1).strip();
                int[] section = findClose(html, at, name);
                if (section == null) {
                    continue; // no {{/name}}: the tag is dropped
                }
                String inside = html.substring(at, section[0]);
                at = section[1];
                JsonElement value = lookup(scopes, name);
                if (tag.startsWith("^")) {
                    if (!truthy(value)) {
                        render(inside, scopes, out);
                    }
                } else if (value != null && value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        scopes.add(item);
                        render(inside, scopes, out);
                        scopes.removeLast();
                        if (out.length() >= MAX_OUTPUT) {
                            return;
                        }
                    }
                } else if (value != null && value.isJsonObject()) {
                    scopes.add(value);
                    render(inside, scopes, out);
                    scopes.removeLast();
                } else if (truthy(value)) {
                    render(inside, scopes, out);
                }
                continue;
            }
            if (tag.startsWith("/")) {
                continue; // a stray close
            }
            String value = text(lookup(scopes, tag));
            out.append(raw ? value : escape(value));
        }
    }

    /** Where the {{/name}} closing a section opened just before {@code from} is: [its start, the end after it]. */
    private static int @Nullable [] findClose(String html, int from, String name) {
        int depth = 1;
        int at = from;
        while (true) {
            int open = html.indexOf("{{", at);
            if (open < 0) {
                return null;
            }
            int end = html.indexOf("}}", open + 2);
            if (end < 0) {
                return null;
            }
            String tag = html.substring(open + 2, end).strip();
            if ((tag.startsWith("#") || tag.startsWith("^")) && tag.substring(1).strip().equals(name)) {
                depth++;
            } else if (tag.startsWith("/") && tag.substring(1).strip().equals(name)) {
                if (--depth == 0) {
                    return new int[] {open, end + 2};
                }
            }
            at = end + 2;
        }
    }

    private static boolean truthy(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonArray()) {
            return !value.getAsJsonArray().isEmpty();
        }
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsDouble() != 0;
            }
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    /** A name, looked for in the innermost scope first (a loop's item), then outwards. */
    private static @Nullable JsonElement lookup(List<JsonElement> scopes, String name) {
        if (name.equals(".")) {
            return scopes.getLast();
        }
        String first = name.split("\\.", 2)[0];
        for (int i = scopes.size() - 1; i >= 0; i--) {
            JsonElement scope = scopes.get(i);
            if (scope.isJsonObject() && scope.getAsJsonObject().has(first)) {
                return walk(scope, name);
            }
        }
        return null;
    }

    private static @Nullable JsonElement walk(JsonElement from, String name) {
        JsonElement at = from;
        for (String part : name.split("\\.")) {
            if (at == null || at.isJsonNull()) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(part);
            } else if (at.isJsonArray()) {
                JsonArray list = at.getAsJsonArray();
                try {
                    int index = Integer.parseInt(part);
                    at = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return at;
    }

    private static String text(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : p.getAsString();
            }
            return p.getAsString();
        }
        return value.toString();
    }

    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
