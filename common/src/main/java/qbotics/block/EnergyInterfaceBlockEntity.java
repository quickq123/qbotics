package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.EnergyPort;
import qbotics.network.Medium;

/**
 * The Energy Interface: the network's energy store (design doc §11.0). It holds two million
 * units of the energy mods share (FE, also called RF). Other mods' cables can charge and
 * drain it.
 *
 * <p>On its screen ({@link EnergyInterfaceMenu}) it has two battery slots: one charges
 * whatever energy-holding item is put in it from the store, the other drains one into the
 * store. Both work a little every tick, as fast as the item takes or gives.
 */
public class EnergyInterfaceBlockEntity extends DeviceBlockEntity implements EnergyPort, StorageSnapshots,
        net.minecraft.world.MenuProvider {
    public static final int CAPACITY = 2_000_000;
    /** The battery slots: this one is charged from the store... */
    public static final int CHARGE_SLOT = 0;
    /** ...and this one drains into it. */
    public static final int DRAIN_SLOT = 1;
    /** The most moved to or from a battery in one tick. */
    public static final int PER_TICK = 50_000;

    private int energy;
    private final BatterySlots batteries = new BatterySlots();

    public EnergyInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ENERGY_INTERFACE_BLOCK_ENTITY.get(), pos, state);
    }

    /** The battery slots, for the screen and for hoppers and item pipes. */
    public net.minecraft.world.WorldlyContainer batteries() {
        return batteries;
    }

    /**
     * The battery slots, which hoppers and item pipes reach from any face. A battery that can
     * take more charge goes in the charge slot; a full one goes in the drain slot. Each comes
     * out again only when it is done: charged full, or drained empty. Something that holds no
     * energy is never put in by them, and comes straight out if a player put it there.
     */
    private final class BatterySlots extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
        private static final int[] BOTH = {CHARGE_SLOT, DRAIN_SLOT};

        BatterySlots() {
            super(2);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            EnergyInterfaceBlockEntity.this.setChanged();
        }

        @Override
        public int[] getSlotsForFace(net.minecraft.core.Direction side) {
            return BOTH;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.@org.jspecify.annotations.Nullable Direction side) {
            if (!getItem(slot).isEmpty()) {
                return false;
            }
            boolean chargeable = qbotics.Qbotics.platform().canCharge(stack);
            return slot == CHARGE_SLOT ? chargeable : !chargeable && qbotics.Qbotics.platform().canDischarge(stack);
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.Direction side) {
            return slot == CHARGE_SLOT ? !qbotics.Qbotics.platform().canCharge(stack) : !qbotics.Qbotics.platform().canDischarge(stack);
        }
    }

    /** Once a tick on the server: charges and drains what is in the battery slots. */
    public void serverTick() {
        if (!batteries.getItem(CHARGE_SLOT).isEmpty() && energy > 0) {
            qbotics.Qbotics.platform().exchangeEnergy(batteries, CHARGE_SLOT, this, true, PER_TICK);
        }
        if (!batteries.getItem(DRAIN_SLOT).isEmpty() && energy < CAPACITY) {
            qbotics.Qbotics.platform().exchangeEnergy(batteries, DRAIN_SLOT, this, false, PER_TICK);
        }
    }

    /** Breaking the block drops what is in its battery slots. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            net.minecraft.world.Containers.dropContents(level, pos, batteries);
        }
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.qbotics.energy_interface");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new EnergyInterfaceMenu(id, inventory, this);
    }

    @Override
    public Kind kind() {
        return Kind.INTERFACE;
    }

    @Override
    public Medium medium() {
        return Medium.ENERGY;
    }

    @Override
    public long stored() {
        return energy;
    }

    @Override
    public long capacity() {
        return CAPACITY;
    }

    @Override
    public int insert(int max, boolean simulate) {
        int moved = Math.clamp(max, 0, CAPACITY - energy);
        if (!simulate && moved > 0) {
            energy += moved;
            setChanged();
        }
        return moved;
    }

    @Override
    public int extract(int max, boolean simulate) {
        int moved = Math.clamp(max, 0, energy);
        if (!simulate && moved > 0) {
            energy -= moved;
            setChanged();
        }
        return moved;
    }

    @Override
    public Object takeSnapshot() {
        return energy;
    }

    @Override
    public void restoreSnapshot(Object snapshot) {
        energy = (Integer) snapshot;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy);
        output.store("batteries", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf(),
                java.util.List.of(batteries.getItem(0), batteries.getItem(1)));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy = Math.clamp(input.getIntOr("energy", 0), 0, CAPACITY);
        java.util.List<net.minecraft.world.item.ItemStack> held = input.read("batteries",
                net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf()).orElse(java.util.List.of());
        for (int slot = 0; slot < 2; slot++) {
            batteries.setItem(slot, slot < held.size() ? held.get(slot) : net.minecraft.world.item.ItemStack.EMPTY);
        }
    }
}
