package qbotics.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import qbotics.agent.GameKnowledge;
import qbotics.network.NetworkCalls.CallError;
import qbotics.qbot.QbotBlockEntity;

/**
 * A Qbot's built-in crafting table (design doc §8): it crafts from its own inventory as a
 * player at a crafting table would, by the game's own recipes (every mod's).
 *
 * <p>Two ways. By name: it finds a crafting-table recipe for the item whose ingredients it
 * holds, takes them from any of its slots, and does it as many times as needed. By grid: as
 * laid out in the top-left 3x3 of its 4x4 inventory (slots 0-2, 4-6, 8-10), one of each slot
 * per craft, for any recipe, including those found only by their pattern (fireworks, dyed
 * armour); what it makes goes into the other slots. Each craft is all or nothing: if what it
 * makes and what the grid leaves behind (a bucket) won't fit, it isn't done.
 */
final class QbotCrafting {
    /** The slots of its 4x4 inventory that are the crafting grid, row by row. */
    private static final int[] GRID = {0, 1, 2, 4, 5, 6, 8, 9, 10};
    /** The most crafts one call does. */
    private static final int MAX_RUNS = 64 * QbotBlockEntity.SLOTS;

    private QbotCrafting() {
    }

    /** Crafts at least count of an item (by name), or count from the grid (item null). {"item", "made"}. */
    static JsonObject craft(ServerLevel level, QbotBlockEntity qbot, String item, int count) {
        if (count < 1) {
            throw new CallError("craft() makes 1 or more, not " + count + ".");
        }
        return item == null ? byGrid(level, qbot, count) : byName(level, qbot, item, count);
    }

    // ---- by name ---------------------------------------------------------------------------

    private static JsonObject byName(ServerLevel level, QbotBlockEntity qbot, String name, int count) {
        String id;
        try {
            id = GameKnowledge.itemId(name);
        } catch (IllegalArgumentException e) {
            throw new CallError(e.getMessage());
        }
        List<JsonObject> forms = new ArrayList<>();
        for (JsonObject form : GameKnowledge.craftForms(level.getServer(), id)) {
            if ("minecraft:crafting_table".equals(form.get("station").getAsString())
                    && id.equals(form.getAsJsonObject("output").get("item").getAsString())) {
                forms.add(form);
            }
        }
        if (forms.isEmpty()) {
            throw new CallError("No crafting table recipe makes " + id + ", so a Qbot can't craft it.");
        }
        SimpleContainer items = qbot.items();
        int made = 0;
        boolean noRoom = false;
        for (int runs = 0; made < count && runs < MAX_RUNS; runs++) {
            Run run = null;
            for (JsonObject form : forms) {
                run = lay(level, items, form, id);
                if (run != null) {
                    break;
                }
            }
            if (run == null) {
                break;
            }
            int got = commit(items, run, allSlots());
            if (got < 0) {
                noRoom = true;
                break;
            }
            made += got;
        }
        if (made == 0) {
            throw new CallError(noRoom ? "There is no room in the Qbot's inventory for the " + id + " it would make."
                    : "The Qbot doesn't hold what " + id + " needs: " + needs(forms.getFirst()) + ".");
        }
        return result(id, made);
    }

    /** The grid for one craft of this recipe from what it holds now, or null if it lacks something. */
    private static Run lay(ServerLevel level, SimpleContainer items, JsonObject form, String id) {
        int[] left = new int[items.getContainerSize()];
        for (int slot = 0; slot < left.length; slot++) {
            left[slot] = items.getItem(slot).getCount();
        }
        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            grid.add(ItemStack.EMPTY);
        }
        int[] from = new int[9];
        java.util.Arrays.fill(from, -1);
        for (JsonElement element : form.getAsJsonArray("inputs")) {
            JsonObject input = element.getAsJsonObject();
            int cell = input.get("slot").getAsInt() - 1;
            if (cell < 0 || cell >= 9) {
                return null;
            }
            // The kind it holds most of, among those that fit ("any planks": its oak, then its spruce).
            int best = -1;
            for (JsonElement option : input.getAsJsonArray("options")) {
                Item wanted = BuiltInRegistries.ITEM.getValue(Identifier.parse(option.getAsString()));
                for (int slot = 0; slot < left.length; slot++) {
                    if (left[slot] > 0 && items.getItem(slot).is(wanted) && (best < 0 || left[slot] > left[best])) {
                        best = slot;
                    }
                }
            }
            if (best < 0) {
                return null;
            }
            left[best]--;
            from[cell] = best;
            grid.set(cell, items.getItem(best).copyWithCount(1));
        }
        Run run = match(level, grid, from);
        return run != null && run.result.getItem() == BuiltInRegistries.ITEM.getValue(Identifier.parse(id)) ? run : null;
    }

    private static String needs(JsonObject form) {
        Map<String, Integer> lines = new LinkedHashMap<>();
        for (JsonElement element : form.getAsJsonArray("inputs")) {
            lines.merge(element.getAsJsonObject().get("item").getAsString(), element.getAsJsonObject().get("count").getAsInt(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        lines.forEach((what, n) -> parts.add(n + " " + what));
        return String.join(", ", parts);
    }

    // ---- by grid ---------------------------------------------------------------------------

    private static JsonObject byGrid(ServerLevel level, QbotBlockEntity qbot, int count) {
        SimpleContainer items = qbot.items();
        int[] outputs = otherSlots();
        int made = 0;
        String id = null;
        boolean noRoom = false;
        for (int runs = 0; made < count && runs < MAX_RUNS; runs++) {
            List<ItemStack> grid = new ArrayList<>();
            boolean any = false;
            for (int slot : GRID) {
                ItemStack stack = items.getItem(slot);
                grid.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
                any |= !stack.isEmpty();
            }
            Run run = any ? match(level, grid, GRID.clone()) : null;
            if (run == null || (id != null && !id.equals(NetworkCalls.itemId(run.result.getItem())))) {
                if (made == 0) {
                    throw new CallError(any ? "What is in its crafting grid (slots 0-2, 4-6 and 8-10) isn't a recipe."
                            : "Its crafting grid (slots 0-2, 4-6 and 8-10) is empty: lay out a recipe there, or craft an item by name.");
                }
                break;
            }
            id = NetworkCalls.itemId(run.result.getItem());
            int got = commit(items, run, outputs);
            if (got < 0) {
                noRoom = true;
                break;
            }
            made += got;
        }
        if (made == 0 && noRoom) {
            throw new CallError("There is no room outside its crafting grid (slots 3, 7, 11 and 12-15) for the " + id + " it would make.");
        }
        return result(id, made);
    }

    // ---- one craft -------------------------------------------------------------------------

    /** One craft: the nine stacks of the grid, which slot each came from, what it makes, what it leaves. */
    private record Run(int[] from, ItemStack result, NonNullList<ItemStack> remaining) {
    }

    private static Run match(ServerLevel level, List<ItemStack> grid, int[] from) {
        CraftingInput.Positioned placed = CraftingInput.ofPositioned(3, 3, grid);
        CraftingInput input = placed.input();
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack result = recipe.get().value().assemble(input);
        if (result.isEmpty()) {
            return null;
        }
        // CraftingInput trims the empty rows and columns: its remainders are for what is left.
        NonNullList<ItemStack> trimmed = recipe.get().value().getRemainingItems(input);
        NonNullList<ItemStack> remaining = NonNullList.withSize(9, ItemStack.EMPTY);
        int at = 0;
        for (int cell = 0; cell < 9 && at < trimmed.size(); cell++) {
            int row = cell / 3 - placed.top();
            int col = cell % 3 - placed.left();
            if (row >= 0 && row < input.height() && col >= 0 && col < input.width()) {
                remaining.set(cell, trimmed.get(row * input.width() + col));
                at++;
            }
        }
        return new Run(from, result, remaining);
    }

    /**
     * Does one craft on a copy of the inventory, and keeps it only if everything fits:
     * the ingredients out, the product into these slots, what the grid leaves back where it
     * came from (or anywhere). How many it made, or -1 if there was no room.
     */
    private static int commit(SimpleContainer items, Run run, int[] outputSlots) {
        int size = items.getContainerSize();
        ItemStack[] copy = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            copy[slot] = items.getItem(slot).copy();
        }
        for (int slot : run.from) {
            if (slot >= 0) {
                copy[slot].shrink(1);
            }
        }
        for (int cell = 0; cell < 9; cell++) {
            ItemStack left = run.remaining.get(cell);
            if (left.isEmpty()) {
                continue;
            }
            int slot = run.from[cell];
            if (slot >= 0 && copy[slot].isEmpty()) {
                copy[slot] = left.copy();
            } else if (!put(copy, left.copy(), allSlots())) {
                return -1;
            }
        }
        if (!put(copy, run.result.copy(), outputSlots)) {
            return -1;
        }
        for (int slot = 0; slot < size; slot++) {
            items.setItem(slot, copy[slot].isEmpty() ? ItemStack.EMPTY : copy[slot]);
        }
        return run.result.getCount();
    }

    /** Puts a stack into these slots: onto stacks of the same first, then into empty ones. False if it doesn't all fit. */
    private static boolean put(ItemStack[] slots, ItemStack stack, int[] into) {
        for (int slot : into) {
            ItemStack there = slots[slot];
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, stack)) {
                int room = Math.min(there.getMaxStackSize(), 99) - there.getCount();
                int moved = Math.min(room, stack.getCount());
                if (moved > 0) {
                    there.grow(moved);
                    stack.shrink(moved);
                }
            }
            if (stack.isEmpty()) {
                return true;
            }
        }
        for (int slot : into) {
            if (slots[slot].isEmpty()) {
                slots[slot] = stack;
                return true;
            }
        }
        return false;
    }

    private static int[] allSlots() {
        int[] all = new int[QbotBlockEntity.SLOTS];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    /**
     * Where what the grid makes goes: slot 7 first (right of the grid's middle row, shaded on
     * its screen, as a crafting table's output sits), then the rest outside the grid.
     */
    private static int[] otherSlots() {
        return new int[] {7, 3, 11, 12, 13, 14, 15};
    }

    private static JsonObject result(String id, int made) {
        JsonObject result = new JsonObject();
        result.addProperty("item", id);
        result.addProperty("made", made);
        return result;
    }
}
