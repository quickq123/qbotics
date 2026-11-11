package qbotics.block;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.FluidPort;
import qbotics.network.Medium;

/**
 * The Fluid Interface: the network's fluid store (design doc §11.0). Four small tanks of
 * four buckets each. A tank takes any fluid while it is empty, and after that only more of
 * the same. Other mods' pipes can fill and drain it.
 */
public class FluidInterfaceBlockEntity extends DeviceBlockEntity implements FluidPort, StorageSnapshots {
    public static final int TANKS = 4;
    public static final int TANK_CAPACITY = 4 * BUCKET;

    private final Fluid[] fluids = new Fluid[TANKS];
    private final int[] amounts = new int[TANKS];

    public FluidInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.FLUID_INTERFACE_BLOCK_ENTITY.get(), pos, state);
        java.util.Arrays.fill(fluids, Fluids.EMPTY);
    }

    @Override
    public Kind kind() {
        return Kind.INTERFACE;
    }

    @Override
    public Medium medium() {
        return Medium.FLUID;
    }

    @Override
    public List<Tank> tanks() {
        List<Tank> tanks = new ArrayList<>(TANKS);
        for (int i = 0; i < TANKS; i++) {
            tanks.add(new Tank(amounts[i] == 0 ? Fluids.EMPTY : fluids[i], amounts[i], TANK_CAPACITY));
        }
        return tanks;
    }

    @Override
    public int insert(Fluid fluid, int max, boolean simulate) {
        if (fluid == Fluids.EMPTY || max <= 0) {
            return 0;
        }
        int left = max;
        // Top up tanks that already hold it before starting an empty one.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < TANKS && left > 0; i++) {
                boolean wanted = pass == 0 ? amounts[i] > 0 && fluids[i] == fluid : amounts[i] == 0;
                int room = wanted ? Math.min(left, TANK_CAPACITY - amounts[i]) : 0;
                if (room > 0) {
                    if (!simulate) {
                        fluids[i] = fluid;
                        amounts[i] += room;
                    }
                    left -= room;
                }
            }
        }
        if (!simulate && left < max) {
            setChanged();
        }
        return max - left;
    }

    @Override
    public int extract(Fluid fluid, int max, boolean simulate) {
        if (fluid == Fluids.EMPTY || max <= 0) {
            return 0;
        }
        int left = max;
        for (int i = 0; i < TANKS && left > 0; i++) {
            if (amounts[i] > 0 && fluids[i] == fluid) {
                int take = Math.min(left, amounts[i]);
                if (!simulate) {
                    amounts[i] -= take;
                    if (amounts[i] == 0) {
                        fluids[i] = Fluids.EMPTY;
                    }
                }
                left -= take;
            }
        }
        if (!simulate && left < max) {
            setChanged();
        }
        return max - left;
    }

    // ---- for the loader's fluid API, which needs to undo changes -------------------------

    private record Snapshot(Fluid[] fluids, int[] amounts) {
    }

    @Override
    public Object takeSnapshot() {
        return new Snapshot(fluids.clone(), amounts.clone());
    }

    @Override
    public void restoreSnapshot(Object snapshot) {
        Snapshot saved = (Snapshot) snapshot;
        System.arraycopy(saved.fluids(), 0, fluids, 0, TANKS);
        System.arraycopy(saved.amounts(), 0, amounts, 0, TANKS);
    }

    // ---- saving ----------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        List<String> names = new ArrayList<>(TANKS);
        List<Integer> held = new ArrayList<>(TANKS);
        for (int i = 0; i < TANKS; i++) {
            names.add(amounts[i] == 0 ? "" : BuiltInRegistries.FLUID.getKey(fluids[i]).toString());
            held.add(amounts[i]);
        }
        output.store("fluids", Codec.STRING.listOf(), names);
        output.store("amounts", Codec.INT.listOf(), held);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        List<String> names = input.read("fluids", Codec.STRING.listOf()).orElse(List.of());
        List<Integer> held = input.read("amounts", Codec.INT.listOf()).orElse(List.of());
        for (int i = 0; i < TANKS; i++) {
            Identifier id = i < names.size() && !names.get(i).isEmpty() ? Identifier.tryParse(names.get(i)) : null;
            Fluid fluid = id != null && BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.getValue(id) : Fluids.EMPTY;
            int amount = i < held.size() ? Math.clamp(held.get(i), 0, TANK_CAPACITY) : 0;
            fluids[i] = fluid == Fluids.EMPTY || amount == 0 ? Fluids.EMPTY : fluid;
            amounts[i] = fluids[i] == Fluids.EMPTY ? 0 : amount;
        }
    }
}
