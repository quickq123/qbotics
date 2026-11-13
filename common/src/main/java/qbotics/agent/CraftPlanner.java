package qbotics.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

/**
 * Works out how to make an item from what is in storage (game.plan, design doc §17.8): the whole
 * tree of recipes, in the order to do them, what is taken from storage, and what is missing.
 * Done by the server, off its main thread, so a computer's small Python needn't.
 *
 * <p>Only recipes the game can describe for a program (GameKnowledge's craft form: crafting
 * table, furnaces, stonecutter, smithing table) are used, and only at the stations asked for.
 * Where several recipes make an item, the one whose ingredients are most at hand is chosen;
 * cycles (ingots to a block and back) are never followed. For a tag ingredient ("any planks"),
 * whatever matching items are stored are used first, mixed if need be, then the one most
 * easily made. Work is capped, so a huge tree answers "too big" instead of taking long.
 */
public final class CraftPlanner {
    /** Recipes looked at, at most, for one plan. */
    private static final int BUDGET = 6_000;
    /** The largest count "how many could be made" tries. */
    private static final long MAX_COUNT = 1_000_000;
    /** Preferred stations, when two recipes are as good. */
    private static final List<String> STATION_ORDER = List.of("minecraft:crafting_table", "minecraft:stonecutter",
            "minecraft:smithing_table", "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace", "minecraft:campfire");

    private final MinecraftServer server;
    private final Set<String> stations;
    private final Map<String, Long> have;
    private final Map<String, Long> fromStorage = new LinkedHashMap<>();
    private final Map<String, Long> missing = new LinkedHashMap<>();
    private final List<JsonObject> steps = new ArrayList<>();
    private final Deque<String> chain = new ArrayDeque<>();
    private int budget = BUDGET;
    private boolean tooBig;

    private CraftPlanner(MinecraftServer server, Set<String> stations, Map<String, Long> have) {
        this.server = server;
        this.stations = stations;
        this.have = new HashMap<>(have);
    }

    /**
     * A plan for count of an item from what is in storage (have: item id to count), using only
     * these stations (empty: any). {"item", "count", "ok", "steps", "from_storage", "missing",
     * "max_craftable", "too_big"}.
     */
    public static JsonObject plan(MinecraftServer server, String itemName, long count, Map<String, Long> have, Set<String> stations) {
        String item = GameKnowledge.itemId(itemName);
        if (count < 1) {
            throw new IllegalArgumentException("Plan for 1 or more, not " + count + ".");
        }
        CraftPlanner planner = new CraftPlanner(server, stations, have);
        planner.need(item, count);
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        result.addProperty("count", count);
        result.addProperty("ok", planner.missing.isEmpty() && !planner.tooBig);
        JsonArray steps = new JsonArray();
        planner.steps.forEach(steps::add);
        result.add("steps", steps);
        result.add("from_storage", list(planner.fromStorage));
        result.add("missing", list(planner.missing));
        result.addProperty("max_craftable", maxCraftable(server, item, have, stations));
        result.addProperty("too_big", planner.tooBig);
        return result;
    }

    /** The most of an item that could be made from what is in storage: doubling, then halving the gap. */
    private static long maxCraftable(MinecraftServer server, String item, Map<String, Long> have, Set<String> stations) {
        long good = 0;
        long bad = -1;
        long started = System.nanoTime();
        for (long n = 1; n <= MAX_COUNT; n *= 2) {
            if (works(server, item, n, have, stations)) {
                good = n;
            } else {
                bad = n;
                break;
            }
            if (System.nanoTime() - started > 300_000_000L) {
                return good; // enough time spent: at least this many
            }
        }
        if (bad < 0) {
            return good;
        }
        while (bad - good > 1 && System.nanoTime() - started < 300_000_000L) {
            long middle = good + (bad - good) / 2;
            if (works(server, item, middle, have, stations)) {
                good = middle;
            } else {
                bad = middle;
            }
        }
        return good;
    }

    private static boolean works(MinecraftServer server, String item, long count, Map<String, Long> have, Set<String> stations) {
        CraftPlanner trial = new CraftPlanner(server, stations, have);
        trial.need(item, count);
        return trial.missing.isEmpty() && !trial.tooBig;
    }

    private static JsonArray list(Map<String, Long> counts) {
        JsonArray list = new JsonArray();
        counts.forEach((item, count) -> {
            JsonObject one = new JsonObject();
            one.addProperty("item", item);
            one.addProperty("count", count);
            list.add(one);
        });
        return list;
    }

    // ---- the tree ----------------------------------------------------------------

    private long stored(String item) {
        return have.getOrDefault(item, 0L);
    }

    private void take(String item, long count) {
        have.merge(item, -count, Long::sum);
        fromStorage.merge(item, count, Long::sum);
    }

    /** Gets count of an item: from storage, then by recipe. */
    private void need(String item, long count) {
        long taken = Math.min(stored(item), count);
        if (taken > 0) {
            take(item, taken);
            count -= taken;
        }
        if (count == 0) {
            return;
        }
        if (--budget < 0) {
            tooBig = true;
            missing.merge(item, count, Long::sum);
            return;
        }
        JsonObject best = null;
        double bestScore = -1;
        if (!chain.contains(item)) {
            for (JsonObject recipe : recipesFor(item)) {
                if (!viable(recipe, item)) {
                    continue;
                }
                double score = score(recipe);
                if (score > bestScore) {
                    best = recipe;
                    bestScore = score;
                }
            }
        }
        if (best == null) {
            missing.merge(item, count, Long::sum);
            return;
        }
        make(best, item, count);
    }

    /** The recipes, at the stations allowed, that make this item as their output, best station first. */
    private List<JsonObject> recipesFor(String item) {
        List<JsonObject> found = new ArrayList<>();
        for (JsonObject recipe : GameKnowledge.craftForms(server, item)) {
            String station = recipe.get("station").getAsString();
            if ((stations.isEmpty() || stations.contains(station))
                    && recipe.getAsJsonObject("output").get("item").getAsString().equals(item)) {
                found.add(recipe);
            }
        }
        found.sort((a, b) -> Integer.compare(order(a), order(b)));
        return found;
    }

    private static int order(JsonObject recipe) {
        int at = STATION_ORDER.indexOf(recipe.get("station").getAsString());
        return at < 0 ? STATION_ORDER.size() : at;
    }

    /** The ingredients of one run, by what is asked for (several slots of planks are one line). */
    private static Map<String, Object[]> lines(JsonObject recipe) {
        Map<String, Object[]> lines = new LinkedHashMap<>();
        for (JsonElement element : recipe.getAsJsonArray("inputs")) {
            JsonObject input = element.getAsJsonObject();
            String what = input.get("item").getAsString();
            Object[] line = lines.computeIfAbsent(what, k -> new Object[] {0L, input.getAsJsonArray("options")});
            line[0] = (Long) line[0] + input.get("count").getAsLong();
        }
        return lines;
    }

    private long storedOf(JsonArray options) {
        long total = 0;
        for (JsonElement option : options) {
            total += stored(option.getAsString());
        }
        return total;
    }

    /** Whether each ingredient is stored, or could be made without going round in a circle. */
    private boolean viable(JsonObject recipe, String item) {
        for (Object[] line : lines(recipe).values()) {
            JsonArray options = (JsonArray) line[1];
            if (storedOf(options) > 0) {
                continue;
            }
            boolean any = false;
            for (JsonElement option : options) {
                String o = option.getAsString();
                if (!o.equals(item) && !chain.contains(o) && makeable(o, item)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Whether some recipe for an item uses something other than what is being made around it. */
    private boolean makeable(String what, String forItem) {
        for (JsonObject recipe : recipesFor(what)) {
            boolean ok = true;
            for (Object[] line : lines(recipe).values()) {
                JsonArray options = (JsonArray) line[1];
                boolean other = storedOf(options) > 0;
                for (JsonElement option : options) {
                    String o = option.getAsString();
                    if (!o.equals(forItem) && !o.equals(what) && !chain.contains(o)) {
                        other = true;
                        break;
                    }
                }
                if (!other) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** How much of a run's ingredients are in storage (0 to 1), a little more for a preferred station. */
    private double score(JsonObject recipe) {
        Map<String, Object[]> lines = lines(recipe);
        double total = 0;
        for (Object[] line : lines.values()) {
            total += Math.min(1.0, storedOf((JsonArray) line[1]) / (double) (Long) line[0]);
        }
        return (lines.isEmpty() ? 1 : total / lines.size()) + (STATION_ORDER.size() - order(recipe)) * 0.001;
    }

    /** Makes count of an item with this recipe: its ingredients first (from storage, or made), then it. */
    private void make(JsonObject recipe, String item, long count) {
        JsonObject output = recipe.getAsJsonObject("output");
        long each = Math.max(1, output.get("count").getAsLong());
        long runs = (count + each - 1) / each;
        chain.push(item);
        Map<String, Long> used = new LinkedHashMap<>();
        for (Map.Entry<String, Object[]> entry : lines(recipe).entrySet()) {
            long wanted = runs * (Long) entry.getValue()[0];
            JsonArray options = (JsonArray) entry.getValue()[1];
            // What is stored first, the most plentiful kind first ("any planks": oak, then spruce...).
            List<String> byStock = new ArrayList<>();
            options.forEach(o -> byStock.add(o.getAsString()));
            byStock.sort((a, b) -> Long.compare(stored(b), stored(a)));
            for (String option : byStock) {
                long taken = Math.min(stored(option), wanted);
                if (taken > 0) {
                    take(option, taken);
                    used.merge(option, taken, Long::sum);
                    wanted -= taken;
                }
            }
            if (wanted > 0) {
                String chosen = easiest(byStock, item);
                need(chosen, wanted);
                used.merge(chosen, wanted, Long::sum);
            }
        }
        chain.pop();
        // What is left over: buckets from a recipe that empties them, and any extra made.
        for (JsonElement element : recipe.getAsJsonArray("leftovers")) {
            JsonObject left = element.getAsJsonObject();
            have.merge(left.get("item").getAsString(), runs * left.get("count").getAsLong(), Long::sum);
        }
        long extra = runs * each - count;
        if (extra > 0) {
            have.merge(item, extra, Long::sum);
        }
        JsonObject step = new JsonObject();
        step.addProperty("recipe", recipe.get("recipe").getAsString());
        step.addProperty("station", recipe.get("station").getAsString());
        step.addProperty("runs", runs);
        JsonObject makes = new JsonObject();
        makes.addProperty("item", item);
        makes.addProperty("count", runs * each);
        step.add("makes", makes);
        step.add("uses", list(used));
        step.add("craft", recipe);
        steps.add(step);
    }

    /** Of the items a tag ingredient allows, the one most easily made now (logs at hand for those planks). */
    private String easiest(List<String> options, String forItem) {
        String best = null;
        double bestScore = -1;
        for (String option : options) {
            if (option.equals(forItem) || chain.contains(option)) {
                continue;
            }
            for (JsonObject recipe : recipesFor(option)) {
                if (viable(recipe, option)) {
                    double score = score(recipe);
                    if (score > bestScore) {
                        best = option;
                        bestScore = score;
                    }
                }
            }
            if (bestScore >= 1.0) {
                break; // everything at hand: good enough
            }
        }
        return best != null ? best : options.getFirst();
    }
}
