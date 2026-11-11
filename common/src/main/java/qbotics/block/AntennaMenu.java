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
 * An antenna's screen: its upgrade slots, the switch for its Chunk Loader Upgrade, and what
 * that is doing. {@code qbotics.client.AntennaScreen} draws it.
 */
public class AntennaMenu extends AbstractContainerMenu {
    public static final int SLOTS = AntennaBlockEntity.UPGRADE_SLOTS;
    /** The button that switches chunk loading on and off. */
    public static final int TOGGLE_BUTTON = 0;
    /** Where things are on the screen, measured from its top-left corner. */
    public static final int SLOT_Y = 20;
    public static final int TOP_HEIGHT = 80;

    public static int slotX(int slot) {
        return 10 + slot * 22;
    }

    /** What the screen shows: chunk loading on (1) or off, its status, and chunks held and wanted. */
    private final ContainerData data;
    private final @Nullable AntennaBlockEntity antenna;
    private final Container upgrades;

    /** On the server. */
    public AntennaMenu(int containerId, Inventory inventory, AntennaBlockEntity antenna) {
        this(containerId, inventory, antenna, antenna.upgrades(), new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> antenna.chunkLoading() ? 1 : 0;
                    case 1 -> antenna.loaderStatus();
                    case 2 -> antenna.loadedChunks();
                    default -> antenna.wantedChunks();
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
    public AntennaMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(SLOTS), new SimpleContainerData(4));
    }

    private AntennaMenu(int containerId, Inventory inventory, @Nullable AntennaBlockEntity antenna, Container upgrades, ContainerData data) {
        super(QboticsContent.ANTENNA_MENU.get(), containerId);
        this.antenna = antenna;
        this.upgrades = upgrades;
        this.data = data;
        upgrades.startOpen(inventory.player);
        for (int slot = 0; slot < SLOTS; slot++) {
            addSlot(new Slot(upgrades, slot, slotX(slot) + 1, SLOT_Y + 1) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return container.canPlaceItem(getContainerSlot(), stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
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
        if (id == TOGGLE_BUTTON && antenna != null) {
            antenna.setChunkLoading(!antenna.chunkLoading());
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return upgrades.stillValid(player) && (antenna == null || !antenna.isRemoved());
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
        } else {
            boolean moved = false;
            for (int target = 0; target < SLOTS && !moved; target++) {
                if (!slots.get(target).hasItem() && slots.get(target).mayPlace(stack) && stack.getItem() instanceof UpgradeItem) {
                    moved = moveItemStackTo(stack, target, target + 1, false);
                }
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
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
        upgrades.stopOpen(player);
    }
}
