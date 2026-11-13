package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
