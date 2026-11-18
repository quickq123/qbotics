package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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

    /**
     * Whether this slot's rules let this item in, whatever it holds now (room aside). By
     * default worked out from a trial run, which a full or occupied slot can't answer.
     */
    default boolean mayPlace(int slot, ItemStack like) {
        return insertInto(slot, like.copyWithCount(1), 1, true) > 0;
    }

    /** Items a slot is tried with to learn whether it takes anything (slots() "accepts"). */
    List<ItemStack> PROBES = java.util.stream.Stream.of(net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COAL,
            net.minecraft.world.item.Items.RAW_IRON, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.DIAMOND,
            net.minecraft.world.item.Items.STICK, net.minecraft.world.item.Items.OAK_LOG, net.minecraft.world.item.Items.OAK_PLANKS,
            net.minecraft.world.item.Items.WHEAT_SEEDS, net.minecraft.world.item.Items.PAPER, net.minecraft.world.item.Items.MAP,
            net.minecraft.world.item.Items.BOOK, net.minecraft.world.item.Items.LAPIS_LAZULI, net.minecraft.world.item.Items.POTION,
            net.minecraft.world.item.Items.GLASS_BOTTLE, net.minecraft.world.item.Items.BLAZE_POWDER, net.minecraft.world.item.Items.NETHER_WART,
            net.minecraft.world.item.Items.BUCKET, net.minecraft.world.item.Items.WHITE_BANNER, net.minecraft.world.item.Items.RED_DYE, net.minecraft.world.item.Items.FLOWER_BANNER_PATTERN,
            net.minecraft.world.item.Items.IRON_PICKAXE, net.minecraft.world.item.Items.DIAMOND_PICKAXE, net.minecraft.world.item.Items.LEATHER_HELMET,
            net.minecraft.world.item.Items.LEATHER_BOOTS, net.minecraft.world.item.Items.SADDLE, net.minecraft.world.item.Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
            .map(ItemStack::new).toList();

    /**
     * Whether anything may be put into this slot: false for a product slot (a furnace's or
     * crafting table's) or a closed one. Tried with what it holds and a set of common items, so a
     * slot for one rare thing alone (another mod's) may show false until it holds one.
     */
    default boolean acceptsAny(int slot) {
        List<ItemStack> stacks = contents();
        if (slot >= 0 && slot < stacks.size() && !stacks.get(slot).isEmpty() && mayPlace(slot, stacks.get(slot))) {
            return true;
        }
        for (ItemStack probe : PROBES) {
            if (mayPlace(slot, probe)) {
                return true;
            }
        }
        return false;
    }

    /** Moves up to count of an item from one place to another, using any slots. */
    static int move(ItemPort from, ItemPort to, Item item, int count) {
        return move(from, -1, to, -1, stack -> stack.is(item), count);
    }

    /**
     * Moves up to count items from one place to another and returns how many moved.
     * Every step is tried out before it is done, so nothing is lost or copied when the
     * destination is full or refuses the item.
     *
     * @param fromSlot the one slot to take from, or -1 for any
     * @param toSlot   the one slot to put into, or -1 for any
     * @param wanted   which stacks to move (one item, or a filter many pass), or null for whatever is there
     */
    static int move(ItemPort from, int fromSlot, ItemPort to, int toSlot, @Nullable Predicate<ItemStack> wanted, int count) {
        return move(from, fromSlot, to, toSlot, wanted, count, false);
    }

    /**
     * The same, with a trial run: with simulate true nothing changes, and the answer is how
     * many would move. (A trial of several kinds of item into one place can come out a little
     * high, because each kind is tried as if it had the free slots to itself.)
     */
    static int move(ItemPort from, int fromSlot, ItemPort to, int toSlot, @Nullable Predicate<ItemStack> wanted, int count, boolean simulate) {
        int moved = 0;
        // Stacks of one item can still differ (damage, enchantments, names); each kind moves separately.
        List<ItemStack> kinds = new ArrayList<>();
        List<ItemStack> held = from.contents();
        for (int slot = 0; slot < held.size(); slot++) {
            ItemStack stack = held.get(slot);
            boolean moves = !stack.isEmpty() && (fromSlot < 0 || slot == fromSlot) && (wanted == null || wanted.test(stack));
            if (moves && kinds.stream().noneMatch(kind -> ItemStack.isSameItemSameComponents(kind, stack))) {
                kinds.add(stack);
            }
        }
        for (ItemStack kind : kinds) {
            if (moved >= count) {
                break;
            }
            int most = count - moved;
            int available = fromSlot < 0 ? from.extract(kind, most, true) : from.extractFrom(fromSlot, kind, most, true);
            int fits = available == 0 ? 0 : toSlot < 0 ? to.insert(kind, available, true) : to.insertInto(toSlot, kind, available, true);
            if (fits == 0) {
                continue;
            }
            if (simulate) {
                moved += fits;
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
