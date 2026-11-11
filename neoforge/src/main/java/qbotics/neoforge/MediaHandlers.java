package qbotics.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import qbotics.block.EnergyInterfaceBlockEntity;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.block.StorageSnapshots;
import qbotics.network.EnergyPort;
import qbotics.network.FluidPort;

/**
 * The joins between this mod's fluid and energy handling and NeoForge's standard transfer
 * API, in both directions: other mods' tanks and machines as seen by our plates, and our
 * Fluid Interface and Energy Interface as seen by other mods' pipes and cables.
 */
final class MediaHandlers {
    private MediaHandlers() {
    }

    // ---- other mods' blocks, as our plates see them --------------------------------------

    /** A tank reached through NeoForge's fluid capability. */
    record FluidTarget(ResourceHandler<FluidResource> handler) implements FluidPort {
        @Override
        public List<Tank> tanks() {
            List<Tank> tanks = new ArrayList<>(handler.size());
            for (int i = 0; i < handler.size(); i++) {
                FluidResource resource = handler.getResource(i);
                int amount = handler.getAmountAsInt(i);
                boolean empty = resource.isEmpty() || amount <= 0;
                tanks.add(new Tank(empty ? Fluids.EMPTY : resource.getFluid(), empty ? 0 : amount, handler.getCapacityAsInt(i, resource)));
            }
            return tanks;
        }

        @Override
        public int insert(Fluid fluid, int max, boolean simulate) {
            if (fluid == Fluids.EMPTY || max <= 0) {
                return 0;
            }
            // A transaction that is closed without being committed changes nothing: that is the trial run.
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = handler.insert(FluidResource.of(fluid), max, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return inserted;
            }
        }

        @Override
        public int extract(Fluid fluid, int max, boolean simulate) {
            if (fluid == Fluids.EMPTY || max <= 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int extracted = handler.extract(FluidResource.of(fluid), max, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return extracted;
            }
        }
    }

    /** A machine or battery reached through NeoForge's energy capability. */
    record EnergyTarget(EnergyHandler handler) implements EnergyPort {
        @Override
        public long stored() {
            return handler.getAmountAsLong();
        }

        @Override
        public long capacity() {
            return handler.getCapacityAsLong();
        }

        @Override
        public int insert(int max, boolean simulate) {
            if (max <= 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int inserted = handler.insert(max, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return inserted;
            }
        }

        @Override
        public int extract(int max, boolean simulate) {
            if (max <= 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int extracted = handler.extract(max, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return extracted;
            }
        }
    }

    // ---- our stores, as other mods see them -----------------------------------------------

    /**
     * Remembers what a store held before a transfer touched it, so the transfer can be
     * taken back. One per block, kept for as long as the block is: two of them working on
     * the same block inside one transfer could undo each other's work in the wrong order.
     */
    private abstract static class Journal extends SnapshotJournal<Object> {
        final StorageSnapshots store;

        Journal(StorageSnapshots store) {
            this.store = store;
        }

        @Override
        protected Object createSnapshot() {
            return store.takeSnapshot();
        }

        @Override
        protected void revertToSnapshot(Object snapshot) {
            store.restoreSnapshot(snapshot);
        }

        @Override
        protected void onRootCommit(Object originalState) {
            store.setChanged();
        }
    }

    private static final Map<BlockEntity, Object> HANDLERS = new WeakHashMap<>();

    static ResourceHandler<FluidResource> of(FluidInterfaceBlockEntity store) {
        synchronized (HANDLERS) {
            return (FluidStore) HANDLERS.computeIfAbsent(store, s -> new FluidStore(store));
        }
    }

    static EnergyHandler of(EnergyInterfaceBlockEntity store) {
        synchronized (HANDLERS) {
            return (EnergyStore) HANDLERS.computeIfAbsent(store, s -> new EnergyStore(store));
        }
    }

    /** The Fluid Interface's four tanks. A fluid goes to whichever tank will take it, whatever tank number is asked for. */
    private static final class FluidStore extends Journal implements ResourceHandler<FluidResource> {
        private final FluidInterfaceBlockEntity tanks;

        FluidStore(FluidInterfaceBlockEntity tanks) {
            super(tanks);
            this.tanks = tanks;
        }

        @Override
        public int size() {
            return FluidInterfaceBlockEntity.TANKS;
        }

        @Override
        public FluidResource getResource(int index) {
            FluidPort.Tank tank = tanks.tanks().get(index);
            return tank.amount() == 0 ? FluidResource.EMPTY : FluidResource.of(tank.fluid());
        }

        @Override
        public long getAmountAsLong(int index) {
            return tanks.tanks().get(index).amount();
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return FluidInterfaceBlockEntity.TANK_CAPACITY;
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return !resource.isEmpty() && resource.isComponentsPatchEmpty(); // plain fluids only
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            if (!isValid(index, resource) || tanks.insert(resource.getFluid(), amount, true) == 0) {
                return 0;
            }
            updateSnapshots(transaction);
            return tanks.insert(resource.getFluid(), amount, false);
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            if (!isValid(index, resource) || tanks.extract(resource.getFluid(), amount, true) == 0) {
                return 0;
            }
            updateSnapshots(transaction);
            return tanks.extract(resource.getFluid(), amount, false);
        }
    }

    private static final class EnergyStore extends Journal implements EnergyHandler {
        private final EnergyInterfaceBlockEntity cell;

        EnergyStore(EnergyInterfaceBlockEntity cell) {
            super(cell);
            this.cell = cell;
        }

        @Override
        public long getAmountAsLong() {
            return cell.stored();
        }

        @Override
        public long getCapacityAsLong() {
            return cell.capacity();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            if (cell.insert(amount, true) == 0) {
                return 0;
            }
            updateSnapshots(transaction);
            return cell.insert(amount, false);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            if (cell.extract(amount, true) == 0) {
                return 0;
            }
            updateSnapshots(transaction);
            return cell.extract(amount, false);
        }
    }
}
