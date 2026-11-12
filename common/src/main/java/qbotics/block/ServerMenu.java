package qbotics.block;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.upgrade.UpgradeItem;

/**
 * A Server's hardware (sneak + right-click it): four hard-drive sockets, a slot for a Chunk
 * Loader Upgrade, its switch, and what chunk loading is doing. {@code qbotics.client.ServerScreen}
 * draws it.
 */
public class ServerMenu extends AbstractContainerMenu {
    public static final int DRIVES = ServerBlockEntity.SLOTS;
    /** Slot number of the upgrade, after the drives. */
    public static final int UPGRADE = DRIVES;
    public static final int TOGGLE_BUTTON = 0;
    public static final int SLOT_Y = 20;
    public static final int UPGRADE_Y = 56;
    public static final int TOP_HEIGHT = 108;

    public static int slotX(int slot) {
        return 10 + slot * 22;
    }

    /** Chunk loading on (1) or off, its status, chunks held and wanted. */
    private final ContainerData data;
    private final @Nullable ServerBlockEntity server;
    private final Container drives;
    private final Container upgrades;

    /** On the server. */
    public ServerMenu(int containerId, Inventory inventory, ServerBlockEntity server) {
        this(containerId, inventory, server, server.drives(), server.upgrades(), new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> server.chunkLoading() ? 1 : 0;
                    case 1 -> server.loaderStatus();
                    case 2 -> server.loadedChunks();
                    default -> server.wantedChunks();
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 4;
            }
        });
    }

    /** In the player's game. */
    public ServerMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(DRIVES), new SimpleContainer(1), new SimpleContainerData(4));
    }

    private ServerMenu(int containerId, Inventory inventory, @Nullable ServerBlockEntity server, Container drives, Container upgrades,
            ContainerData data) {
        super(QboticsContent.SERVER_MENU.get(), containerId);
        this.server = server;
        this.drives = drives;
        this.upgrades = upgrades;
        this.data = data;
        drives.startOpen(inventory.player);
        for (int slot = 0; slot < DRIVES; slot++) {
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
        addSlot(new Slot(upgrades, 0, slotX(0) + 1, UPGRADE_Y + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return UpgradeItem.kindOf(stack) == UpgradeItem.Kind.CHUNK_LOADER;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
        addDataSlots(data);
    }

    public boolean chunkLoading() {
        return data.get(0) != 0;
    }

    public int status() {
        return data.get(1);
    }

    public int loaded() {
        return data.get(2);
    }

    public int wanted() {
        return data.get(3);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == TOGGLE_BUTTON && server != null) {
            server.setChunkLoading(!server.chunkLoading());
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return drives.stillValid(player) && (server == null || !server.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        int own = DRIVES + 1;
        if (slotIndex < own) {
            if (!moveItemStackTo(stack, own, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof HardDriveItem) {
            if (!moveItemStackTo(stack, 0, DRIVES, false)) {
                return ItemStack.EMPTY;
            }
        } else if (UpgradeItem.kindOf(stack) == UpgradeItem.Kind.CHUNK_LOADER) {
            if (!moveItemStackTo(stack, UPGRADE, UPGRADE + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else {
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
