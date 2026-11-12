package qbotics.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/** A Web Panel's state in its page: {{name}} becomes the value (design doc §10.3). */
public final class WebTemplate {
    private WebTemplate() {
    }

    /**
     * Fills a page's {{name}} with the state's values, made safe as text, and {{{name}}} with
     * them as they are (HTML). A name may reach inside: {{ores.iron}}, {{items.0}}.
     */
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
        StringBuilder out = new StringBuilder(html.length() + 64);
        int at = 0;
        while (true) {
            int open = html.indexOf("{{", at);
            if (open < 0) {
                out.append(html, at, html.length());
                return out.toString();
            }
            boolean raw = html.startsWith("{{{", open);
            String close = raw ? "}}}" : "}}";
            int end = html.indexOf(close, open + close.length());
            if (end < 0) {
                out.append(html, at, html.length());
                return out.toString();
            }
            out.append(html, at, open);
            String name = html.substring(open + close.length(), end).strip();
            String value = text(lookup(state, name));
            out.append(raw ? value : escape(value));
            at = end + close.length();
        }
    }

    private static @Nullable JsonElement lookup(JsonObject state, String name) {
        JsonElement at = state;
        for (String part : name.split("\\.")) {
            if (at == null || at.isJsonNull()) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(part);
            } else if (at.isJsonArray()) {
                try {
                    int index = Integer.parseInt(part);
                    at = index >= 0 && index < at.getAsJsonArray().size() ? at.getAsJsonArray().get(index) : null;
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

    private static String escape(String text) {
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
