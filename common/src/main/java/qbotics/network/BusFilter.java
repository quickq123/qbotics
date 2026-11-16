package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What an item bus lets into the container it points at (bus.set_filter, docs/reference.md
 * §6.3a): anything one of its names or filters passes, and, when matching, any kind the
 * container already holds. Kept by the plate, so it holds for every move, every storage the
 * bus is part of, and things pushed through a tunnel. It never stops anything coming out.
 *
 * @param names    as written ("#c:ores", "coal"); a name that no longer means anything (its mod
 *                 removed) passes nothing
 * @param matching true to also let in any kind already there
 * @param exact    with matching: only things identical to one already there, data and all (a
 *                 Strength II potion, not any potion); without, any of the same item
 */
public record BusFilter(List<String> names, boolean matching, boolean exact) {
    /** The most names one bus may keep. */
    public static final int MOST_NAMES = 32;

    public BusFilter {
        names = List.copyOf(names);
    }

    /** The names read as filters, made once per filter. */
    private List<NameMatch<Item>> compiled() {
        List<NameMatch<Item>> out = COMPILED.get(this);
        if (out == null) {
            out = new ArrayList<>();
            for (String name : names) {
                try {
                    out.add(NameMatch.parse(BuiltInRegistries.ITEM, name));
                } catch (RuntimeException unreadable) {
                    // passes nothing
                }
            }
            COMPILED.put(this, out);
        }
        return out;
    }

    private static final java.util.Map<BusFilter, List<NameMatch<Item>>> COMPILED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** True if one of its names or filters passes this stack (not counting matching). */
    public boolean named(ItemStack stack) {
        for (NameMatch<Item> match : compiled()) {
            if (match.testStack(stack)) {
                return true;
            }
        }
        return false;
    }

    /** True if it lets this stack in, given what the container holds now. */
    public boolean allows(ItemStack like, List<ItemStack> held) {
        if (named(like)) {
            return true;
        }
        if (matching) {
            for (ItemStack stack : held) {
                if (!stack.isEmpty() && (exact ? ItemStack.isSameItemSameComponents(stack, like) : stack.is(like.getItem()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How a program writes its matching: False, True or "exact". */
    public com.google.gson.JsonElement matchingJson() {
        return exact && matching ? new com.google.gson.JsonPrimitive("exact") : new com.google.gson.JsonPrimitive(matching);
    }

    /** A bus's container, letting in only what its filter allows. Taking out is never stopped. */
    public static ItemPort wrap(ItemPort inner, @Nullable BusFilter filter) {
        return filter == null ? inner : new Filtered(inner, filter);
    }

    /** The container behind a filtered bus. */
    record Filtered(ItemPort inner, BusFilter filter) implements ItemPort {
        boolean allows(ItemStack like) {
            return filter.allows(like, filter.matching() && !filter.named(like) ? inner.contents() : List.of());
        }

        @Override
        public List<ItemStack> contents() {
            return inner.contents();
        }

        @Override
        public int insert(ItemStack like, int max, boolean simulate) {
            return allows(like) ? inner.insert(like, max, simulate) : 0;
        }

        @Override
        public int extract(ItemStack like, int max, boolean simulate) {
            return inner.extract(like, max, simulate);
        }

        @Override
        public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
            return allows(like) ? inner.insertInto(slot, like, max, simulate) : 0;
        }

        @Override
        public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
            return inner.extractFrom(slot, like, max, simulate);
        }
    }
}
