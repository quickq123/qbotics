package qbotics.neoforge;

import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CablePort;
import qbotics.block.PortBlockEntity;
import qbotics.network.Medium;
import qbotics.network.Tunnels;
import qbotics.upgrade.TransportRate;

/**
 * What other mods' blocks see on the face of a bus that is one end of a tunnel (design doc
 * §11.0.6): the container behind the other end. Each handler looks the other end up on every
 * call, so a pipe that keeps hold of it never works on a container that has gone; when the
 * other end can't be reached (unloaded, out of range) it is simply empty and takes nothing.
 *
 * <p>Between networks every transfer counts against the antennas' Transport rate, and only
 * when it really happens: a pipe's trial run, which is rolled back, costs nothing.
 */
final class TunnelHandlers {
    private TunnelHandlers() {
    }

    /** How deep tunnels may lead into tunnels before the chain is cut: a loop would otherwise never end. */
    private static final int MAX_DEPTH = 4;
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** True if the bus on this side of the cable is a tunnel end that carries this medium. */
    private static boolean offers(PortBlockEntity host, @Nullable Direction side, Medium medium) {
        if (side == null) {
            return false;
        }
        CablePort end = host.port(side);
        Tunnels.Link link = end == null ? null : end.tunnel();
        if (link == null) {
            return false;
        }
        // What the far end carries is only known when it is loaded; offer by this end's own medium meanwhile.
        return end.medium() == medium || end.medium() == Medium.UNIVERSAL;
    }

    static @Nullable ResourceHandler<ItemResource> items(PortBlockEntity host, @Nullable Direction side) {
        return offers(host, side, Medium.ITEM) ? new Resources<>(host, side, Capabilities.Item.BLOCK, Medium.ITEM) : null;
    }

    static @Nullable ResourceHandler<FluidResource> fluid(PortBlockEntity host, @Nullable Direction side) {
        return offers(host, side, Medium.FLUID) ? new Resources<>(host, side, Capabilities.Fluid.BLOCK, Medium.FLUID) : null;
    }

    static @Nullable EnergyHandler energy(PortBlockEntity host, @Nullable Direction side) {
        return offers(host, side, Medium.ENERGY) ? new Energy(host, side) : null;
    }

    /** The handler behind the other end now, with the antennas a transfer crosses; null if there is none. */
    private record Target<H>(H handler, Set<AntennaBlockEntity> links, ServerLevel level, CablePort port) {
    }

    private static <H> @Nullable Target<H> target(PortBlockEntity host, Direction side, BlockCapability<H, @Nullable Direction> capability, Medium medium) {
        if (!(host.getLevel() instanceof ServerLevel level) || host.isRemoved()) {
            return null;
        }
        CablePort end = host.port(side);
        if (end == null || end.tunnel() == null) {
            return null;
        }
        Tunnels.Far far = Tunnels.far(level, end);
        if (far == null || !Tunnels.carried(end.medium(), far.port().medium()).contains(medium)) {
            return null;
        }
        CablePort other = far.port();
        Direction face = other.accessFace().resolve(other.pointing());
        if (!far.level().isLoaded(other.targetPos())) {
            return null; // asking would load that chunk, each time a pipe looked through the tunnel
        }
        H handler = far.level().getCapability(capability, other.targetPos(), face);
        return handler == null ? null : new Target<>(handler, far.links(), far.level(), other);
    }

    /**
     * What has crossed antennas in the transaction now open, so it is counted against the
     * Transport rate only if that transaction is committed in the end.
     */
    private static final class Crossing extends SnapshotJournal<Long> {
        private final Medium medium;
        private long pending;
        private Set<AntennaBlockEntity> links = Set.of();
        private long tick;

        Crossing(Medium medium) {
            this.medium = medium;
        }

        /** How much more may cross now. */
        long room(Set<AntennaBlockEntity> through, long now) {
            return through.isEmpty() ? Long.MAX_VALUE
                    : Math.max(0, TransportRate.room(through, medium, now) - pending);
        }

        void crossed(Set<AntennaBlockEntity> through, long now, long amount, TransactionContext transaction) {
            if (through.isEmpty() || amount <= 0) {
                return;
            }
            updateSnapshots(transaction);
            links = through;
            tick = now;
            pending += amount;
        }

        @Override
        protected Long createSnapshot() {
            return pending;
        }

        @Override
        protected void revertToSnapshot(Long snapshot) {
            pending = snapshot;
        }

        @Override
        protected void onRootCommit(Long originalState) {
            if (pending > 0) {
                TransportRate.spend(links, medium, pending, tick);
            }
            pending = 0;
        }
    }

    /** Items or fluid. */
    private static final class Resources<T extends Resource> implements ResourceHandler<T> {
        private final PortBlockEntity host;
        private final Direction side;
        private final BlockCapability<ResourceHandler<T>, @Nullable Direction> capability;
        private final Medium medium;
        private final Crossing crossing;

        Resources(PortBlockEntity host, Direction side, BlockCapability<ResourceHandler<T>, @Nullable Direction> capability, Medium medium) {
            this.host = host;
            this.side = side;
            this.capability = capability;
            this.medium = medium;
            this.crossing = new Crossing(medium);
        }

        private @Nullable Target<ResourceHandler<T>> target() {
            return DEPTH.get()[0] >= MAX_DEPTH ? null : TunnelHandlers.target(host, side, capability, medium);
        }

        @Override
        public int size() {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                return t == null ? 0 : t.handler().size();
            } finally {
                depth[0]--;
            }
        }

        @Override
        public T getResource(int index) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                if (t == null || index >= t.handler().size()) {
                    return emptyLike(t);
                }
                return t.handler().getResource(index);
            } finally {
                depth[0]--;
            }
        }

        @SuppressWarnings("unchecked")
        private T emptyLike(@Nullable Target<ResourceHandler<T>> ignored) {
            return (T) (medium == Medium.ITEM ? ItemResource.EMPTY : FluidResource.EMPTY);
        }

        @Override
        public long getAmountAsLong(int index) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                return t == null || index >= t.handler().size() ? 0 : t.handler().getAmountAsLong(index);
            } finally {
                depth[0]--;
            }
        }

        @Override
        public long getCapacityAsLong(int index, T resource) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                return t == null || index >= t.handler().size() ? 0 : t.handler().getCapacityAsLong(index, resource);
            } finally {
                depth[0]--;
            }
        }

        @Override
        public boolean isValid(int index, T resource) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                return t != null && index < t.handler().size() && t.handler().isValid(index, resource);
            } finally {
                depth[0]--;
            }
        }

        @Override
        public int insert(int index, T resource, int amount, TransactionContext transaction) {
            return move(index, resource, amount, transaction, true);
        }

        @Override
        public int extract(int index, T resource, int amount, TransactionContext transaction) {
            return move(index, resource, amount, transaction, false);
        }

        private int move(int index, T resource, int amount, TransactionContext transaction, boolean in) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<ResourceHandler<T>> t = target();
                if (t == null || index >= t.handler().size() || amount <= 0) {
                    return 0;
                }
                if (in && medium == Medium.ITEM && t.port().filter() instanceof qbotics.network.BusFilter filter
                        && resource instanceof ItemResource item && !filter.allows(item.toStack(1),
                                filter.matching() ? new HandlerItemPort((ResourceHandler<ItemResource>) t.handler()).contents() : java.util.List.of())) {
                    return 0; // the bus at the far end doesn't let this into its container
                }
                long now = t.level().getGameTime();
                int allowed = (int) Math.min(amount, crossing.room(t.links(), now));
                if (allowed <= 0) {
                    return 0;
                }
                int moved = in ? t.handler().insert(index, resource, allowed, transaction)
                        : t.handler().extract(index, resource, allowed, transaction);
                crossing.crossed(t.links(), now, moved, transaction);
                return moved;
            } finally {
                depth[0]--;
            }
        }
    }

    /** Energy. */
    private static final class Energy implements EnergyHandler {
        private final PortBlockEntity host;
        private final Direction side;
        private final Crossing crossing = new Crossing(Medium.ENERGY);

        Energy(PortBlockEntity host, Direction side) {
            this.host = host;
            this.side = side;
        }

        private @Nullable Target<EnergyHandler> target() {
            return DEPTH.get()[0] >= MAX_DEPTH ? null : TunnelHandlers.target(host, side, Capabilities.Energy.BLOCK, Medium.ENERGY);
        }

        @Override
        public long getAmountAsLong() {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<EnergyHandler> t = target();
                return t == null ? 0 : t.handler().getAmountAsLong();
            } finally {
                depth[0]--;
            }
        }

        @Override
        public long getCapacityAsLong() {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<EnergyHandler> t = target();
                return t == null ? 0 : t.handler().getCapacityAsLong();
            } finally {
                depth[0]--;
            }
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            return move(amount, transaction, true);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return move(amount, transaction, false);
        }

        private int move(int amount, TransactionContext transaction, boolean in) {
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                Target<EnergyHandler> t = target();
                if (t == null || amount <= 0) {
                    return 0;
                }
                long now = t.level().getGameTime();
                int allowed = (int) Math.min(amount, crossing.room(t.links(), now));
                if (allowed <= 0) {
                    return 0;
                }
                int moved = in ? t.handler().insert(allowed, transaction) : t.handler().extract(allowed, transaction);
                crossing.crossed(t.links(), now, moved, transaction);
                return moved;
            } finally {
                depth[0]--;
            }
        }
    }
}
