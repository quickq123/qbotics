package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Somewhere items can be read, put in and taken out: an Input block's own slots, or any
 * container seen through one of its faces. Each loader supplies the second kind with its
 * standard item API, so other mods' machines work without special support.
 */
public interface ItemPort {
    /** A copy of what is in each slot, in slot order. Empty slots are empty stacks. */
    List<ItemStack> contents();

    /**
     * Puts in up to max items like this one (same item, same extra data).
     *
     * @param simulate true to only work out the answer, changing nothing
     * @return how many went in
     */
    int insert(ItemStack like, int max, boolean simulate);

    /** Takes out up to max items like this one. Returns how many came out. */
    int extract(ItemStack like, int max, boolean simulate);

    /** The same as {@link #insert}, into one slot only (slots are numbered as in {@link #contents()}). */
    int insertInto(int slot, ItemStack like, int max, boolean simulate);

    /** The same as {@link #extract}, out of one slot only. */
    int extractFrom(int slot, ItemStack like, int max, boolean simulate);

    /** Moves up to count of an item from one place to another, using any slots. */
    static int move(ItemPort from, ItemPort to, Item item, int count) {
        return move(from, -1, to, -1, item, count);
    }

    /**
     * Moves up to count items from one place to another and returns how many moved.
     * Every step is tried out before it is done, so nothing is lost or copied when the
     * destination is full or refuses the item.
     *
     * @param fromSlot the one slot to take from, or -1 for any
     * @param toSlot   the one slot to put into, or -1 for any
     * @param item     the item to move, or null for whatever is there
     */
    static int move(ItemPort from, int fromSlot, ItemPort to, int toSlot, @Nullable Item item, int count) {
        int moved = 0;
        // Stacks of one item can still differ (damage, enchantments, names); each kind moves separately.
        List<ItemStack> kinds = new ArrayList<>();
        List<ItemStack> held = from.contents();
        for (int slot = 0; slot < held.size(); slot++) {
            ItemStack stack = held.get(slot);
            boolean wanted = !stack.isEmpty() && (fromSlot < 0 || slot == fromSlot) && (item == null || stack.is(item));
            if (wanted && kinds.stream().noneMatch(kind -> ItemStack.isSameItemSameComponents(kind, stack))) {
                kinds.add(stack);
            }
        }
        for (ItemStack kind : kinds) {
            if (moved >= count) {
                break;
            }
            int wanted = count - moved;
            int available = fromSlot < 0 ? from.extract(kind, wanted, true) : from.extractFrom(fromSlot, kind, wanted, true);
            int fits = available == 0 ? 0 : toSlot < 0 ? to.insert(kind, available, true) : to.insertInto(toSlot, kind, available, true);
            if (fits == 0) {
                continue;
            }
            int taken = fromSlot < 0 ? from.extract(kind, fits, false) : from.extractFrom(fromSlot, kind, fits, false);
            int placed = taken == 0 ? 0 : toSlot < 0 ? to.insert(kind, taken, false) : to.insertInto(toSlot, kind, taken, false);
            if (placed < taken) {
                // The destination took less than it said it would: give the rest back.
                int returned = fromSlot < 0 ? from.insert(kind, taken - placed, false) : from.insertInto(fromSlot, kind, taken - placed, false);
                if (returned < taken - placed) {
                    throw new IllegalStateException("Could not return " + (taken - placed - returned) + " x " + kind.getItem()
                            + " after a failed transfer");
                }
            }
            moved += placed;
        }
        return moved;
    }
}
