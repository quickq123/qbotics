package qbotics.client.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import qbotics.Qbotics;
import qbotics.agent.ModDocs;

/**
 * What the AI helper can learn about the other mods in the pack, from the player's own game:
 * the mods loaded ({@link #mods}), their key bindings as this player has them ({@link #controls}),
 * and the text the mods ship to explain themselves ({@link #docs}): Patchouli guidebook entries,
 * and the descriptive lines of their language files (see {@link ModDocs}). Best effort: a mod
 * that explains itself some other way (its own book screen, a wiki) isn't seen.
 */
public final class GameDocs {
    private GameDocs() {
    }

    // ---- the mods ---------------------------------------------------------------------------

    /** The loaded mods, or those matching a word: name, ID, version, and (when searching) what each says it is. */
    public static String mods(String query) {
        List<String[]> all = Qbotics.platform().loadedMods();
        String[] words = ModDocs.words(query);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String[] mod : all) {
            String haystack = (mod[0] + " " + mod[1] + " " + mod[3]).toLowerCase(Locale.ROOT);
            if (words.length > 0 && !ModDocs.matchesAny(haystack, words)) {
                continue;
            }
            if (++shown > 150) {
                out.append("... and more: search with a word.\n");
                break;
            }
            out.append(mod[1]).append(" (").append(mod[0]).append(") ").append(mod[2]);
            if (words.length > 0 && !mod[3].isBlank()) {
                String about = mod[3].strip().replaceAll("\\s+", " ");
                out.append(": ").append(about.length() > 240 ? about.substring(0, 240) + "..." : about);
            }
            out.append('\n');
        }
        if (shown == 0) {
            return "No loaded mod matches \"" + query + "\". " + all.size() + " mods are loaded; call mods with no query to list them.";
        }
        return (words.length == 0 ? all.size() + " mods loaded:\n" : "") + out;
    }

    // ---- key bindings -----------------------------------------------------------------------

    /** The player's key bindings (as they have set them), those matching a word, grouped by category. */
    public static String controls(String query) throws Exception {
        String[] words = ModDocs.words(query);
        java.util.function.Supplier<String> read = () -> {
            Map<String, StringBuilder> byCategory = new java.util.LinkedHashMap<>();
            int shown = 0;
            for (KeyMapping key : Minecraft.getInstance().options.keyMappings) {
                String category = key.getCategory().label().getString();
                String name = I18n.get(key.getName());
                String bound = key.isUnbound() ? "not bound" : key.getTranslatedKeyMessage().getString();
                String haystack = (category + " " + name + " " + key.getName()).toLowerCase(Locale.ROOT);
                if (words.length > 0 && !ModDocs.matchesAny(haystack, words)) {
                    continue;
                }
                shown++;
                byCategory.computeIfAbsent(category, c -> new StringBuilder()).append("  ").append(name).append(": ").append(bound).append('\n');
            }
            if (shown == 0) {
                return "No key binding matches \"" + query + "\". Call controls with no query to list them all, or with the mod's name.";
            }
            StringBuilder out = new StringBuilder("The player's keys (Options > Controls > Key Binds changes them):\n");
            byCategory.forEach((category, lines) -> out.append(category).append(":\n").append(lines));
            return out.length() > 6000 ? out.substring(0, 6000) + "\n... (search with a word for the rest)" : out.toString();
        };
        // The key bindings belong to the game's thread: read there (directly, if this is it).
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.isSameThread() ? read.get() : minecraft.submit(read).get(5, TimeUnit.SECONDS);
    }

    // ---- what the mods say about themselves -------------------------------------------------

    private static List<ModDocs.Doc> index;
    private static String indexedLanguage;

    /** Searches the mods' own guidebooks and descriptive text for the words asked. */
    public static synchronized String docs(String query) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (index == null || !language.equals(indexedLanguage)) {
            index = build(Minecraft.getInstance().getResourceManager(), language);
            indexedLanguage = language;
        }
        return ModDocs.search(index, query);
    }

    /** Every language line worth reading, and every Patchouli guidebook entry, from the loaded resources. */
    static List<ModDocs.Doc> build(ResourceManager resources, String language) {
        Map<String, String> lang = new HashMap<>();
        Map<String, String> owner = new HashMap<>();
        for (String namespace : resources.getNamespaces()) {
            for (String code : language.equals("en_us") ? List.of("en_us") : List.of("en_us", language)) {
                for (Resource resource : resources.getResourceStack(Identifier.fromNamespaceAndPath(namespace, "lang/" + code + ".json"))) {
                    try (Reader in = resource.openAsReader()) {
                        JsonElement parsed = JsonParser.parseReader(in);
                        if (!parsed.isJsonObject()) {
                            continue;
                        }
                        for (var entry : parsed.getAsJsonObject().entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                lang.put(entry.getKey(), entry.getValue().getAsString());
                                owner.put(entry.getKey(), namespace);
                            }
                        }
                    } catch (Exception e) {
                        // A broken language file: skipped.
                    }
                }
            }
        }
        List<ModDocs.Doc> docs = new ArrayList<>(ModDocs.fromLanguage(lang, owner, Qbotics.platform()::modName));
        // Patchouli guidebooks: assets/<mod>/patchouli_books/<book>/<language>/entries/**.json, in the
        // player's language where the book has it, else English.
        Map<Identifier, Resource> entries = resources.listResources("patchouli_books",
                id -> id.getPath().endsWith(".json") && id.getPath().contains("/entries/"));
        Map<String, Identifier> chosen = new HashMap<>();
        for (Identifier id : entries.keySet()) {
            String[] parts = id.getPath().split("/");
            if (parts.length < 5) {
                continue;
            }
            String code = parts[2];
            String key = id.getNamespace() + ":" + parts[1] + ":" + String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
            if (code.equals(language) || (code.equals("en_us") && !chosen.containsKey(key))) {
                chosen.put(key, id);
            }
        }
        for (Identifier id : chosen.values()) {
            try (Reader in = entries.get(id).openAsReader()) {
                StringBuilder json = new StringBuilder();
                char[] buffer = new char[8192];
                for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                    json.append(buffer, 0, n);
                }
                ModDocs.Doc doc = ModDocs.fromPatchouli(Qbotics.platform().modName(id.getNamespace()), id.getPath(), json.toString(), lang);
                if (doc != null) {
                    docs.add(doc);
                }
            } catch (Exception e) {
                // An entry that isn't plain JSON: skipped.
            }
        }
        return docs;
    }
}
