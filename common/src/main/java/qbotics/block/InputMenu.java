package qbotics.block;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;

/**
 * The Input block's screen: a row of nine filter sockets above the row of nine slots they
 * belong to, above the player's inventory. {@code qbotics.client.InputScreen} draws it.
 *
 * <p>The top row holds no items. Clicking one of its sockets with an item in hand sets the
 * filter of the slot below to that item (the item stays in hand); clicking with an empty
 * hand closes the slot again. The bottom row takes only what its filter names; taking items
 * out is always allowed.
 *
 * <p>The same class runs on the server, where it works on the real block, and in the
 * player's game, where it works on a copy the server keeps up to date.
 */
public class InputMenu extends AbstractContainerMenu {
    public static final int SLOTS = InputBlockEntity.SLOTS;

    /** The real block on the server; null in the player's game. */
    private final @Nullable InputBlockEntity input;
    private final Container items;
    /** One of each filter's item, so there is something to draw in the top row. */
    private final SimpleContainer shown = new SimpleContainer(SLOTS);

    /** On the server. */
    public InputMenu(int containerId, Inventory inventory, InputBlockEntity input) {
        this(containerId, inventory, input, input);
    }

    /** In the player's game. */
    public InputMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(SLOTS), null);
    }

    private InputMenu(int containerId, Inventory inventory, Container items, @Nullable InputBlockEntity input) {
        super(QboticsContent.INPUT_MENU.get(), containerId);
        this.input = input;
        this.items = items;
        items.startOpen(inventory.player);
        showFilters();
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
            addSlot(new Slot(items, storage, 8 + x * 18, 36) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // The player's game judges by the filter it was shown, so its guess matches the server.
                    return input != null ? input.canPlaceItem(storage, stack) : shown.getItem(storage).is(stack.getItem());
                }
            });
        }
        addStandardInventorySlots(inventory, 8, 18 + 2 * 18 + 13);
    }

    /** True for the top row's slots, which the screen draws as sockets. */
    public static boolean isFilterSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < SLOTS;
    }

    private void showFilters() {
        if (input == null) {
            return;
        }
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
        if (isFilterSlot(slotIndex)) {
            // A socket remembers what was shown to it and takes nothing.
            ItemStack held = getCarried();
            if (input != null) {
                input.setFilter(slotIndex, held.isEmpty() ? null : held.getItem());
                showFilters();
            } else {
                shown.setItem(slotIndex, held.isEmpty() ? ItemStack.EMPTY : held.copyWithCount(1));
            }
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
        return items.stillValid(player);
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
        items.stopOpen(player);
    }
}
