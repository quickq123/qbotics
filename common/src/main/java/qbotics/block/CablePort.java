package qbotics.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;
import qbotics.network.AccessFace;
import qbotics.network.Addresses;
import qbotics.network.NetworkDevice;

/**
 * One plate on a cable: an input, an output or a bus (design doc §11.0). It holds nothing
 * itself, and acts on the container it points at. A cable can carry one on each side, so
 * several of these can share one block position.
 */
public final class CablePort implements NetworkDevice {
    final PortBlockEntity host;
    private final Direction side;
    @Nullable UUID deviceId;
    @Nullable UUID owner;
    String name = "";
    int hostNumber;
    /** The face used when a program doesn't name one. */
    AccessFace accessFace = AccessFace.NEAR;
    /** True once the plate has been taken off its cable. */
    boolean detached;

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

    @Override
    public Kind kind() {
        Kind kind = CableBlock.plateKind(host.getBlockState(), side);
        return kind == null ? Kind.BUS : kind;
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
        return detached || host.isRemoved() || CableBlock.plateKind(host.getBlockState(), side) == null;
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
