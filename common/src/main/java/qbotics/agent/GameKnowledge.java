package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import qbotics.network.NameMatch;

/**
 * What the game itself knows about items and recipes (design doc §17.8): every item, tag and
 * recipe loaded in this world, from every mod, as the server has them. The same answers go to
 * the AI agent (its find_items, item_info and recipes tools), the shell (recipe, item, items)
 * and programs (the game module), so they always agree, and fit this modpack, its changes
 * included, rather than what anyone remembers of plain Minecraft.
 *
 * <p>Recipes of any kind (crafting, smelting, and other mods' machines) are given in the form
 * they were loaded from (their codec turns them back into JSON), so a mod's own recipe types
 * need nothing special here. An index of which items each recipe makes and uses is built when
 * the server starts (in the background) and again after /reload.
 *
 * <p>Mistakes (an item that doesn't exist) are IllegalArgumentException, with a message for a
 * person: "No item is called x. Did you mean y?".
 */
public final class GameKnowledge {
    private GameKnowledge() {
    }

    /** The most a call gives at once (a program's memory is small). */
    public static final int MAX_LIMIT = 50;
    private static final int MAX_RECIPE_CHARS = 4_000;
    /** Keys whose values are what a recipe makes. */
    private static final Pattern RESULT_KEY = Pattern.compile("(?i)results?|outputs?|result_item|produces");
    private static final Set<String> NOISE = Set.of("category", "group", "show_notification", "neoforge:conditions", "conditions");

    private record Index(Collection<RecipeHolder<?>> of, Map<String, List<ResourceKey<Recipe<?>>>> makes,
            Map<String, List<ResourceKey<Recipe<?>>>> uses, Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byId) {
    }

    private static volatile Index index;

    /** Builds the recipe index ahead of the first question (the server has just started). */
    public static void warm(MinecraftServer server) {
        Thread.ofVirtual().name("qbotics-recipe-index").start(() -> {
            try {
                index(server);
            } catch (RuntimeException e) {
                qbotics.Qbotics.LOGGER.warn("Couldn't index the recipes ahead of time; it will be done when first needed", e);
            }
        });
    }

    // ---- items -------------------------------------------------------------------

    /**
     * Items whose name (or display name) matches: a word ("copper"), a pattern ("*_ingot"), a
     * #tag or an @mod, as filters in code. {"total": n, "items": [{"id", "name"}, ...]}.
     */
    public static JsonObject findItems(String query, int limit) {
        String text = query.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Give something to look for: a word (\"copper\"), a pattern (\"*_ingot\"), a #tag or an @mod.");
        }
        int most = Math.clamp(limit, 1, MAX_LIMIT);
        JsonArray found = new JsonArray();
        int total = 0;
        try {
            NameMatch<Item> match = NameMatch.parse(BuiltInRegistries.ITEM, text.contains(":") || text.matches(".*[*?#@$|-].*") ? text : "*" + text + "*");
            for (Item item : BuiltInRegistries.ITEM) {
                if (match.test(item)) {
                    total++;
                    if (found.size() < most) {
                        found.add(brief(item));
                    }
                }
            }
        } catch (RuntimeException notAFilter) {
            // Not a filter the mod understands: display names only, below.
        }
        if (total == 0) {
            // Display names, for the words a player sees ("Brass Ingot").
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            for (Item item : BuiltInRegistries.ITEM) {
                if (new ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                    total++;
                    if (found.size() < most) {
                        found.add(brief(item));
                    }
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.add("items", found);
        return result;
    }

    private static JsonObject brief(Item item) {
        JsonObject one = new JsonObject();
        one.addProperty("id", BuiltInRegistries.ITEM.getKey(item).toString());
        one.addProperty("name", new ItemStack(item).getHoverName().getString());
        return one;
    }

    /** One item: its names, mod, tags, stack size, fuel and food values, and how many recipes make and use it. */
    public static JsonObject itemInfo(MinecraftServer server, String name) {
        Item item = item(name);
        ItemStack stack = new ItemStack(item);
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        JsonObject info = new JsonObject();
        info.addProperty("id", id.toString());
        info.addProperty("name", stack.getHoverName().getString());
        info.addProperty("mod", id.getNamespace());
        info.addProperty("stack_size", stack.getMaxStackSize());
        if (stack.getMaxDamage() > 0) {
            info.addProperty("durability", stack.getMaxDamage());
        }
        int burn = server.fuelValues().burnDuration(stack);
        if (burn > 0) {
            info.addProperty("fuel_ticks", burn);
            info.addProperty("fuel_smelts_items", burn / 200.0);
        }
        var food = stack.get(DataComponents.FOOD);
        if (food != null) {
            info.addProperty("food_nutrition", food.nutrition());
            info.addProperty("food_saturation", food.saturation());
        }
        JsonArray tags = new JsonArray();
        BuiltInRegistries.ITEM.wrapAsHolder(item).tags().forEach(tag -> tags.add("#" + tag.location()));
        info.add("tags", tags);
        Index recipes = index(server);
        info.addProperty("recipes_making_it", recipes.makes().getOrDefault(id.toString(), List.of()).size());
        info.addProperty("recipes_using_it", usesOf(recipes, item).size());
        return info;
    }

    private static Item item(String name) {
        String text = name.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Give an item: its name (crafting_table, minecraft:crafting_table) or display name (Crafting Table).");
        }
        Identifier id = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text.replace(' ', '_').toLowerCase(java.util.Locale.ROOT));
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.getValue(id);
        }
        // A display name ("Brass Ingot"), or a name in another mod.
        Item only = null;
        int matches = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (path.equalsIgnoreCase(text.replace(' ', '_')) || new ItemStack(item).getHoverName().getString().equalsIgnoreCase(text)) {
                only = item;
                matches++;
            }
        }
        if (matches == 1) {
            return only;
        }
        List<String> near = NameMatch.closest(BuiltInRegistries.ITEM, text.replace(' ', '_'));
        throw new IllegalArgumentException(matches > 1 ? "Several items are called " + text + ": give its full name (mod:name)."
                : "No item is called " + text + (near.isEmpty() ? "." : ". Did you mean " + String.join(", ", near) + "?"));
    }

    // ---- recipes -----------------------------------------------------------------

    /**
     * The recipes that make an item, or (uses) those it is an ingredient of, as the game has them:
     * {"item", "uses", "total", "recipes": [{"id", "type", ... as the recipe's own data}, ...]}.
     */
    public static JsonObject recipes(MinecraftServer server, String name, boolean uses, int limit) {
        Item item = item(name);
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        Index recipes = index(server);
        List<ResourceKey<Recipe<?>>> found = uses ? usesOf(recipes, item) : recipes.makes().getOrDefault(id, List.of());
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonArray list = new JsonArray();
        for (ResourceKey<Recipe<?>> key : found.subList(0, Math.min(Math.clamp(limit, 1, MAX_LIMIT), found.size()))) {
            RecipeHolder<?> holder = recipes.byId().get(key);
            JsonElement json = Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null);
            if (!(json instanceof JsonObject recipe)) {
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("id", key.identifier().toString());
            JsonObject craft = craft(server, recipes, key);
            if (craft != null) {
                one.add("craft", craft); // slots, the items that fit, leftovers: ready for logistics' Recipe.from_game
            }
            JsonObject tidied = (JsonObject) tidy(recipe);
            if (tidied.toString().length() > MAX_RECIPE_CHARS) {
                one.add("type", tidied.get("type"));
                one.addProperty("too_long", true);
            } else {
                tidied.entrySet().forEach(entry -> one.add(entry.getKey(), entry.getValue()));
            }
            list.add(one);
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", id);
        result.addProperty("uses", uses);
        result.addProperty("total", found.size());
        result.add("recipes", list);
        return result;
    }

    private static List<ResourceKey<Recipe<?>>> usesOf(Index recipes, Item item) {
        LinkedHashSet<ResourceKey<Recipe<?>>> all = new LinkedHashSet<>(recipes.uses().getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), List.of()));
        // Recipes that take any item of a tag it carries ("#c:ingots/iron").
        BuiltInRegistries.ITEM.wrapAsHolder(item).tags().forEach(tag -> all.addAll(recipes.uses().getOrDefault("#" + tag.location(), List.of())));
        return new ArrayList<>(all);
    }

    /** The recipe's JSON without what doesn't help (book categories, load conditions). */
    private static JsonElement tidy(JsonElement json) {
        if (json instanceof JsonObject object) {
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!NOISE.contains(entry.getKey())) {
                    kept.add(entry.getKey(), tidy(entry.getValue()));
                }
            }
            return kept;
        }
        if (json instanceof JsonArray array) {
            JsonArray kept = new JsonArray();
            array.forEach(element -> kept.add(tidy(element)));
            return kept;
        }
        return json;
    }

    // ---- recipes in a form a program can act on ----------------------------------------

    /** Craft forms by recipe, worked out once per load of the recipes; null for a recipe that has none. */
    private static final Map<ResourceKey<Recipe<?>>, java.util.Optional<JsonObject>> CRAFT = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Index craftFor;

    /** The full name of an item given as a full name, a name without its mod, or its display name. */
    public static String itemId(String name) {
        return BuiltInRegistries.ITEM.getKey(item(name)).toString();
    }

    /** The craft forms of the recipes that make an item (those the game can describe). */
    static List<JsonObject> craftForms(MinecraftServer server, String itemId) {
        Index recipes = index(server);
        List<JsonObject> forms = new ArrayList<>();
        for (ResourceKey<Recipe<?>> key : recipes.makes().getOrDefault(itemId, List.of())) {
            JsonObject form = craft(server, recipes, key);
            if (form != null) {
                forms.add(form);
            }
        }
        return forms;
    }

    private static JsonObject craft(MinecraftServer server, Index recipes, ResourceKey<Recipe<?>> key) {
        if (craftFor != recipes) {
            CRAFT.clear();
            craftFor = recipes;
        }
        return CRAFT.computeIfAbsent(key, k -> {
            RecipeHolder<?> holder = recipes.byId().get(k);
            if (holder == null) {
                return java.util.Optional.empty();
            }
            JsonElement json = Recipe.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), holder.value())
                    .result().orElse(null);
            try {
                return java.util.Optional.ofNullable(json instanceof JsonObject raw ? craftForm(server, k, raw) : null);
            } catch (RuntimeException unusual) {
                return java.util.Optional.empty(); // data this doesn't understand: no craft form, the raw recipe still
            }
        }).orElse(null);
    }

    /** An ingredient: the filter a program passes to move or Recipe, and the items it takes. */
    private record Ingredient(String filter, List<String> options, int total) {
    }

    private static final int MAX_OPTIONS = 24;

    /** An ingredient's JSON as recipes write it: "mod:item", "#tag", or a list of those. Null if it is something else. */
    private static Ingredient ingredient(JsonElement json) {
        List<String> written = new ArrayList<>();
        if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            written.add(primitive.getAsString());
        } else if (json instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonPrimitive p && p.isString())) {
                    return null;
                }
                written.add(p.getAsString());
            }
        } else if (json instanceof JsonObject object && object.size() == 1 && (object.has("item") || object.has("tag"))) {
            written.add(object.has("item") ? object.get("item").getAsString() : "#" + object.get("tag").getAsString());
        } else {
            return null; // a mod's own kind of ingredient
        }
        LinkedHashSet<String> options = new LinkedHashSet<>();
        for (String one : written) {
            if (one.startsWith("#")) {
                Identifier tag = Identifier.tryParse(one.substring(1));
                if (tag == null) {
                    return null;
                }
                BuiltInRegistries.ITEM.getTagOrEmpty(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tag))
                        .forEach(holder -> options.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString()));
            } else {
                Identifier id = Identifier.tryParse(one);
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                    return null;
                }
                options.add(id.toString());
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        List<String> all = new ArrayList<>(options);
        return new Ingredient(String.join(" | ", written), all.subList(0, Math.min(MAX_OPTIONS, all.size())), all.size());
    }

    private static JsonObject input(int slot, Ingredient ingredient, int count) {
        JsonObject input = new JsonObject();
        input.addProperty("slot", slot);
        input.addProperty("item", ingredient.filter());
        input.addProperty("count", count);
        JsonArray options = new JsonArray();
        ingredient.options().forEach(options::add);
        input.add("options", options);
        if (ingredient.total() > ingredient.options().size()) {
            input.addProperty("options_total", ingredient.total());
        }
        return input;
    }

    /**
     * A recipe as a program can act on it, whatever its type: the station (the block that makes it),
     * each input by slot of that block's screen (the numbers logistics' Recipe uses), with the filter
     * to pass and the items that fit, the output and its slot, and what is left over. Null for a
     * recipe whose station or ingredients can't be known (other mods' machines, special recipes).
     */
    private static JsonObject craftForm(MinecraftServer server, ResourceKey<Recipe<?>> key, JsonObject raw) {
        String type = raw.has("type") ? raw.get("type").getAsString() : "";
        JsonArray inputs = new JsonArray();
        String station;
        int outputSlot;
        JsonObject extra = new JsonObject();
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                station = "minecraft:crafting_table";
                outputSlot = 0;
                JsonArray pattern = raw.getAsJsonArray("pattern");
                JsonObject keys = raw.getAsJsonObject("key");
                if (pattern.size() > 3) {
                    return null;
                }
                for (int row = 0; row < pattern.size(); row++) {
                    String line = pattern.get(row).getAsString();
                    if (line.length() > 3) {
                        return null;
                    }
                    for (int column = 0; column < line.length(); column++) {
                        char symbol = line.charAt(column);
                        if (symbol == ' ') {
                            continue;
                        }
                        Ingredient ingredient = ingredient(keys.get(String.valueOf(symbol)));
                        if (ingredient == null) {
                            return null;
                        }
                        inputs.add(input(1 + row * 3 + column, ingredient, 1)); // the grid is slots 1 to 9
                    }
                }
            }
            case "minecraft:crafting_shapeless" -> {
                station = "minecraft:crafting_table";
                outputSlot = 0;
                JsonArray ingredients = raw.getAsJsonArray("ingredients");
                if (ingredients.size() > 9) {
                    return null;
                }
                for (int i = 0; i < ingredients.size(); i++) {
                    Ingredient ingredient = ingredient(ingredients.get(i));
                    if (ingredient == null) {
                        return null;
                    }
                    inputs.add(input(1 + i, ingredient, 1));
                }
            }
            case "minecraft:crafting_transmute" -> {
                station = "minecraft:crafting_table";
                outputSlot = 0;
                Ingredient first = ingredient(raw.get("input"));
                Ingredient second = ingredient(raw.get("material"));
                if (first == null || second == null) {
                    return null;
                }
                inputs.add(input(1, first, 1));
                inputs.add(input(2, second, 1));
            }
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" -> {
                station = switch (type) {
                    case "minecraft:smelting" -> "minecraft:furnace";
                    case "minecraft:blasting" -> "minecraft:blast_furnace";
                    case "minecraft:smoking" -> "minecraft:smoker";
                    default -> "minecraft:campfire";
                };
                outputSlot = type.equals("minecraft:campfire_cooking") ? -1 : 2; // a furnace: 0 in, 1 fuel, 2 out
                Ingredient ingredient = ingredient(raw.get("ingredient"));
                if (ingredient == null) {
                    return null;
                }
                inputs.add(input(0, ingredient, 1));
                extra.addProperty("cook_ticks", raw.has("cookingtime") ? raw.get("cookingtime").getAsInt() : 200);
                if (raw.has("experience")) {
                    extra.addProperty("xp", raw.get("experience").getAsFloat());
                }
                if (outputSlot >= 0) {
                    extra.addProperty("fuel_slot", 1);
                }
            }
            case "minecraft:stonecutting" -> {
                station = "minecraft:stonecutter";
                outputSlot = 1;
                Ingredient ingredient = ingredient(raw.get("ingredient"));
                if (ingredient == null) {
                    return null;
                }
                inputs.add(input(0, ingredient, 1));
                // Which of the stonecutter's choices this is, for the first item that fits.
                ItemStack in = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(ingredient.options().getFirst())));
                var choices = server.getRecipeManager().stonecutterRecipes().selectByInput(in).entries();
                for (int i = 0; i < choices.size(); i++) {
                    if (choices.get(i).recipe().recipe().map(h -> h.id().equals(key)).orElse(false)) {
                        extra.addProperty("button", i);
                    }
                }
            }
            case "minecraft:smithing_transform" -> {
                station = "minecraft:smithing_table";
                outputSlot = 3;
                String[] parts = {"template", "base", "addition"};
                for (int i = 0; i < parts.length; i++) {
                    if (raw.has(parts[i])) {
                        Ingredient ingredient = ingredient(raw.get(parts[i]));
                        if (ingredient == null) {
                            return null;
                        }
                        inputs.add(input(i, ingredient, 1));
                    }
                }
            }
            default -> {
                return null;
            }
        }
        // The output: "result" as an item name or {"id", "count"}.
        JsonElement result = raw.get("result");
        String made;
        int count = 1;
        if (result instanceof JsonPrimitive primitive && primitive.isString()) {
            made = primitive.getAsString();
        } else if (result instanceof JsonObject object && (object.has("id") || object.has("item"))) {
            made = (object.has("id") ? object.get("id") : object.get("item")).getAsString();
            count = object.has("count") ? object.get("count").getAsInt() : 1;
        } else {
            return null;
        }
        Identifier madeId = Identifier.tryParse(made);
        if (madeId == null || !BuiltInRegistries.ITEM.containsKey(madeId)) {
            return null;
        }
        JsonObject output = new JsonObject();
        if (outputSlot >= 0) {
            output.addProperty("slot", outputSlot);
        }
        output.addProperty("item", madeId.toString());
        output.addProperty("count", count);
        // What the crafting grid keeps: a bucket from milk, a bottle from honey.
        JsonArray leftovers = new JsonArray();
        if (station.equals("minecraft:crafting_table")) {
            Map<String, Integer> left = new LinkedHashMap<>();
            for (JsonElement element : inputs) {
                String first = element.getAsJsonObject().getAsJsonArray("options").get(0).getAsString();
                @SuppressWarnings("deprecation")
                var remainder = BuiltInRegistries.ITEM.getValue(Identifier.parse(first)).getCraftingRemainder();
                if (remainder != null) {
                    left.merge(BuiltInRegistries.ITEM.getKey(remainder.item().value()).toString(), remainder.count(), Integer::sum);
                }
            }
            left.forEach((item, n) -> {
                JsonObject one = new JsonObject();
                one.addProperty("item", item);
                one.addProperty("count", n);
                leftovers.add(one);
            });
        }
        JsonObject form = new JsonObject();
        form.addProperty("recipe", key.identifier().toString());
        form.addProperty("station", station);
        form.add("inputs", inputs);
        form.add("output", output);
        form.add("leftovers", leftovers);
        extra.entrySet().forEach(entry -> form.add(entry.getKey(), entry.getValue()));
        return form;
    }

    // ---- fuels -----------------------------------------------------------------------

    /** Every item that burns in a furnace, best first: {"id", "name", "ticks", "smelts"}. */
    public static JsonArray fuels(MinecraftServer server, int limit) {
        List<Object[]> all = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            int ticks = server.fuelValues().burnDuration(new ItemStack(item));
            if (ticks > 0) {
                all.add(new Object[] {item, ticks});
            }
        }
        all.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        JsonArray list = new JsonArray();
        for (Object[] one : all.subList(0, Math.min(Math.clamp(limit, 1, 500), all.size()))) {
            JsonObject fuel = brief((Item) one[0]);
            fuel.addProperty("ticks", (Integer) one[1]);
            fuel.addProperty("smelts", (Integer) one[1] / 200.0);
            list.add(fuel);
        }
        return list;
    }

    // ---- calls from computers -----------------------------------------------------------

    /**
     * A computer's game_* call (the game module, the shell's recipe, item and items): answered
     * off the server's main thread, since it only reads what was loaded. {"r": ...} or {"e": why}.
     */
    public static String answerCall(MinecraftServer server, String call) {
        JsonObject reply = new JsonObject();
        try {
            JsonArray args = com.google.gson.JsonParser.parseString(call).getAsJsonArray();
            String method = args.get(0).getAsString();
            JsonElement answer = switch (method) {
                case "game_recipes" -> recipes(server, args.get(1).getAsString(), args.get(2).getAsBoolean(), args.size() > 3 ? args.get(3).getAsInt() : 10);
                case "game_item" -> itemInfo(server, args.get(1).getAsString());
                case "game_find" -> findItems(args.get(1).getAsString(), args.size() > 2 ? args.get(2).getAsInt() : 40);
                case "game_fuels" -> fuels(server, args.size() > 1 ? args.get(1).getAsInt() : 20);
                case "game_plan" -> {
                    Map<String, Long> have = new HashMap<>();
                    if (args.size() > 3 && args.get(3).isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : args.get(3).getAsJsonObject().entrySet()) {
                            have.merge(itemIdOrSame(entry.getKey()), entry.getValue().getAsLong(), Long::sum);
                        }
                    }
                    Set<String> stations = new java.util.HashSet<>();
                    if (args.size() > 4 && args.get(4).isJsonArray()) {
                        args.get(4).getAsJsonArray().forEach(s -> stations.add(s.getAsString().contains(":") ? s.getAsString() : "minecraft:" + s.getAsString()));
                    }
                    yield CraftPlanner.plan(server, args.get(1).getAsString(), args.get(2).getAsLong(), have, stations);
                }
                default -> throw new IllegalArgumentException("No such call: " + method);
            };
            reply.add("r", answer);
        } catch (IllegalArgumentException e) {
            reply.addProperty("e", e.getMessage());
        } catch (RuntimeException e) {
            qbotics.Qbotics.LOGGER.warn("A game call failed: {}", call.length() > 300 ? call.substring(0, 300) : call, e);
            reply = new JsonObject();
            reply.addProperty("e", "The call failed: its arguments are wrong (" + e.getClass().getSimpleName() + ").");
        }
        return reply.toString();
    }

    /** An item name in storage counts as given ("coal" is minecraft:coal); one the game doesn't know is kept as it is. */
    private static String itemIdOrSame(String name) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id.toString() : name;
    }

    /** Which items (and tags) each recipe makes and uses: built from every recipe's JSON, once per load of the recipes. */
    private static Index index(MinecraftServer server) {
        Collection<RecipeHolder<?>> all = server.getRecipeManager().getRecipes();
        Index known = index;
        if (known != null && known.of() == all) {
            return known;
        }
        synchronized (GameKnowledge.class) {
            known = index;
            if (known != null && known.of() == all) {
                return known;
            }
            long started = System.nanoTime();
            RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            Map<String, List<ResourceKey<Recipe<?>>>> makes = new HashMap<>();
            Map<String, List<ResourceKey<Recipe<?>>>> uses = new HashMap<>();
            Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byId = new HashMap<>();
            for (RecipeHolder<?> holder : all) {
                JsonElement json;
                try {
                    json = Recipe.CODEC.encodeStart(ops, holder.value()).result().orElse(null);
                } catch (RuntimeException e) {
                    json = null; // a mod's recipe that can't be written back: left out
                }
                if (json == null) {
                    continue;
                }
                byId.put(holder.id(), holder);
                Set<String> made = new LinkedHashSet<>();
                Set<String> used = new LinkedHashSet<>();
                walk(json, false, made, used, null);
                for (String id : made) {
                    makes.computeIfAbsent(id, k -> new ArrayList<>()).add(holder.id());
                }
                used.removeAll(made);
                for (String id : used) {
                    uses.computeIfAbsent(id, k -> new ArrayList<>()).add(holder.id());
                }
            }
            index = known = new Index(all, makes, uses, byId);
            qbotics.Qbotics.LOGGER.info("Indexed {} recipes (for the recipe command, the game module and the AI agent) in {} ms",
                    byId.size(), (System.nanoTime() - started) / 1_000_000);
            return known;
        }
    }

    /** Finds the item ids (and #tags) in a recipe's JSON, sorting them into what it makes and what it uses. */
    private static void walk(JsonElement json, boolean result, Set<String> made, Set<String> used, String key) {
        if (json instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                walk(entry.getValue(), result || RESULT_KEY.matcher(entry.getKey()).matches(), made, used, entry.getKey());
            }
        } else if (json instanceof JsonArray array) {
            array.forEach(element -> walk(element, result, made, used, key));
        } else if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            String text = primitive.getAsString();
            String found = null;
            if ("tag".equals(key)) {
                found = "#" + text;
            } else if (text.startsWith("#")) {
                found = text;
            } else if (text.indexOf(':') > 0) {
                Identifier id = Identifier.tryParse(text);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    found = id.toString();
                }
            }
            if (found != null) {
                (result ? made : used).add(found);
            }
        }
    }
}
