package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The text mods ship to explain themselves, made searchable for the AI helper's mod_docs tool:
 * the descriptive lines of their language files (tooltips, JEI information pages, hints) and
 * their Patchouli guidebook entries. The player's game reads the files (client.agent.GameDocs);
 * this turns them into entries and searches them, so it can be tested without a game client.
 */
public final class ModDocs {
    private ModDocs() {
    }

    /** One piece of text: a guidebook entry ("guide") or a language line ("text"), its mod and title. */
    public record Doc(String mod, String title, String text, String kind) {
    }

    /**
     * The language lines worth reading: tooltips, information pages, hints, descriptions, and any
     * line long enough to explain something. Item and block names alone are left out.
     *
     * @param lang    every language key and its text
     * @param owner   which mod (namespace) each key came from
     * @param modName a namespace's mod name
     */
    public static List<Doc> fromLanguage(Map<String, String> lang, Map<String, String> owner, Function<String, String> modName) {
        List<Doc> docs = new ArrayList<>();
        for (var entry : lang.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String text = clean(entry.getValue());
            boolean explains = key.contains("tooltip") || key.contains(".desc") || key.contains("info") || key.contains("jei")
                    || key.contains("hint") || key.contains("guide") || key.contains("help") || key.contains("tip.")
                    || key.contains("lore") || text.length() >= 70;
            if (explains && text.length() >= 12 && !key.startsWith("advancements.") && !key.startsWith("death.")) {
                String namespace = owner.get(entry.getKey());
                docs.add(new Doc(namespace == null ? "?" : modName.apply(namespace), title(entry.getKey(), lang), text, "text"));
            }
        }
        return docs;
    }

    /**
     * A Patchouli guidebook entry (its JSON, from assets/mod/patchouli_books/book/language/entries/),
     * with its name and pages' titles and text, translated where the book uses language keys,
     * formatting codes taken out, and the items its pages show named. Null if it has no text.
     */
    public static @Nullable Doc fromPatchouli(String mod, String path, String json, Map<String, String> lang) {
        JsonObject entry = JsonParser.parseString(json).getAsJsonObject();
        String name = clean(translate(entry.has("name") ? entry.get("name").getAsString() : path, lang));
        StringBuilder text = new StringBuilder();
        if (entry.has("pages") && entry.get("pages").isJsonArray()) {
            for (JsonElement page : entry.getAsJsonArray("pages")) {
                if (!page.isJsonObject()) {
                    continue;
                }
                JsonObject p = page.getAsJsonObject();
                for (String field : new String[] {"title", "text"}) {
                    if (p.has(field) && p.get(field).isJsonPrimitive()) {
                        text.append(clean(translate(p.get(field).getAsString(), lang))).append('\n');
                    }
                }
                for (String field : new String[] {"recipe", "recipe2", "item"}) {
                    if (p.has(field) && p.get(field).isJsonPrimitive()) {
                        text.append("(shows ").append(p.get(field).getAsString()).append(")\n");
                    }
                }
                if (p.has("items") && p.get("items").isJsonArray()) {
                    JsonArray items = p.getAsJsonArray("items");
                    for (JsonElement item : items) {
                        if (item.isJsonPrimitive()) {
                            text.append("(shows ").append(item.getAsString()).append(")\n");
                        }
                    }
                }
            }
        }
        return text.isEmpty() ? null : new Doc(mod, name, text.toString().strip(), "guide");
    }

    /** The entries best matching the words asked, most first: up to six, with their mod and title. */
    public static String search(List<Doc> index, String query) {
        String[] words = words(query);
        if (words.length == 0) {
            return "Say what to look for, e.g. \"jetpack fuel\" or \"bee breeding\".";
        }
        List<Map.Entry<Integer, Doc>> scored = new ArrayList<>();
        for (Doc doc : index) {
            String title = (doc.mod() + " " + doc.title()).toLowerCase(Locale.ROOT);
            String text = doc.text().toLowerCase(Locale.ROOT);
            int score = 0;
            int matched = 0;
            for (String word : words) {
                int here = (title.contains(word) ? 6 : 0) + Math.min(count(text, word), 8);
                score += here;
                matched += here > 0 ? 1 : 0;
            }
            if (score > 0) {
                // Entries with more of the words first; guidebook pages before single lines.
                scored.add(Map.entry(score + matched * 20 + (doc.kind().equals("guide") ? 5 : 0), doc));
            }
        }
        if (scored.isEmpty()) {
            return "The mods' guidebooks and tooltips say nothing about \"" + query + "\" (" + index.size()
                    + " pieces searched). If you answer from what you know, say it may differ in this version.";
        }
        scored.sort((a, b) -> b.getKey() - a.getKey());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(6, scored.size()) && out.length() < 5000; i++) {
            Doc doc = scored.get(i).getValue();
            String text = doc.text().length() > 900 ? doc.text().substring(0, 900) + "..." : doc.text();
            out.append("[").append(doc.mod()).append(", ").append(doc.kind().equals("guide") ? "guidebook: " : "text: ")
                    .append(doc.title()).append("]\n").append(text).append("\n\n");
        }
        return out.toString();
    }

    /** A language line's title: the item or block it belongs to, if the key says, else the key itself. */
    private static String title(String key, Map<String, String> lang) {
        String[] parts = key.split("\\.");
        // item.mod.thing.tooltip: the item's own name, from a shorter key.
        for (int end = parts.length - 1; end >= 2; end--) {
            String named = lang.get(String.join(".", Arrays.copyOfRange(parts, 0, end)));
            if (named != null && named.length() < 60) {
                return clean(named);
            }
        }
        // tooltip.mod.thing (and info., desc., jei.): the item or block called mod.thing, if there is one.
        if (parts.length >= 3) {
            String rest = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));
            for (String kind : new String[] {"item.", "block.", "entity."}) {
                for (int end = parts.length; end >= 3; end--) {
                    String named = lang.get(kind + String.join(".", Arrays.copyOfRange(parts, 1, end)));
                    if (named != null && named.length() < 60) {
                        return clean(named);
                    }
                }
            }
            // Otherwise the key's last words, readable: "message.qbotics.probe_nothing" is "probe nothing".
            return rest.substring(rest.indexOf('.') + 1).replace('.', ' ').replace('_', ' ');
        }
        return key;
    }

    private static String translate(String text, Map<String, String> lang) {
        return lang.getOrDefault(text, text);
    }

    /** Patchouli's and Minecraft's formatting codes taken out: $(item)...$(), $(l:...), §a. */
    public static String clean(String text) {
        return text.replace("$(br2)", "\n\n").replace("$(br)", "\n").replace("$(p)", "\n")
                .replaceAll("\\$\\([^)]*\\)", "").replaceAll("§.", "").replace("%s", "...").strip();
    }

    /** Words too common to search by: "how do I turn on my jetpack" searches for "jetpack". */
    private static final Set<String> COMMON = Set.of("a", "an", "the", "my", "me", "on", "off", "how", "do",
            "does", "to", "is", "it", "its", "what", "of", "in", "for", "with", "use", "using", "turn", "can", "where", "and", "or",
            "you", "your", "this", "that", "get", "make", "work", "works", "which", "key", "keys", "button");

    /** The words of a query worth searching by, lower case. */
    public static String[] words(String query) {
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"))
                .filter(w -> w.length() >= 2 && !COMMON.contains(w)).toArray(String[]::new);
    }

    /** True if any of the words is in the text. */
    public static boolean matchesAny(String haystack, String[] words) {
        for (String word : words) {
            if (haystack.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static int count(String text, String word) {
        int n = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            n++;
        }
        return n;
    }
}
