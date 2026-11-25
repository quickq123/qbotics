package qbotics.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;
import qbotics.network.AccessFace;
import qbotics.network.Addresses;
import qbotics.network.Medium;
import qbotics.network.NetworkDevice;

/**
 * One plate on a cable: a Serial Bus (item, fluid, energy or universal) or a world plate
 * (design doc §11.0). It holds nothing itself, and acts on the container it points at. A
 * cable can carry one on each side, so several of these can share one block position.
 *
 * <p>A Universal Serial Bus carries all three media. A call works on one of them through a
 * <em>view</em> ({@link #as}): a CablePort for that medium that shares the real plate's
 * identity, name, address and face, so the rest of the network code sees an ordinary bus.
 */
public final class CablePort implements NetworkDevice {
    final PortBlockEntity host;

    /** The level its cable is in. */
    public net.minecraft.world.level.@org.jspecify.annotations.Nullable Level level() {
        return host.getLevel();
    }
    private final Direction side;
    @Nullable UUID deviceId;
    @Nullable UUID owner;
    String name = "";
    int hostNumber;
    /** The face used when a program doesn't name one. */
    AccessFace accessFace = AccessFace.NEAR;
    /** What it carries: items, fluid or energy. */
    Medium medium = Medium.ITEM;
    /** Which kind of plate it is: a bus, or a world plate. */
    Kind kind = Kind.BUS;
    /** For a view of a Universal Serial Bus: the real plate. Null for a real plate. */
    private @Nullable CablePort viewOf;
    /** A Universal Serial Bus's views, made once so each stays the same object (screens and watches key on it). */
    private java.util.@Nullable EnumMap<Medium, CablePort> views;
    /** True once the plate has been taken off its cable. */
    boolean detached;
    /** The other end of this bus's tunnel, or null if it is in none (see Tunnels). */
    qbotics.network.Tunnels.@Nullable Link tunnel;
    /** True while its tunnel's other end can be reached: what its light shows (green, not amber). */
    boolean tunnelLive;
    /** True if this end pulls what its block holds through its tunnel, into the block at the other end. */
    boolean tunnelPull;
    /** What an item bus lets into its container, or null for anything (see BusFilter). */
    qbotics.network.@Nullable BusFilter filter;

    // ---- a Logic Pin (see WorldPlates). Saved: the mode, the level it drives and when a pulse ends. ----
    /** True if it drives a signal; false if it reads one. */
    boolean pinOutput;
    /** The level it drives, 0 to 15. */
    int pinLevel;
    /** The game time a pulse ends at, or 0 if none is running. */
    long pulseUntil;

    // ---- a Dropper plate. Saved: whether it fires items as a dispenser does, or throws them out as a dropper. ----
    boolean dispense;

    /** True if this Dropper plate fires items as a dispenser would (arrows flying, bone meal used); false to throw them out as a dropper. */
    public boolean dispense() {
        return dispense;
    }

    public void setDispense(boolean fire) {
        dispense = fire;
        host.setChanged();
    }
    /** What it last read, and counts of what it has seen happen since it was put on (not saved). */
    int inputLevel;
    boolean inputKnown;
    long rising;
    long falling;
    long signalChanges;
    long blockChanges;
    net.minecraft.world.level.block.state.@Nullable BlockState lastSeen;
    /** Computers whose programs want this pin's events, with the session each signed up under (not saved). */
    final java.util.Map<UUID, Integer> listeners = new java.util.HashMap<>();

    /** A computer's program wants this pin's events pushed to it (see WorldPlates.sense). */
    public void listen(UUID computer, int session) {
        listeners.put(computer, session);
    }

    // ---- a Breaker (not saved): the block it is part-way through breaking, and the drops remembered for it. ----
    net.minecraft.world.level.block.state.@Nullable BlockState breaking;
    long breakingSince;
    net.minecraft.world.level.block.state.@Nullable BlockState dropsFor;
    java.util.List<net.minecraft.world.item.ItemStack> drops = java.util.List.of();

    CablePort(PortBlockEntity host, Direction side) {
        this.host = host;
        this.side = side;
    }

    /** Called when a player puts the plate on. */
    public void onPlaced(@Nullable UUID placedBy) {
        PortBlockEntity.placed(this, placedBy);
    }

    /** The other end of this bus's tunnel, or null if it is in none. */
    public qbotics.network.Tunnels.@Nullable Link tunnel() {
        return viewOf != null ? viewOf.tunnel : tunnel;
    }

    /** True while its tunnel's other end can be reached (see Tunnels.tick). */
    public boolean tunnelLive() {
        return viewOf != null ? viewOf.tunnelLive : tunnelLive;
    }

    /** True if this end of a tunnel pulls from the block it points at and sends that to the other end's. */
    public boolean tunnelPull() {
        return viewOf != null ? viewOf.tunnelPull : tunnelPull;
    }

    /** Sets whether this end pulls; see Tunnels.setPull, which keeps a tunnel to one direction. */
    public void setTunnelPull(boolean pull) {
        CablePort real = base();
        if (real.tunnelPull != pull) {
            real.tunnelPull = pull;
            host.lightChanged();
        }
    }

    /** Sets what its light shows; players nearby are told when it changes. */
    public void setTunnelLive(boolean live) {
        CablePort real = base();
        if (real.tunnelLive != live) {
            real.tunnelLive = live;
            host.lightChanged();
        }
    }

    /** Opens or closes this end of a tunnel; see Tunnels.link and unlink. */
    public void setTunnel(qbotics.network.Tunnels.@Nullable Link link) {
        if (viewOf != null) {
            viewOf.setTunnel(link);
            return;
        }
        tunnel = link;
        tunnelPull = false; // a new tunnel only passes things through, until it is told to pull
        if (link == null) {
            tunnelLive = false;
        } else {
            qbotics.network.Tunnels.watch(this);
        }
        host.lightChanged();
    }

    /** True for a Universal Serial Bus itself (not one of its views). */
    public boolean universal() {
        return medium == Medium.UNIVERSAL;
    }

    /**
     * This Universal Serial Bus working as a bus for one medium. Anything else is returned
     * as it is.
     */
    public CablePort as(Medium wanted) {
        if (!universal() || wanted == Medium.UNIVERSAL) {
            return this;
        }
        if (views == null) {
            views = new java.util.EnumMap<>(Medium.class);
        }
        return views.computeIfAbsent(wanted, m -> {
            CablePort view = new CablePort(host, side);
            view.viewOf = this;
            view.kind = kind;
            view.medium = m;
            return view;
        });
    }

    /** The real plate: itself, or the Universal Serial Bus this is a view of. */
    public CablePort base() {
        return viewOf == null ? this : viewOf;
    }

    @Override
    public String kindWord() {
        return universal() ? "usb" : NetworkDevice.super.kindWord();
    }

    /** A view's address names its medium after the USB's own: "02:1a:...@fluid". */
    @Override
    public String mac() {
        return viewOf == null ? NetworkDevice.super.mac() : viewOf.mac() + "@" + medium.word;
    }

    @Override
    public UUID deviceId() {
        if (viewOf != null) {
            return viewOf.deviceId();
        }
        if (deviceId == null) {
            deviceId = UUID.randomUUID();
            host.setChanged();
        }
        return deviceId;
    }

    /** The player who put the plate on, or null if nobody did (a command, a machine). */
    public @Nullable UUID owner() {
        return viewOf != null ? viewOf.owner : owner;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public Medium medium() {
        return medium;
    }

    @Override
    public String deviceName() {
        return viewOf != null ? viewOf.name : name;
    }

    @Override
    public void setDeviceName(String newName) {
        if (viewOf != null) {
            viewOf.setDeviceName(newName);
            return;
        }
        name = Addresses.cleanName(newName);
        host.setChanged();
    }

    @Override
    public int hostNumber() {
        return viewOf != null ? viewOf.hostNumber : hostNumber;
    }

    @Override
    public void setHostNumber(int number) {
        if (viewOf != null) {
            viewOf.setHostNumber(number);
            return;
        }
        if (hostNumber != number) {
            hostNumber = number;
            host.setChanged();
        }
    }

    /** The cable's position. Other plates on the same cable share it. */
    @Override
    public BlockPos getBlockPos() {
        return host.getBlockPos();
    }

    @Override
    public boolean isRemoved() {
        if (viewOf != null) {
            return viewOf.isRemoved();
        }
        return detached || host.isRemoved() || host.port(side) != this;
    }

    /** What a Logic Pin is doing, and what it has seen since it was put on. */
    public record PinState(boolean output, int driving, int reading, long rising, long falling, long changes, long blockChanges) {
    }

    public PinState pinState() {
        return new PinState(pinOutput, pinLevel, inputLevel, rising, falling, signalChanges, blockChanges);
    }

    /** True if things can be taken from what it points at: a bus. */
    public boolean gives() {
        return kind == Kind.BUS;
    }

    /** True if things can be put into what it points at: a bus. */
    public boolean takes() {
        return kind == Kind.BUS;
    }

    /** The direction from the cable to the plate's target. */
    public Direction pointing() {
        return side;
    }

    public BlockPos targetPos() {
        return host.getBlockPos().relative(side);
    }

    public AccessFace accessFace() {
        return viewOf != null ? viewOf.accessFace : accessFace;
    }

    /** What it lets into its container (items only), or null for anything. */
    public qbotics.network.@Nullable BusFilter filter() {
        return viewOf != null ? viewOf.filter : filter;
    }

    public void setFilter(qbotics.network.@Nullable BusFilter wanted) {
        if (viewOf != null) {
            viewOf.setFilter(wanted);
            return;
        }
        filter = wanted;
        host.setChanged();
    }

    public void setAccessFace(AccessFace face) {
        if (viewOf != null) {
            viewOf.setAccessFace(face);
            return;
        }
        accessFace = face;
        host.setChanged();
    }
}
