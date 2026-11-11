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

/**
 * The Energy Interface's screen: a gauge of what it stores between two battery slots, one
 * charging what is put in it from the store and one draining it into the store; then the
 * player's inventory. {@code qbotics.client.EnergyInterfaceScreen} draws it.
 */
public class EnergyInterfaceMenu extends AbstractContainerMenu {
    /** Where things are on the screen, measured from its top-left corner. */
    public static final int CHARGE_X = 22;
    public static final int DRAIN_X = 136;
    public static final int SLOT_Y = 48;
    public static final int GAUGE_X = 62;
    public static final int GAUGE_Y = 20;
    public static final int GAUGE_WIDTH = 52;
    public static final int GAUGE_HEIGHT = 56;
    /** The height of the part above the player's inventory. */
    public static final int TOP_HEIGHT = 96;

    private final @Nullable EnergyInterfaceBlockEntity store;
    private final Container batteries;
    /** What it stores, in two halves: the screen's numbers travel as 16 bits each. */
    private final ContainerData data;

    /** On the server. */
    public EnergyInterfaceMenu(int containerId, Inventory inventory, EnergyInterfaceBlockEntity store) {
        this(containerId, inventory, store, store.batteries(), new ContainerData() {
            @Override
            public int get(int index) {
                int stored = (int) store.stored();
                return index == 0 ? stored & 0xFFFF : (stored >>> 16) & 0xFFFF;
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 2;
            }
        });
    }

    /** In the player's game. */
    public EnergyInterfaceMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(2), new SimpleContainerData(2));
    }

    private EnergyInterfaceMenu(int containerId, Inventory inventory, @Nullable EnergyInterfaceBlockEntity store, Container batteries,
            ContainerData data) {
        super(QboticsContent.ENERGY_INTERFACE_MENU.get(), containerId);
        this.store = store;
        this.batteries = batteries;
        this.data = data;
        batteries.startOpen(inventory.player);
        addSlot(batterySlot(batteries, EnergyInterfaceBlockEntity.CHARGE_SLOT, CHARGE_X));
        addSlot(batterySlot(batteries, EnergyInterfaceBlockEntity.DRAIN_SLOT, DRAIN_X));
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
        addDataSlots(data);
    }

    private static Slot batterySlot(Container batteries, int index, int x) {
        return new Slot(batteries, index, x + 1, SLOT_Y + 1) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }

    /** What it stores, in the player's game. */
    public long stored() {
        return (data.get(0) & 0xFFFFL) | ((data.get(1) & 0xFFFFL) << 16);
    }

    public long capacity() {
        return EnergyInterfaceBlockEntity.CAPACITY;
    }

    @Override
    public boolean stillValid(Player player) {
        return batteries.stillValid(player) && (store == null || !store.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (slotIndex < 2) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, 2, false)) {
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
        batteries.stopOpen(player);
    }
}
