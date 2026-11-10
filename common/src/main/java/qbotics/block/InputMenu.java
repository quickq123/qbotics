package qbotics.block;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Input block's nine slots above the player's inventory. To the player's game it is an
 * ordinary one-row chest, so no screen of our own is needed yet; this server side adds the
 * rule that a slot takes only the item its filter names. Taking items out is always allowed.
 */
public class InputMenu extends AbstractContainerMenu {
    private final Container container;

    public InputMenu(int containerId, Inventory inventory, Container container) {
        super(MenuType.GENERIC_9x1, containerId);
        checkContainerSize(container, 9);
        this.container = container;
        container.startOpen(inventory.player);
        // The same positions as a one-row chest, which is what the player's game draws.
        for (int x = 0; x < 9; x++) {
            int index = x;
            addSlot(new Slot(container, index, 8 + x * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return container.canPlaceItem(index, stack);
                }
            });
        }
        addStandardInventorySlots(inventory, 8, 18 + 18 + 13);
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();
            if (slotIndex < 9) {
                if (!moveItemStackTo(stack, 9, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, 9, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return clicked;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
    }
}
