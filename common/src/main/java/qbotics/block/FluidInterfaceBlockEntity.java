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
 *
 * <p>Each tank can have a filter: one fluid, and then it takes nothing else, from players,
 * pipes and programs alike. A tank with no filter takes any fluid. On its screen
 * ({@link FluidInterfaceMenu}) each tank also has a bucket slot: a full container put there
 * empties into the tank, an empty one fills from it.
 */
public class FluidInterfaceBlockEntity extends DeviceBlockEntity implements FluidPort, StorageSnapshots,
        net.minecraft.world.MenuProvider {
    public static final int TANKS = 4;
    public static final int TANK_CAPACITY = 4 * BUCKET;

    private final Fluid[] fluids = new Fluid[TANKS];
    private final int[] amounts = new int[TANKS];
    /** The one fluid each tank takes, or EMPTY where any fluid will do. */
    private final Fluid[] filters = new Fluid[TANKS];
    /** One bucket slot under each tank, on the screen. */
    private final net.minecraft.world.SimpleContainer buckets = new net.minecraft.world.SimpleContainer(TANKS) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            bucketsChanged();
        }
    };
    /** The item each slot held after it was last dealt with, so each is dealt with once. */
    private final net.minecraft.world.item.ItemStack[] handled = new net.minecraft.world.item.ItemStack[TANKS];
    /** True while this block itself changes a bucket slot, so that change isn't taken as a new request. */
    private boolean exchanging;

    public FluidInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.FLUID_INTERFACE_BLOCK_ENTITY.get(), pos, state);
        java.util.Arrays.fill(fluids, Fluids.EMPTY);
        java.util.Arrays.fill(filters, Fluids.EMPTY);
    }

    // ---- filters ---------------------------------------------------------------------------

    /** The fluid a tank is kept for, or EMPTY if it takes any. */
    public Fluid filter(int tank) {
        return filters[tank];
    }

    /** Keeps a tank for one fluid; EMPTY (or null) lets it take any again. What it already holds stays. */
    public void setFilter(int tank, @org.jspecify.annotations.Nullable Fluid fluid) {
        Fluid wanted = fluid == null ? Fluids.EMPTY : fluid;
        if (filters[tank] != wanted) {
            filters[tank] = wanted;
            setChanged();
        }
    }

    /** True if a tank may take this fluid: its filter allows it, and it is empty or holds the same. */
    private boolean takes(int tank, Fluid fluid) {
        return (filters[tank] == Fluids.EMPTY || filters[tank] == fluid) && (amounts[tank] == 0 || fluids[tank] == fluid);
    }

    // ---- bucket slots ------------------------------------------------------------------------

    /** The bucket slots, for the screen. */
    public net.minecraft.world.Container buckets() {
        return buckets;
    }

    /**
     * Something was put in a bucket slot: a container with fluid in it empties into that
     * slot's tank; an empty one fills from it. What comes back (an empty bucket, a full one)
     * stays in the slot to be taken out.
     */
    private void bucketsChanged() {
        setChanged();
        if (exchanging || level == null || level.isClientSide()) {
            return;
        }
        exchanging = true;
        try {
            for (int slot = 0; slot < TANKS; slot++) {
                net.minecraft.world.item.ItemStack held = buckets.getItem(slot);
                if (!held.isEmpty() && held != handled[slot]) {
                    qbotics.Qbotics.platform().exchangeFluid(buckets, slot, this, slot);
                }
                handled[slot] = buckets.getItem(slot);
            }
        } finally {
            exchanging = false;
        }
    }

    /** Breaking the block drops what is in its bucket slots. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            net.minecraft.world.Containers.dropContents(level, pos, buckets);
        }
    }

    // ---- the screen ----------------------------------------------------------------------------

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.qbotics.fluid_interface");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new FluidInterfaceMenu(id, inventory, this);
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
                boolean wanted = takes(i, fluid) && (pass == 0 ? amounts[i] > 0 : amounts[i] == 0);
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

    @Override
    public int insertInto(int tank, Fluid fluid, int max, boolean simulate) {
        if (fluid == Fluids.EMPTY || max <= 0 || !takes(tank, fluid)) {
            return 0;
        }
        int room = Math.min(max, TANK_CAPACITY - amounts[tank]);
        if (!simulate && room > 0) {
            fluids[tank] = fluid;
            amounts[tank] += room;
            setChanged();
        }
        return room;
    }

    @Override
    public int extractFrom(int tank, Fluid fluid, int max, boolean simulate) {
        if (fluid == Fluids.EMPTY || max <= 0 || amounts[tank] == 0 || fluids[tank] != fluid) {
            return 0;
        }
        int take = Math.min(max, amounts[tank]);
        if (!simulate) {
            amounts[tank] -= take;
            if (amounts[tank] == 0) {
                fluids[tank] = Fluids.EMPTY;
            }
            setChanged();
        }
        return take;
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
        List<String> kept = new ArrayList<>(TANKS);
        for (Fluid filter : filters) {
            kept.add(filter == Fluids.EMPTY ? "" : BuiltInRegistries.FLUID.getKey(filter).toString());
        }
        output.store("filters", Codec.STRING.listOf(), kept);
        List<net.minecraft.world.item.ItemStack> inSlots = new ArrayList<>(TANKS);
        for (int slot = 0; slot < TANKS; slot++) {
            inSlots.add(buckets.getItem(slot));
        }
        output.store("buckets", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf(), inSlots);
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
        List<String> kept = input.read("filters", Codec.STRING.listOf()).orElse(List.of());
        for (int i = 0; i < TANKS; i++) {
            Identifier id = i < kept.size() && !kept.get(i).isEmpty() ? Identifier.tryParse(kept.get(i)) : null;
            filters[i] = id != null && BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.getValue(id) : Fluids.EMPTY;
        }
        List<net.minecraft.world.item.ItemStack> inSlots = input.read("buckets", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf())
                .orElse(List.of());
        exchanging = true; // what was saved in a slot was dealt with before
        for (int slot = 0; slot < TANKS; slot++) {
            buckets.setItem(slot, slot < inSlots.size() ? inSlots.get(slot) : net.minecraft.world.item.ItemStack.EMPTY);
            handled[slot] = buckets.getItem(slot);
        }
        exchanging = false;
    }
}
