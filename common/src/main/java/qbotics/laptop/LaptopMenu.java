package qbotics.laptop;

import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import qbotics.QboticsContent;
import qbotics.block.HardDriveItem;

/**
 * A laptop's two hard-drive slots (sneak and right-click it in the air). The drives are kept
 * in the laptop item; on the laptop computer they appear at /net/disks/disk1 and disk2.
 */
public class LaptopMenu extends AbstractContainerMenu {
    public static final int SLOT_Y = 20;
    public static final int TOP_HEIGHT = 48;

    /** The x of a disk slot. */
    public static int slotX(int slot) {
        return 62 + slot * 36;
    }

    private final SimpleContainer disks = new SimpleContainer(Laptops.DISK_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            save();
        }
    };
    private final ItemStack laptop;

    /** On the server: the laptop in the player's hand. */
    public LaptopMenu(int containerId, Inventory inventory, InteractionHand hand) {
        this(containerId, inventory, inventory.player.getItemInHand(hand), hand == InteractionHand.MAIN_HAND ? inventory.getSelectedSlot() : -1);
    }

    /** In the player's game. */
    public LaptopMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, ItemStack.EMPTY, -1);
    }

    private LaptopMenu(int containerId, Inventory inventory, ItemStack laptop, int lockedSlot) {
        super(QboticsContent.LAPTOP_MENU.get(), containerId);
        this.laptop = laptop;
        if (!laptop.isEmpty()) {
            NonNullList<ItemStack> held = NonNullList.withSize(Laptops.DISK_SLOTS, ItemStack.EMPTY);
            Laptops.disks(laptop).copyInto(held);
            for (int i = 0; i < held.size(); i++) {
                disks.setItem(i, held.get(i));
            }
        }
        for (int slot = 0; slot < Laptops.DISK_SLOTS; slot++) {
            addSlot(new Slot(disks, slot, slotX(slot), SLOT_Y) {
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
        this.lockedSlot = lockedSlot;
        for (Slot slot : slots) {
            if (lockedSlot >= 0 && slot.container == inventory && slot.getContainerSlot() == lockedSlot) {
                lockedMenuSlot = slot.index;
            }
        }
    }

    /** The laptop's own place in the player's inventory, and in this menu: it can't be moved meanwhile. */
    private final int lockedSlot;
    private int lockedMenuSlot = -1;

    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
        if ((lockedMenuSlot >= 0 && slotId == lockedMenuSlot)
                || (lockedSlot >= 0 && input == net.minecraft.world.inventory.ContainerInput.SWAP && button == lockedSlot)) {
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    /** Writes the drives back into the laptop; a new drive gets the ID its files are kept under. */
    private void save() {
        if (laptop == null || laptop.isEmpty()) {
            return;
        }
        NonNullList<ItemStack> now = NonNullList.withSize(Laptops.DISK_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < Laptops.DISK_SLOTS; i++) {
            ItemStack stack = disks.getItem(i);
            if (stack.getItem() instanceof HardDriveItem && stack.get(QboticsContent.MEDIA_ID.get()) == null) {
                stack.set(QboticsContent.MEDIA_ID.get(), UUID.randomUUID());
            }
            now.set(i, stack.copy());
        }
        laptop.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(now));
    }

    @Override
    public boolean stillValid(Player player) {
        return laptop.isEmpty() || Laptops.carriedStack(player, laptop);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();
        if (index < Laptops.DISK_SLOTS) {
            if (!moveItemStackTo(stack, Laptops.DISK_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!(stack.getItem() instanceof HardDriveItem) || !moveItemStackTo(stack, 0, Laptops.DISK_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return moved;
    }
}
