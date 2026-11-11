package qbotics.block;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.computer.ComputerManager;

/**
 * The Programmer's screen: its hard drive's socket, and an "Edit files" button (button 0)
 * that opens the drive in the editor, without a terminal. {@code qbotics.client.ProgrammerScreen} draws it.
 */
public class ProgrammerMenu extends AbstractContainerMenu {
    /** Where things are on the screen, measured from its top-left corner. */
    public static final int SLOT_X = 25;
    public static final int SLOT_Y = 26;
    public static final int TOP_HEIGHT = 76;
    /** The button that opens the drive in the editor. */
    public static final int EDIT_BUTTON = 0;

    private final @Nullable ProgrammerBlockEntity programmer;
    private final Container drive;

    /** On the server. */
    public ProgrammerMenu(int containerId, Inventory inventory, ProgrammerBlockEntity programmer) {
        this(containerId, inventory, programmer, programmer.driveSlot());
    }

    /** In the player's game. */
    public ProgrammerMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(1));
    }

    private ProgrammerMenu(int containerId, Inventory inventory, @Nullable ProgrammerBlockEntity programmer, Container drive) {
        super(QboticsContent.PROGRAMMER_MENU.get(), containerId);
        this.programmer = programmer;
        this.drive = drive;
        drive.startOpen(inventory.player);
        addSlot(new Slot(drive, 0, SLOT_X + 1, SLOT_Y + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof HardDriveItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
    }

    /** "Edit files": the drive opens in the editor, for this player. */
    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button == EDIT_BUTTON && programmer != null && player instanceof ServerPlayer serverPlayer) {
            if (programmer.mediaId() == null) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Put a hard drive in it first."));
                return false;
            }
            ComputerManager.get(serverPlayer.level().getServer()).openDriveEditor(serverPlayer, programmer);
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return drive.stillValid(player) && (programmer == null || !programmer.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (slotIndex == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!(stack.getItem() instanceof HardDriveItem) || !moveItemStackTo(stack, 0, 1, false)) {
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
        drive.stopOpen(player);
    }
}
