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
 */
public class EnergyInterfaceBlockEntity extends DeviceBlockEntity implements EnergyPort, StorageSnapshots {
    public static final int CAPACITY = 2_000_000;

    private int energy;

    public EnergyInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ENERGY_INTERFACE_BLOCK_ENTITY.get(), pos, state);
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
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy = Math.clamp(input.getIntOr("energy", 0), 0, CAPACITY);
    }
}
