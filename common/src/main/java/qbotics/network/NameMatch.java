package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * What a program may write where an item or a fluid is named: one exact name, or a filter
 * that several things pass. The filter words follow the search box of the popular JEI mod,
 * which many players already know:
 *
 * <pre>
 *   minecraft:coal      that one thing ("coal" alone means the same)
 *   *_log               a name with wildcards: * is any run of characters, ? any one
 *   &#64;create             everything from a mod, by its ID or its name (part of either is enough)
 *   #minecraft:logs     everything carrying a tag ($ works too, as in JEI); "#logs" tries minecraft: then c:
 *   &#64;minecraft *_ore    several words: all must hold
 *   -*_slab             a word starting with - must NOT hold
 *   coal | charcoal     alternatives, separated by |
 * </pre>
 *
 * @param <T> items or fluids
 */
public final class NameMatch<T> implements Predicate<T> {
    /** A mistake in a filter, in words for the player. */
    public static final class BadFilter extends RuntimeException {
        BadFilter(String message) {
            super(message, null, false, false);
        }
    }

    private final Registry<T> registry;
    private final String text;
    /** Alternatives; each is a list of tests that must all hold. */
    private final List<List<Predicate<T>>> alternatives = new ArrayList<>();
    /** Set when the filter is one exact name. */
    private @Nullable T single;

    private NameMatch(Registry<T> registry, String text) {
        this.registry = registry;
        this.text = text;
    }

    /**
     * Reads a filter. It may name things that don't exist (yet): whether anything passes is
     * a question for {@link #first()}.
     *
     * @throws BadFilter if the text can't be understood at all
     */
    public static <T> NameMatch<T> parse(Registry<T> registry, String text) {
        String cleaned = text.strip();
        if (cleaned.isEmpty() || cleaned.length() > 200) {
            throw new BadFilter("A name or filter can't be empty (or longer than 200 characters).");
        }
        NameMatch<T> match = new NameMatch<>(registry, cleaned);
        for (String alternative : cleaned.split("\\|")) {
            List<Predicate<T>> all = new ArrayList<>();
            for (String word : alternative.strip().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                boolean not = word.startsWith("-") && word.length() > 1;
                Predicate<T> test = match.word(not ? word.substring(1) : word);
                all.add(not ? test.negate() : test);
            }
            if (all.isEmpty()) {
                throw new BadFilter("'" + cleaned + "' has an empty part beside a |.");
            }
            match.alternatives.add(all);
        }
        // One plain name: remember the thing itself, for the callers that want exactly one.
        boolean plain = !cleaned.matches(".*[\\s|*?@#$].*") && !cleaned.startsWith("-");
        if (plain) {
            Identifier id = id(cleaned);
            match.single = id != null && registry.containsKey(id) ? registry.getValue(id) : null;
        }
        return match;
    }

    private static @Nullable Identifier id(String name) {
        return Identifier.tryParse(name.toLowerCase(Locale.ROOT));
    }

    private Predicate<T> word(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            String mod = lower.substring(1);
            if (mod.isEmpty()) {
                throw new BadFilter("After @ comes a mod's ID or name, as in @minecraft.");
            }
            return thing -> {
                String namespace = key(thing).getNamespace();
                return namespace.contains(mod) || Qbotics.platform().modName(namespace).toLowerCase(Locale.ROOT).replace(" ", "").contains(mod);
            };
        }
        if (lower.startsWith("#") || lower.startsWith("$")) {
            String name = lower.substring(1);
            List<TagKey<T>> tags = new ArrayList<>();
            // "#logs" means the game's own tag, or failing that the one mods share.
            for (String full : name.contains(":") ? List.of(name) : List.of("minecraft:" + name, "c:" + name)) {
                Identifier tagId = Identifier.tryParse(full);
                if (tagId == null || name.isEmpty()) {
                    throw new BadFilter("'" + word + "' isn't a tag. Tags look like #minecraft:logs.");
                }
                tags.add(TagKey.create(registry.key(), tagId));
            }
            return thing -> {
                var holder = registry.wrapAsHolder(thing);
                return tags.stream().anyMatch(holder::is);
            };
        }
        if (lower.contains("*") || lower.contains("?")) {
            StringBuilder regex = new StringBuilder();
            for (char c : lower.toCharArray()) {
                regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
            }
            Pattern pattern = Pattern.compile(regex.toString());
            // With a colon the whole name is matched; without, the part after the colon, from any mod.
            boolean whole = lower.contains(":");
            return thing -> pattern.matcher(whole ? key(thing).toString() : key(thing).getPath()).matches();
        }
        Identifier id = id(lower);
        if (id == null) {
            throw new BadFilter("'" + word + "' isn't a name. Names look like minecraft:iron_ingot.");
        }
        return thing -> key(thing).equals(id);
    }

    private Identifier key(T thing) {
        Identifier key = registry.getKey(thing);
        return key == null ? Identifier.withDefaultNamespace("air") : key;
    }

    @Override
    public boolean test(T thing) {
        for (List<Predicate<T>> all : alternatives) {
            if (all.stream().allMatch(test -> test.test(thing))) {
                return true;
            }
        }
        return false;
    }

    /** The filter as it was written. */
    public String text() {
        return text;
    }

    /** The one thing this names, or null if it is a filter (or names nothing that exists). */
    public @Nullable T single() {
        return single;
    }

    /** The first thing in the game that passes (other than "nothing"), or null if none does. */
    public @Nullable T first() {
        if (single != null) {
            return single;
        }
        T empty = registry.getValue(Identifier.withDefaultNamespace(registry.key().identifier().getPath().equals("fluid") ? "empty" : "air"));
        for (T thing : registry) {
            if (thing != empty && test(thing)) {
                return thing;
            }
        }
        return null;
    }
}
