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
 * kept for up to a second or until a cable or device is placed or removed.
 *
 * <p>Network blocks that touch are joined too, so a cable isn't needed between neighbours.
 * Used on the server thread only.
 */
public final class CableNetwork {
    /** Cables and devices one network may have. Design doc §15; will come from config. */
    public static final int MAX_BLOCKS = 4096;
    private static final int MAX_AGE_TICKS = 20;

    /** Goes up whenever a cable or device appears or disappears anywhere. */
    private static volatile long topology;
    private static final Map<ServerLevel, Map<BlockPos, CableNetwork>> CACHE = new WeakHashMap<>();
    private static long cacheTopology = -1;

    private final List<NetworkDevice> devices;
    private final boolean tooLarge;
    private final long madeAt;

    private CableNetwork(List<NetworkDevice> devices, boolean tooLarge, long madeAt) {
        this.devices = devices;
        this.tooLarge = tooLarge;
        this.madeAt = madeAt;
    }

    /** A cable or device was placed, removed, loaded or unloaded: networks must be looked at again. */
    public static void changed() {
        topology++;
    }

    /** The network that the cable or device at this position is on. */
    public static CableNetwork at(ServerLevel level, BlockPos start) {
        if (cacheTopology != topology) {
            CACHE.clear();
            cacheTopology = topology;
        }
        Map<BlockPos, CableNetwork> known = CACHE.computeIfAbsent(level, l -> new HashMap<>());
        CableNetwork network = known.get(start);
        long now = level.getGameTime();
        if (network == null || now - network.madeAt > MAX_AGE_TICKS || now < network.madeAt) {
            network = discover(level, start.immutable(), now);
            for (NetworkDevice device : network.devices) {
                known.put(device.getBlockPos(), network);
            }
            known.put(start.immutable(), network);
        }
        return network;
    }

    private static CableNetwork discover(ServerLevel level, BlockPos start, long now) {
        List<NetworkDevice> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean tooLarge = false;
        if (isCable(level, start) || deviceAt(level, start) != null) {
            queue.add(start);
            seen.add(start);
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            NetworkDevice device = deviceAt(level, pos);
            if (device != null) {
                found.add(device);
            }
            // A cable's plates are devices too, sharing the cable's position.
            found.addAll(CableBlock.ports(level, pos));
            for (Direction side : Direction.values()) {
                if (!joinsOn(level, pos, side)) {
                    continue;
                }
                BlockPos next = pos.relative(side);
                if (seen.contains(next) || !level.isLoaded(next)) {
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
        return new CableNetwork(List.copyOf(found), tooLarge, now);
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
        return entity instanceof NetworkDevice device && !entity.isRemoved() ? device : null;
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

    /** True if the network has more cables or devices than is allowed; it does nothing until made smaller. */
    public boolean tooLarge() {
        return tooLarge;
    }

    /** The antenna that is this network's router, or null if it has none. */
    public qbotics.block.AntennaBlockEntity gateway() {
        for (NetworkDevice device : devices()) {
            if (device.hostNumber() == Addresses.GATEWAY_HOST && device instanceof qbotics.block.AntennaBlockEntity antenna) {
                return antenna;
            }
        }
        return null;
    }

    /** The device with this last number in its internal IP address, or null. */
    public NetworkDevice byHost(int hostNumber) {
        for (NetworkDevice device : devices()) {
            if (device.hostNumber() == hostNumber) {
                return device;
            }
        }
        return null;
    }

    /** The device with this hardware address, or null. */
    public NetworkDevice byMac(String mac) {
        for (NetworkDevice device : devices()) {
            if (device.mac().equalsIgnoreCase(mac)) {
                return device;
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
