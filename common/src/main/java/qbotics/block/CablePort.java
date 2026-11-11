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
 * One plate on a cable: an input, an output or a bus (design doc §11.0). It holds nothing
 * itself, and acts on the container it points at. A cable can carry one on each side, so
 * several of these can share one block position.
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
    /** Which kind of plate it is: an input, an output or a bus. */
    Kind kind = Kind.BUS;
    /** True once the plate has been taken off its cable. */
    boolean detached;

    // ---- a Logic Pin (see WorldPlates). Saved: the mode, the level it drives and when a pulse ends. ----
    /** True if it drives a signal; false if it reads one. */
    boolean pinOutput;
    /** The level it drives, 0 to 15. */
    int pinLevel;
    /** The game time a pulse ends at, or 0 if none is running. */
    long pulseUntil;
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

    @Override
    public UUID deviceId() {
        if (deviceId == null) {
            deviceId = UUID.randomUUID();
            host.setChanged();
        }
        return deviceId;
    }

    /** The player who put the plate on, or null if nobody did (a command, a machine). */
    public @Nullable UUID owner() {
        return owner;
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
        return name;
    }

    @Override
    public void setDeviceName(String newName) {
        name = Addresses.cleanName(newName);
        host.setChanged();
    }

    @Override
    public int hostNumber() {
        return hostNumber;
    }

    @Override
    public void setHostNumber(int number) {
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
        return detached || host.isRemoved() || host.port(side) != this;
    }

    /** What a Logic Pin is doing, and what it has seen since it was put on. */
    public record PinState(boolean output, int driving, int reading, long rising, long falling, long changes, long blockChanges) {
    }

    public PinState pinState() {
        return new PinState(pinOutput, pinLevel, inputLevel, rising, falling, signalChanges, blockChanges);
    }

    /** True if things can be taken from what it points at: an input or a bus. */
    public boolean gives() {
        return kind == Kind.INPUT || kind == Kind.BUS;
    }

    /** True if things can be put into what it points at: an output or a bus. */
    public boolean takes() {
        return kind == Kind.OUTPUT || kind == Kind.BUS;
    }

    /** The direction from the cable to the plate's target. */
    public Direction pointing() {
        return side;
    }

    public BlockPos targetPos() {
        return host.getBlockPos().relative(side);
    }

    public AccessFace accessFace() {
        return accessFace;
    }

    public void setAccessFace(AccessFace face) {
        accessFace = face;
        host.setChanged();
    }
}
