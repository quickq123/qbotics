package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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

    /**
     * Moves up to count of an item from one place to another and returns how many moved.
     * Every step is tried out before it is done, so nothing is lost or copied when the
     * destination is full or refuses the item.
     */
    static int move(ItemPort from, ItemPort to, Item item, int count) {
        int moved = 0;
        // Stacks of one item can still differ (damage, enchantments, names); each kind moves separately.
        List<ItemStack> kinds = new ArrayList<>();
        for (ItemStack stack : from.contents()) {
            if (stack.is(item) && kinds.stream().noneMatch(kind -> ItemStack.isSameItemSameComponents(kind, stack))) {
                kinds.add(stack);
            }
        }
        for (ItemStack kind : kinds) {
            if (moved >= count) {
                break;
            }
            int available = from.extract(kind, count - moved, true);
            int fits = available == 0 ? 0 : to.insert(kind, available, true);
            if (fits == 0) {
                continue;
            }
            int taken = from.extract(kind, fits, false);
            int placed = taken == 0 ? 0 : to.insert(kind, taken, false);
            if (placed < taken) {
                // The destination took less than it said it would: give the rest back.
                int returned = from.insert(kind, taken - placed, false);
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
