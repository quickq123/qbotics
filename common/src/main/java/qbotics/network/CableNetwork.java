package qbotics.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import qbotics.block.CableBlock;

/**
 * The devices joined by one run of cable (design doc §11.0). A network isn't stored
 * anywhere: it is worked out by walking the cables from any block on it, and the answer is
 * kept until something changes at one of its blocks or next to one (a cable or device placed,
 * removed, loaded or unloaded; a block there changing state), or a chunk it is in or stopped
 * at loads or unloads. Other networks keep theirs. (Every ten seconds it is walked again
 * regardless, in case a change was missed.)
 *
 * <p>Network blocks that touch are joined too, so a cable isn't needed between neighbours.
 * Used on the server thread only.
 */
public final class CableNetwork {
    /** Cables and devices one network may have. Design doc §15; will come from config. */
    public static final int MAX_BLOCKS = 4096;
    private static final int MAX_AGE_TICKS = 200;

    /** Goes up when every network must be looked at again (a change with no place to it). */
    private static volatile long topology;
    /** Each level's networks, by every block they were walked through (and where each was asked for). */
    private static final Map<ServerLevel, Map<BlockPos, CableNetwork>> CACHE = new WeakHashMap<>();
    private static long cacheTopology = -1;
    /** Places where something changed, and chunks that loaded or unloaded, since networks were last asked for. */
    private static final java.util.Queue<BlockPos> CHANGED_AT = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Queue<Long> CHANGED_CHUNKS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final List<NetworkDevice> devices;
    private final boolean tooLarge;
    private final long madeAt;
    private final Set<Long> chunks;
    /** Chunks it stopped at because they weren't loaded: when one loads, it may reach further. */
    private final Set<Long> edges;
    /** Every position it is kept under: its blocks, and where it was asked for. */
    private final List<BlockPos> keys = new ArrayList<>();
    /** Its devices by hardware address (lower case) and by the last number of their IP address. */
    private final Map<String, NetworkDevice> byMac = new HashMap<>();
    private final Map<Integer, NetworkDevice> byHost = new HashMap<>();
    private final qbotics.block.AntennaBlockEntity router;

    private CableNetwork(List<NetworkDevice> devices, boolean tooLarge, long madeAt, Set<Long> chunks, Set<Long> edges) {
        this.devices = devices;
        this.tooLarge = tooLarge;
        this.madeAt = madeAt;
        this.chunks = chunks;
        this.edges = edges;
        qbotics.block.AntennaBlockEntity found = null;
        for (NetworkDevice device : devices) {
            // The first with an address is the one a scan in address order would find.
            byMac.putIfAbsent(device.mac().toLowerCase(java.util.Locale.ROOT), device);
            byHost.putIfAbsent(device.hostNumber(), device);
            if (found == null && device.hostNumber() == Addresses.GATEWAY_HOST && device instanceof qbotics.block.AntennaBlockEntity antenna) {
                found = antenna;
            }
        }
        router = found;
    }

    /** Something changed that has no one place (a whole test stage built): every network is looked at again. */
    public static void changed() {
        topology++;
    }

    /**
     * A cable or device was placed, removed, loaded or unloaded here, or changed how it joins:
     * the networks that have this block, or one beside it, are looked at again. Others are left.
     * May be called from any thread (blocks are made off the server thread while chunks load).
     */
    public static void changed(BlockPos pos) {
        CHANGED_AT.add(pos.immutable());
    }

    /** A chunk loaded or unloaded: networks in it, or that stopped at its edge, are looked at again. */
    public static void chunkChanged(int chunkX, int chunkZ) {
        CHANGED_CHUNKS.add(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * A block became another kind of block (any block, anywhere: a command, a piston, an explosion,
     * another mod): if a network has that place, the network is looked at again. A block that only
     * changes its state (a light coming on) is left: the cables and devices tell of their own changes.
     */
    public static void blockChanged(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState was,
            net.minecraft.world.level.block.state.BlockState now) {
        if (was.getBlock() == now.getBlock()) {
            return;
        }
        Map<BlockPos, CableNetwork> known = CACHE.get(level);
        if (known != null && !known.isEmpty() && known.containsKey(pos)) {
            CHANGED_AT.add(pos.immutable());
        }
    }

    /** Forgets the networks that the changes since last time touch. Server thread. */
    private static void forgetChanged() {
        if (cacheTopology != topology) {
            CACHE.clear();
            cacheTopology = topology;
            CHANGED_AT.clear();
            CHANGED_CHUNKS.clear();
            return;
        }
        for (BlockPos pos; (pos = CHANGED_AT.poll()) != null; ) {
            for (Map<BlockPos, CableNetwork> known : CACHE.values()) {
                if (known.isEmpty()) {
                    continue;
                }
                forget(known, known.get(pos));
                for (Direction side : Direction.values()) {
                    forget(known, known.get(pos.relative(side)));
                }
            }
        }
        for (Long chunk; (chunk = CHANGED_CHUNKS.poll()) != null; ) {
            for (Map<BlockPos, CableNetwork> known : CACHE.values()) {
                if (known.isEmpty()) {
                    continue;
                }
                List<CableNetwork> touched = new ArrayList<>();
                for (CableNetwork network : known.values()) {
                    if ((network.chunks.contains(chunk) || network.edges.contains(chunk)) && !touched.contains(network)) {
                        touched.add(network);
                    }
                }
                for (CableNetwork network : touched) {
                    forget(known, network);
                }
            }
        }
    }

    private static void forget(Map<BlockPos, CableNetwork> known, CableNetwork network) {
        if (network != null) {
            for (BlockPos key : network.keys) {
                known.remove(key, network);
            }
        }
    }

    /** The network that the cable or device at this position is on. */
    public static CableNetwork at(ServerLevel level, BlockPos start) {
        if (cacheTopology != topology || !CHANGED_AT.isEmpty() || !CHANGED_CHUNKS.isEmpty()) {
            forgetChanged();
        }
        Map<BlockPos, CableNetwork> known = CACHE.computeIfAbsent(level, l -> new HashMap<>());
        CableNetwork network = known.get(start);
        long now = level.getGameTime();
        if (network != null && (now - network.madeAt > MAX_AGE_TICKS || now < network.madeAt)) {
            forget(known, network);
            network = null;
        }
        if (network == null) {
            network = discover(level, start.immutable(), now);
            for (BlockPos key : network.keys) {
                known.put(key, network);
            }
        }
        return network;
    }

    private static CableNetwork discover(ServerLevel level, BlockPos start, long now) {
        List<NetworkDevice> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Set<Long> edges = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean tooLarge = false;
        if (isCable(level, start) || deviceAt(level, start) != null) {
            queue.add(start);
            seen.add(start);
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            NetworkDevice device = deviceAt(level, pos);
            if (device != null && device.listed()) {
                found.add(device);
            }
            // A cable's plates are devices too, sharing the cable's position.
            found.addAll(CableBlock.ports(level, pos));
            for (Direction side : Direction.values()) {
                if (!joinsOn(level, pos, side)) {
                    continue;
                }
                BlockPos next = pos.relative(side);
                if (seen.contains(next)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    edges.add(net.minecraft.world.level.ChunkPos.pack(next.getX() >> 4, next.getZ() >> 4));
                    continue;
                }
                if (joinsOn(level, next, side.getOpposite())) {
                    if (seen.size() >= MAX_BLOCKS) {
                        tooLarge = true;
                        queue.clear();
                        break;
                    }
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        tooLarge |= !giveAddresses(found);
        found.sort(Comparator.comparingInt(NetworkDevice::hostNumber));
        Set<Long> chunks = new HashSet<>();
        for (BlockPos pos : seen) {
            chunks.add(net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        }
        CableNetwork network = new CableNetwork(List.copyOf(found), tooLarge, now, Set.copyOf(chunks), Set.copyOf(edges));
        network.keys.addAll(seen);
        if (!seen.contains(start)) {
            network.keys.add(start); // asked for where there is no cable or device: an empty network, kept there
        }
        return network;
    }

    /** True if it stopped at a chunk that isn't loaded: it may reach further than is known now. */
    public boolean reachesUnloaded() {
        return !edges.isEmpty();
    }

    /** The chunks its cables and devices are in (as ChunkPos longs). */
    public Set<Long> chunks() {
        return chunks;
    }

    /** True if the cable or device at a position joins the network through this side of it. */
    private static boolean joinsOn(ServerLevel level, BlockPos pos, Direction side) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CableBlock) {
            return CableBlock.plateKind(level, pos, side) == null; // a plate's face is not a connection
        }
        NetworkDevice device = deviceAt(level, pos);
        return device != null && device.connectsOn(side);
    }

    private static boolean isCable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof CableBlock;
    }

    private static NetworkDevice deviceAt(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        // A bricked device (design doc §5.3) is inert: it isn't on the network at all.
        return entity instanceof NetworkDevice device && !entity.isRemoved()
                && !(entity instanceof qbotics.limits.Limited limited && limited.bricked()) ? device : null;
    }

    /**
     * Gives every device an internal IP address that no other device on the network has.
     * A device keeps the address it already holds; when two networks are joined and two
     * devices hold the same one, the device with the lower hardware address keeps it and the
     * other gets the lowest free one. Returns false if there are more devices than addresses.
     */
    private static boolean giveAddresses(List<NetworkDevice> devices) {
        List<NetworkDevice> byMac = new ArrayList<>(devices);
        byMac.sort(Comparator.comparing(NetworkDevice::mac).thenComparing(device -> device.getBlockPos().asLong()));
        Set<Integer> taken = new HashSet<>();
        List<NetworkDevice> waiting = new ArrayList<>();
        // One antenna is the network's router, at 192.168.1.1. Any others are ordinary devices.
        NetworkDevice router = null;
        for (NetworkDevice device : byMac) {
            if (device.kind() == NetworkDevice.Kind.ANTENNA && (router == null || device.hostNumber() == Addresses.GATEWAY_HOST)) {
                router = device;
            }
        }
        if (router != null) {
            router.setHostNumber(Addresses.GATEWAY_HOST);
        }
        for (NetworkDevice device : byMac) {
            if (device == router) {
                continue;
            }
            int host = device.hostNumber();
            if (host < Addresses.FIRST_HOST || host > Addresses.LAST_HOST || !taken.add(host)) {
                waiting.add(device);
            }
        }
        int next = Addresses.FIRST_HOST;
        boolean enough = true;
        for (NetworkDevice device : waiting) {
            while (next <= Addresses.LAST_HOST && taken.contains(next)) {
                next++;
            }
            if (next > Addresses.LAST_HOST) {
                device.setHostNumber(0);
                enough = false;
            } else {
                taken.add(next);
                device.setHostNumber(next);
            }
        }
        return enough;
    }

    /** Every device on the network, in address order. Devices removed since are left out. */
    public List<NetworkDevice> devices() {
        List<NetworkDevice> present = new ArrayList<>(devices.size());
        for (NetworkDevice device : devices) {
            if (!device.isRemoved()) {
                present.add(device);
            }
        }
        return present;
    }

    /** Its devices of one kind, in address order (worked out once for a network, for what asks every second). */
    @SuppressWarnings("unchecked")
    public <T> List<T> ofType(Class<T> type) {
        List<?> known = typed.get(type);
        if (known == null) {
            List<T> found = new ArrayList<>();
            for (NetworkDevice device : devices) {
                if (type.isInstance(device)) {
                    found.add(type.cast(device));
                }
            }
            known = List.copyOf(found);
            typed.put(type, known);
        }
        for (Object device : known) {
            if (((NetworkDevice) device).isRemoved()) {
                // One has gone since the network was walked: leave it out, as devices() does.
                List<T> present = new ArrayList<>();
                for (Object each : known) {
                    if (!((NetworkDevice) each).isRemoved()) {
                        present.add((T) each);
                    }
                }
                return present;
            }
        }
        return (List<T>) known;
    }

    /** True if this device is on the network (and still there). */
    public boolean has(NetworkDevice device) {
        if (members == null) {
            members = new HashSet<>(devices);
        }
        return members.contains(device) && !device.isRemoved();
    }

    private final Map<Class<?>, List<?>> typed = new HashMap<>();
    private Set<NetworkDevice> members;

    /** True if the network has more cables or devices than is allowed; it does nothing until made smaller. */
    public boolean tooLarge() {
        return tooLarge;
    }

    /** The antenna that is this network's router, or null if it has none. */
    public qbotics.block.AntennaBlockEntity gateway() {
        return router != null && !router.isRemoved() && router.hostNumber() == Addresses.GATEWAY_HOST ? router : null;
    }

    /** The device with this last number in its internal IP address, or null. */
    public NetworkDevice byHost(int hostNumber) {
        NetworkDevice device = byHost.get(hostNumber);
        if (device != null && !device.isRemoved() && device.hostNumber() == hostNumber) {
            return device;
        }
        // Its number was changed since the network was walked (or it has gone): look through them all, as before.
        for (NetworkDevice other : devices) {
            if (!other.isRemoved() && other.hostNumber() == hostNumber) {
                return other;
            }
        }
        return null;
    }

    /** The device with this hardware address, or null. */
    public NetworkDevice byMac(String mac) {
        NetworkDevice device = byMac.get(mac.toLowerCase(java.util.Locale.ROOT));
        if (device != null && !device.isRemoved() && device.mac().equalsIgnoreCase(mac)) {
            return device;
        }
        // A device is given its lasting address just after it is placed, which may be after the network
        // was walked: look through them all, as before.
        for (NetworkDevice other : devices) {
            if (!other.isRemoved() && other.mac().equalsIgnoreCase(mac)) {
                return other;
            }
        }
        return null;
    }

    /** The devices that an IP address, a hardware address or a name stands for. */
    public List<NetworkDevice> find(String key) {
        List<NetworkDevice> matches = new ArrayList<>();
        for (NetworkDevice device : devices()) {
            if (device.ip().equals(key) || device.mac().equalsIgnoreCase(key)
                    || (!device.deviceName().isEmpty() && device.deviceName().equals(key))) {
                matches.add(device);
            }
        }
        return matches;
    }
}
