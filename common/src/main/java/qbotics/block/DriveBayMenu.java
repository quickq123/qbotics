package qbotics.block;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;

/**
 * The Drive Bay's screen: four sockets for hard drives above the player's inventory.
 * {@code qbotics.client.DriveBayScreen} draws it.
 */
public class DriveBayMenu extends AbstractContainerMenu {
    public static final int SLOTS = DriveBayBlockEntity.SLOTS;
    /** Where things are on the screen, measured from its top-left corner. */
    public static final int SLOT_Y = 24;
    public static final int TOP_HEIGHT = 66;

    public static int slotX(int slot) {
        return 43 + slot * 24;
    }

    /** The block holding the drives (a Drive Bay or a Server); null in the player's game. */
    private final net.minecraft.world.level.block.entity.@Nullable BlockEntity bay;
    private final Container drives;

    /** On the server. */
    public DriveBayMenu(int containerId, Inventory inventory, DriveBayBlockEntity bay) {
        this(containerId, inventory, bay, bay.drives(), bay);
    }

    /** On the server, for a Server's own drives. */
    public DriveBayMenu(int containerId, Inventory inventory, net.minecraft.world.level.block.entity.BlockEntity holder, Container drives) {
        this(containerId, inventory, (DriveBayBlockEntity) null, drives, holder);
    }

    /** In the player's game. */
    public DriveBayMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(SLOTS), null);
    }

    private DriveBayMenu(int containerId, Inventory inventory, @Nullable DriveBayBlockEntity unused, Container drives,
            net.minecraft.world.level.block.entity.@Nullable BlockEntity bay) {
        super(QboticsContent.DRIVE_BAY_MENU.get(), containerId);
        this.bay = bay;
        this.drives = drives;
        drives.startOpen(inventory.player);
        for (int slot = 0; slot < SLOTS; slot++) {
            addSlot(new Slot(drives, slot, slotX(slot) + 1, SLOT_Y + 1) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.getItem() instanceof HardDriveItem;
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
    }

    @Override
    public boolean stillValid(Player player) {
        return drives.stillValid(player) && (bay == null || !bay.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (slotIndex < SLOTS) {
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!(stack.getItem() instanceof HardDriveItem) || !moveItemStackTo(stack, 0, SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return clicked;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        drives.stopOpen(player);
    }
}
