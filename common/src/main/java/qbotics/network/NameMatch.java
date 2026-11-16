package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
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
 * Items also have words about one stack's data (docs/filters.md): enchant=mending,
 * effect=strength, damaged, undamaged, enchanted, named; and the game's own item predicate
 * syntax for anything else, a word with [...] in it: potion[potion_contents={potion:"minecraft:strong_strength"}].
 * Those are only known for a real stack ({@link #testStack}); asked about a kind of item
 * alone ({@link #test}), they are passed over, as in "could this kind pass?". Blocks have
 * words about their state in the same way: "wheat age=7", "furnace lit=true" ({@link #testState}).
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

    /** The longest filter that may be written. */
    public static final int MOST_LETTERS = 4000;

    /** One word of a filter: a test of a thing and, for items, the stack it is (null when only its kind is known). */
    private record Word(Predicate<Subject> test, boolean data) {
    }

    private record Subject(Object thing, @Nullable ItemStack stack, net.minecraft.world.level.block.state.@Nullable BlockState state) {
    }

    private final Registry<T> registry;
    private final String text;
    /** Alternatives; each is a list of words that must all hold. */
    private final List<List<Word>> alternatives = new ArrayList<>();
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
        if (cleaned.isEmpty() || cleaned.length() > MOST_LETTERS) {
            throw new BadFilter("A name or filter can't be empty (or longer than " + MOST_LETTERS + " characters).");
        }
        NameMatch<T> match = new NameMatch<>(registry, cleaned);
        for (String alternative : split(cleaned, true)) {
            List<Word> all = new ArrayList<>();
            for (String word : split(alternative, false)) {
                boolean not = word.startsWith("-") && word.length() > 1;
                Word test = match.word(not ? word.substring(1) : word);
                all.add(not ? new Word(test.test().negate(), test.data()) : test);
            }
            if (all.isEmpty()) {
                throw new BadFilter("'" + cleaned + "' has an empty part beside a |.");
            }
            match.alternatives.add(all);
        }
        // One plain name: remember the thing itself, for the callers that want exactly one.
        boolean plain = !cleaned.matches(".*[\\s|*?@#$=\\[].*") && !cleaned.startsWith("-") && !DATA_WORDS.contains(cleaned.toLowerCase(Locale.ROOT));
        if (plain) {
            Identifier id = id(cleaned);
            match.single = id != null && registry.containsKey(id) ? registry.getValue(id) : null;
        }
        return match;
    }

    /** The words about a stack that stand alone. */
    private static final java.util.Set<String> DATA_WORDS = java.util.Set.of("damaged", "undamaged", "enchanted", "named");

    /**
     * Splits a filter at | (alternatives) or at spaces (words), but not inside [...], {...}
     * or quotes, which the game's own item syntax uses.
     */
    private static List<String> split(String text, boolean alternatives) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                part.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    part.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if ((c == ']' || c == '}') && depth > 0) {
                depth--;
            }
            boolean cut = depth == 0 && (alternatives ? c == '|' : Character.isWhitespace(c));
            if (cut) {
                if (alternatives || !part.isEmpty()) {
                    parts.add(alternatives ? part.toString().strip() : part.toString());
                }
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        if (alternatives || !part.isEmpty()) {
            parts.add(alternatives ? part.toString().strip() : part.toString());
        }
        return parts;
    }

    private static @Nullable Identifier id(String name) {
        return Identifier.tryParse(name.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private Word plainWord(Predicate<T> test) {
        return new Word(subject -> test.test((T) subject.thing()), false);
    }

    private boolean items() {
        return registry.key().identifier().getPath().equals("item");
    }

    private boolean blocks() {
        return registry.key().identifier().getPath().equals("block");
    }

    /** A word about one stack's data, or a block's state; null if it isn't one. */
    private @Nullable Word dataWord(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (blocks() && lower.indexOf('=') > 0) {
            // A block's state: "age=7", "lit=true", "facing=north". A block without that property doesn't pass.
            String property = lower.substring(0, lower.indexOf('='));
            String value = lower.substring(lower.indexOf('=') + 1);
            if (value.isEmpty()) {
                throw new BadFilter("Give a value after =, as in age=7.");
            }
            return new Word(subject -> subject.state() != null && stateHas(subject.state(), property, value), true);
        }
        Predicate<ItemStack> test = null;
        if (word.indexOf('[') > 0 || word.startsWith("[")) {
            test = gamePredicate(word);
        } else if (lower.startsWith("enchant=")) {
            Identifier wanted = id(defaultSpace(lower.substring("enchant=".length()), "enchant=mending"));
            test = stack -> enchantments(stack).keySet().stream().anyMatch(holder -> holder.unwrapKey().map(key -> key.identifier().equals(wanted)).orElse(false));
        } else if (lower.startsWith("effect=")) {
            Identifier wanted = id(defaultSpace(lower.substring("effect=".length()), "effect=strength"));
            test = stack -> {
                net.minecraft.world.item.alchemy.PotionContents potion = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
                if (potion == null) {
                    return false;
                }
                for (net.minecraft.world.effect.MobEffectInstance effect : potion.getAllEffects()) {
                    if (effect.getEffect().unwrapKey().map(key -> key.identifier().equals(wanted)).orElse(false)) {
                        return true;
                    }
                }
                return false;
            };
        } else if (lower.equals("damaged")) {
            test = ItemStack::isDamaged;
        } else if (lower.equals("undamaged")) {
            test = stack -> !stack.isDamaged();
        } else if (lower.equals("enchanted")) {
            test = stack -> !enchantments(stack).isEmpty();
        } else if (lower.equals("named")) {
            test = stack -> stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        }
        if (test == null) {
            return null;
        }
        if (!items()) {
            throw new BadFilter("'" + word + "' is about an item's data; fluids have none.");
        }
        Predicate<ItemStack> known = test;
        return new Word(subject -> subject.stack() != null && known.test(subject.stack()), true);
    }

    private static String defaultSpace(String name, String example) {
        if (name.isEmpty()) {
            throw new BadFilter("Give a name after =, as in " + example + ".");
        }
        return name.contains(":") ? name : "minecraft:" + name;
    }

    /** Its enchantments, worn or (on a book) stored. */
    private static net.minecraft.world.item.enchantment.ItemEnchantments enchantments(ItemStack stack) {
        net.minecraft.world.item.enchantment.ItemEnchantments worn = stack.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        net.minecraft.world.item.enchantment.ItemEnchantments stored = stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        if (stored.isEmpty()) {
            return worn;
        }
        if (worn.isEmpty()) {
            return stored;
        }
        net.minecraft.world.item.enchantment.ItemEnchantments.Mutable both = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(worn);
        stored.entrySet().forEach(entry -> both.upgrade(entry.getKey(), entry.getIntValue()));
        return both.toImmutable();
    }

    /** The game's own item predicate, as /clear and /execute if items read it. */
    private static Predicate<ItemStack> gamePredicate(String word) {
        net.minecraft.server.MinecraftServer server = Qbotics.server();
        if (server == null) {
            throw new BadFilter("'" + word + "' uses the game's item syntax, which needs the game running.");
        }
        try {
            net.minecraft.commands.CommandBuildContext context = net.minecraft.commands.CommandBuildContext.simple(server.registryAccess(),
                    server.getWorldData().enabledFeatures());
            com.mojang.brigadier.StringReader reader = new com.mojang.brigadier.StringReader(word);
            Predicate<ItemStack> test = new net.minecraft.commands.arguments.item.ItemPredicateArgument(context).parse(reader);
            if (reader.canRead()) {
                throw new BadFilter("'" + word + "' has something after the item: '" + reader.getRemaining() + "'.");
            }
            return test;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException wrong) {
            throw new BadFilter("'" + word + "' isn't the game's item syntax: " + wrong.getRawMessage().getString()
                    + ". It looks like potion[potion_contents={potion:\"minecraft:strength\"}].");
        }
    }

    private Word word(String word) {
        Word data = dataWord(word);
        if (data != null) {
            return data;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            String mod = lower.substring(1);
            if (mod.isEmpty()) {
                throw new BadFilter("After @ comes a mod's ID or name, as in @minecraft.");
            }
            return plainWord(thing -> {
                String namespace = key(thing).getNamespace();
                return namespace.contains(mod) || Qbotics.platform().modName(namespace).toLowerCase(Locale.ROOT).replace(" ", "").contains(mod);
            });
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
            return plainWord(thing -> {
                var holder = registry.wrapAsHolder(thing);
                return tags.stream().anyMatch(holder::is);
            });
        }
        if (lower.contains("*") || lower.contains("?")) {
            StringBuilder regex = new StringBuilder();
            for (char c : lower.toCharArray()) {
                regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
            }
            Pattern pattern = Pattern.compile(regex.toString());
            // With a colon the whole name is matched; without, the part after the colon, from any mod.
            boolean whole = lower.contains(":");
            return plainWord(thing -> pattern.matcher(whole ? key(thing).toString() : key(thing).getPath()).matches());
        }
        Identifier id = id(lower);
        if (id == null) {
            throw new BadFilter("'" + word + "' isn't a name. Names look like minecraft:iron_ingot.");
        }
        return plainWord(thing -> key(thing).equals(id));
    }

    private Identifier key(T thing) {
        Identifier key = registry.getKey(thing);
        return key == null ? Identifier.withDefaultNamespace("air") : key;
    }

    /**
     * Whether a kind of thing passes, its data unknown: words about a stack's data are passed
     * over. For a real item stack, use {@link #testStack}.
     */
    @Override
    public boolean test(T thing) {
        Subject subject = new Subject(thing, null, null);
        for (List<Word> all : alternatives) {
            if (all.stream().allMatch(word -> word.data() || word.test().test(subject))) {
                return true;
            }
        }
        return false;
    }

    /** Whether one item stack passes, its data and all. */
    public boolean testStack(ItemStack stack) {
        Subject subject = new Subject(stack.getItem(), stack, null);
        for (List<Word> all : alternatives) {
            if (all.stream().allMatch(word -> word.test().test(subject))) {
                return true;
            }
        }
        return false;
    }

    /** Whether one block, as it stands (its state and all), passes. */
    public boolean testState(net.minecraft.world.level.block.state.BlockState state) {
        Subject subject = new Subject(state.getBlock(), null, state);
        for (List<Word> all : alternatives) {
            if (all.stream().allMatch(word -> word.test().test(subject))) {
                return true;
            }
        }
        return false;
    }

    private static boolean stateHas(net.minecraft.world.level.block.state.BlockState state, String name, String value) {
        for (net.minecraft.world.level.block.state.properties.Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return valueName(state, property).equals(value);
            }
        }
        return false;
    }

    private static <V extends Comparable<V>> String valueName(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<V> property) {
        return property.getName(state.getValue(property));
    }

    /** True if some word is about a stack's data, so only {@link #testStack} gives the whole answer. */
    public boolean aboutData() {
        return alternatives.stream().anyMatch(all -> all.stream().anyMatch(Word::data));
    }

    /**
     * The real names closest to one that doesn't exist, best first: at most three, and only
     * ones close enough to be worth offering. "iron_ore_raw" finds raw_iron and iron_ore.
     */
    public static <T> List<String> closest(Registry<T> registry, String wrong) {
        String typed = wrong.toLowerCase(Locale.ROOT).strip();
        String path = typed.substring(typed.indexOf(':') + 1);
        List<String> words = List.of(path.split("_"));
        record Scored(String name, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Identifier id : registry.keySet()) {
            String other = id.getPath();
            // Two ways of being close: a few letters different, or the same words in another order or number.
            int letters = distance(path, other, 3);
            List<String> otherWords = List.of(other.split("_"));
            long shared = words.stream().filter(otherWords::contains).count();
            int wordScore = shared == 0 ? 99 : (int) (words.size() - shared + otherWords.size() - shared);
            int score = Math.min(letters, shared >= 2 || (shared == 1 && otherWords.size() == 1) ? wordScore : 99);
            if (score <= 2) {
                scored.add(new Scored(id.toString(), score * 100 + Math.abs(other.length() - path.length())));
            }
        }
        scored.sort(java.util.Comparator.comparingInt(Scored::score).thenComparing(Scored::name));
        return scored.stream().limit(3).map(Scored::name).toList();
    }

    /** How many single-letter changes turn one word into the other; anything above the limit comes back as limit + 1. */
    private static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int best = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int change = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(change, Math.min(previous[j], current[j - 1]) + 1);
                best = Math.min(best, current[j]);
            }
            if (best > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return Math.min(previous[b.length()], limit + 1);
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
