package qbotics.network;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CablePort;
import qbotics.block.PortBlockEntity;
import qbotics.upgrade.UpgradeItem;

/**
 * Tunnels between two Serial Buses (design doc §11.0.6). Each end of a tunnel offers, on its
 * face, the container the <em>other</em> end points at: a hopper or pipe pushing into one end
 * fills what is behind the other, and one pulling from an end empties it. No program runs and
 * nothing ticks; other mods' blocks reach through as they would reach into any container.
 *
 * <p>Both ends must be on the same cable network, or on networks whose antennas both hold a
 * Transport Upgrade and can reach each other by radio; then the Transport rate caps apply.
 * A tunnel carries what both ends carry: two Item Serial Buses carry items; a USB and a Fluid
 * Serial Bus carry fluid; two USBs carry all three.
 *
 * <p>This is the part that knows where ends are and whether they can reach each other. The
 * handlers other mods see are made on the platform side, from {@link #far}.
 */
public final class Tunnels {
    private Tunnels() {
    }

    /** Where a plate's other end is. Kept with the plate and saved with it. */
    public record Link(UUID id, ResourceKey<Level> dimension, BlockPos pos, Direction side) {
    }

    /**
     * The other end, as reached now: its plate, its level, and the antennas a transfer
     * crosses (none when both ends are on one network).
     */
    public record Far(CablePort port, ServerLevel level, Set<AntennaBlockEntity> links) {
    }

    /** How often a tunnel's route (same network, or which antennas) is worked out again, in ticks. */
    private static final int ROUTE_TICKS = 20;

    private record Route(long madeAt, @Nullable Set<AntennaBlockEntity> links) {
    }

    /** Routes worked out recently, by the end asking. A null set of links means "can't reach now". */
    private static final Map<CablePort, Route> ROUTES = new java.util.WeakHashMap<>();

    /** The media a tunnel between plates carrying these carries: items for two item buses, all three for two USBs. */
    public static Set<Medium> carried(Medium a, Medium b) {
        if (a == Medium.UNIVERSAL && b == Medium.UNIVERSAL) {
            return Set.copyOf(Medium.CARRIED);
        }
        if (a == Medium.UNIVERSAL) {
            return Medium.CARRIED.contains(b) ? Set.of(b) : Set.of();
        }
        if (b == Medium.UNIVERSAL || a == b) {
            return Medium.CARRIED.contains(a) ? Set.of(a) : Set.of();
        }
        return Set.of();
    }

    /** The plate at the other end if it is there (loaded, the same plate, still linked back), else null. */
    private static @Nullable CablePort partner(ServerLevel level, CablePort end) {
        Link link = end.tunnel();
        if (link == null) {
            return null;
        }
        ServerLevel there = level.getServer().getLevel(link.dimension());
        if (there == null || !there.isLoaded(link.pos())) {
            return null; // unloaded: the tunnel waits
        }
        CablePort other = there.getBlockEntity(link.pos()) instanceof PortBlockEntity host ? host.port(link.side()) : null;
        if (other == null || !link.id().equals(other.deviceId()) || other.tunnel() == null || !end.deviceId().equals(other.tunnel().id())) {
            // The other end was taken off or linked elsewhere: this end is no longer in a tunnel.
            end.setTunnel(null);
            Qbotics.platform().capabilitiesChanged(level, end.getBlockPos());
            return null;
        }
        return other;
    }

    /** The level an end is in. */
    private static @Nullable ServerLevel levelOf(CablePort end) {
        return end.level() instanceof ServerLevel own ? own : null;
    }

    /**
     * Why these two ends can't reach each other now, or null if they can; with {@code links}
     * filled with the antennas a transfer crosses (empty for one network).
     */
    private static @Nullable String routeProblem(ServerLevel la, CablePort a, ServerLevel lb, CablePort b, Set<AntennaBlockEntity> links) {
        CableNetwork na = CableNetwork.at(la, a.getBlockPos());
        if (na.devices().contains(b)) {
            return null;
        }
        CableNetwork nb = CableNetwork.at(lb, b.getBlockPos());
        AntennaBlockEntity ga = na.gateway();
        AntennaBlockEntity gb = nb.gateway();
        if (ga == null || gb == null) {
            return "the two buses are on different networks, and a tunnel between networks needs an antenna on each.";
        }
        if (!ga.hasUpgrade(UpgradeItem.Kind.TRANSPORT) || !gb.hasUpgrade(UpgradeItem.Kind.TRANSPORT)) {
            return "a tunnel between networks needs a Transport Upgrade in both antennas (" + ga.externalIp() + " and " + gb.externalIp() + ").";
        }
        if (ga != gb) {
            String problem = RadioNetwork.linkProblem(ga, gb);
            if (problem == null) {
                problem = RadioNetwork.accessProblem(la.getServer(), gb, a.owner());
            }
            if (problem == null) {
                problem = RadioNetwork.accessProblem(la.getServer(), ga, b.owner());
            }
            if (problem != null) {
                return "the antennas can't reach each other: " + problem + ".";
            }
            links.add(ga);
            links.add(gb);
        }
        return null;
    }

    /**
     * The other end of this plate's tunnel, as reachable now, or null if it has none or can't
     * reach it at the moment (unloaded, an antenna lost its upgrade, out of range).
     */
    public static @Nullable Far far(ServerLevel level, CablePort end) {
        CablePort other = partner(level, end);
        ServerLevel there = other == null ? null : levelOf(other);
        if (there == null) {
            return null;
        }
        long now = level.getGameTime();
        Route route = ROUTES.get(end);
        if (route == null || now - route.madeAt() >= ROUTE_TICKS || now < route.madeAt()) {
            Set<AntennaBlockEntity> links = new java.util.HashSet<>();
            route = new Route(now, routeProblem(level, end, there, other, links) == null ? Set.copyOf(links) : null);
            ROUTES.put(end, route);
        }
        return route.links() == null ? null : new Far(other, there, route.links());
    }

    /**
     * Opens a tunnel between two buses. Any tunnel either was in is closed first. Returns
     * null if it is open, or why it couldn't be.
     */
    public static @Nullable String link(ServerLevel la, CablePort a, ServerLevel lb, CablePort b) {
        a = a.base();
        b = b.base();
        if (a.kind() != NetworkDevice.Kind.BUS || b.kind() != NetworkDevice.Kind.BUS) {
            return "a tunnel joins two Serial Buses.";
        }
        if (a == b) {
            return "a tunnel needs two different buses.";
        }
        if (carried(a.medium(), b.medium()).isEmpty()) {
            return "an " + word(a.medium()) + " bus and a " + word(b.medium()) + " bus carry nothing in common. A USB pairs with any bus.";
        }
        String problem = routeProblem(la, a, lb, b, new java.util.HashSet<>());
        if (problem != null) {
            return problem;
        }
        unlink(la, a);
        unlink(lb, b);
        a.setTunnel(new Link(b.deviceId(), lb.dimension(), b.getBlockPos(), b.pointing()));
        b.setTunnel(new Link(a.deviceId(), la.dimension(), a.getBlockPos(), a.pointing()));
        ROUTES.remove(a);
        ROUTES.remove(b);
        Qbotics.platform().capabilitiesChanged(la, a.getBlockPos());
        Qbotics.platform().capabilitiesChanged(lb, b.getBlockPos());
        return null;
    }

    private static String word(Medium medium) {
        return medium == Medium.UNIVERSAL ? "universal" : medium.word;
    }

    /** Closes this plate's tunnel, at both ends (the other end if it is loaded; otherwise it notices later). */
    public static void unlink(ServerLevel level, CablePort end) {
        end = end.base();
        if (end.tunnel() == null) {
            return;
        }
        Link link = end.tunnel();
        end.setTunnel(null);
        ROUTES.remove(end);
        Qbotics.platform().capabilitiesChanged(level, end.getBlockPos());
        ServerLevel there = level.getServer().getLevel(link.dimension());
        if (there != null && there.isLoaded(link.pos()) && there.getBlockEntity(link.pos()) instanceof PortBlockEntity host) {
            CablePort other = host.port(link.side());
            if (other != null && link.id().equals(other.deviceId()) && other.tunnel() != null && end.deviceId().equals(other.tunnel().id())) {
                other.setTunnel(null);
                ROUTES.remove(other);
                Qbotics.platform().capabilitiesChanged(there, other.getBlockPos());
            }
        }
    }

    /** A short way to name a bus to a player: its name, or its kind and internal address. */
    public static String label(ServerLevel level, CablePort port) {
        CableNetwork.at(level, port.getBlockPos()); // gives it an address if it had none
        String kind = port.medium() == Medium.UNIVERSAL ? "USB" : port.medium() == Medium.ITEM ? "item bus" : port.medium().word + " bus";
        return port.deviceName().isEmpty() ? kind + " " + port.ip() : port.deviceName();
    }

    /** Forgets cached routes; the server is stopping. */
    public static void clear() {
        ROUTES.clear();
    }

    /** The address a program on this network uses for the other end, or null if it can't be reached now. */
    public static @Nullable String addressFrom(ServerLevel level, CableNetwork network, CablePort end) {
        Far far = far(level, end.base());
        if (far == null) {
            return null;
        }
        if (network.devices().contains(far.port())) {
            return far.port().mac();
        }
        AntennaBlockEntity gateway = CableNetwork.at(far.level(), far.port().getBlockPos()).gateway();
        return gateway == null ? null : gateway.externalIp() + "/" + far.port().mac();
    }
}
