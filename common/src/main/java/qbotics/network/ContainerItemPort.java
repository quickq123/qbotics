package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Any container (a Qbot's inventory) as a place items move to and from. */
public final class ContainerItemPort implements ItemPort {
    private final Container container;

    /** Set when items move by the rules a hopper on the block meets: which slots take what, and when. */
    private final net.minecraft.world.@org.jspecify.annotations.Nullable WorldlyContainer automation;

    public ContainerItemPort(Container container) {
        this.container = container;
        this.automation = null;
    }

    private ContainerItemPort(net.minecraft.world.WorldlyContainer container, boolean byHopperRules) {
        this.container = container;
        this.automation = byHopperRules ? container : null;
    }

    /** A container whose slots take and give only what a hopper on the block could put in and take out. */
    public static ContainerItemPort asAutomation(net.minecraft.world.WorldlyContainer container) {
        return new ContainerItemPort(container, true);
    }

    private boolean placeable(int slot, ItemStack like) {
        return container.canPlaceItem(slot, like) && (automation == null || automation.canPlaceItemThroughFace(slot, like, null));
    }

    @Override
    public List<ItemStack> contents() {
        List<ItemStack> copy = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            copy.add(container.getItem(slot).copy());
        }
        return copy;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        int left = max;
        // Top up stacks of the same item before starting an empty slot.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
                ItemStack held = container.getItem(slot);
                boolean wanted = pass == 0 ? !held.isEmpty() && ItemStack.isSameItemSameComponents(held, like) : held.isEmpty();
                if (wanted) {
                    left -= insertInto(slot, like, left, simulate);
                }
            }
        }
        return max - left;
    }

    @Override
    public boolean mayPlace(int slot, ItemStack like) {
        return slot >= 0 && slot < container.getContainerSize() && placeable(slot, like);
    }

    @Override
    public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
        ItemStack held = container.getItem(slot);
        if (!placeable(slot, like) || (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, like))) {
            return 0;
        }
        int most = Math.min(container.getMaxStackSize(like), like.getMaxStackSize());
        int room = Math.clamp(most - held.getCount(), 0, Math.max(0, max));
        if (!simulate && room > 0) {
            container.setItem(slot, held.isEmpty() ? like.copyWithCount(room) : held.copyWithCount(held.getCount() + room));
        }
        return room;
    }

    @Override
    public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
        ItemStack held = container.getItem(slot);
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, like)
                || (automation != null && !automation.canTakeItemThroughFace(slot, held, net.minecraft.core.Direction.DOWN))) {
            return 0;
        }
        int take = Math.clamp(max, 0, held.getCount());
        if (!simulate && take > 0) {
            container.setItem(slot, take == held.getCount() ? ItemStack.EMPTY : held.copyWithCount(held.getCount() - take));
        }
        return take;
    }

    @Override
    public int extract(ItemStack like, int max, boolean simulate) {
        int left = max;
        for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
            left -= extractFrom(slot, like, left, simulate);
        }
        return max - left;
    }
}
