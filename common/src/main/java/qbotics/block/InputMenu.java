package qbotics.block;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Input block's screen: a row of nine filters above the row of nine slots they belong
 * to, above the player's inventory. To the player's game it is an ordinary two-row chest,
 * so no screen of our own is needed yet; the rules are all on this server side.
 *
 * <p>The top row holds no items. Clicking one of its slots with an item in hand sets the
 * filter of the slot below to that item (the item stays in hand); clicking with an empty
 * hand closes the slot again. The bottom row takes only what its filter names; taking items
 * out is always allowed.
 */
public class InputMenu extends AbstractContainerMenu {
    private static final int SLOTS = InputBlockEntity.SLOTS;

    private final InputBlockEntity input;
    /** One of each filter's item, only so the player's game has something to draw in the top row. */
    private final SimpleContainer shown = new SimpleContainer(SLOTS);

    public InputMenu(int containerId, Inventory inventory, InputBlockEntity input) {
        super(MenuType.GENERIC_9x2, containerId);
        this.input = input;
        input.startOpen(inventory.player);
        showFilters();
        // The same positions as a two-row chest, which is what the player's game draws.
        for (int x = 0; x < SLOTS; x++) {
            addSlot(new Slot(shown, x, 8 + x * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
        for (int x = 0; x < SLOTS; x++) {
            int storage = x; // not "index": inside a Slot that name means its place in the whole screen
            addSlot(new Slot(input, storage, 8 + x * 18, 36) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return input.canPlaceItem(storage, stack);
                }
            });
        }
        addStandardInventorySlots(inventory, 8, 18 + 2 * 18 + 13);
    }

    private void showFilters() {
        for (int slot = 0; slot < SLOTS; slot++) {
            Item filter = input.filter(slot);
            boolean same = filter == null ? shown.getItem(slot).isEmpty() : shown.getItem(slot).is(filter);
            if (!same) {
                shown.setItem(slot, filter == null ? ItemStack.EMPTY : new ItemStack(filter));
            }
        }
    }

    @Override
    public void clicked(int slotIndex, int button, ContainerInput kind, Player player) {
        if (slotIndex >= 0 && slotIndex < SLOTS) {
            // A filter slot: it remembers what was shown to it and takes nothing.
            ItemStack held = getCarried();
            input.setFilter(slotIndex, held.isEmpty() ? null : held.getItem());
            showFilters();
            return;
        }
        super.clicked(slotIndex, button, kind, player);
    }

    @Override
    public void broadcastChanges() {
        showFilters(); // a program may have changed a filter while the screen is open
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return input.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (slotIndex >= SLOTS && slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();
            if (slotIndex < 2 * SLOTS) {
                if (!moveItemStackTo(stack, 2 * SLOTS, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, SLOTS, 2 * SLOTS, false)) {
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
        input.stopOpen(player);
    }
}
