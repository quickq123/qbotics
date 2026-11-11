package qbotics.block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.network.AccessFace;
import qbotics.network.Addresses;
import qbotics.network.CableNetwork;

/**
 * What a cable remembers about the plates it carries: up to six, one per side (see
 * {@link CableBlock}). Each plate is a device of its own on the network, a
 * {@link CablePort}. Only cables with at least one plate have one of these.
 *
 * <p>Which sides carry which kind of plate is in the cable's block state; this holds the
 * rest (who each plate is, its name and address). A plate the block state shows but nothing
 * was saved for is simply given a new identity.
 */
public class PortBlockEntity extends BlockEntity {
    private final Map<Direction, CablePort> ports = new EnumMap<>(Direction.class);

    public PortBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.PORT_BLOCK_ENTITY.get(), pos, state);
        CableNetwork.changed();
    }

    /** The plate on a side, or null if that side carries none. */
    public @Nullable CablePort port(Direction side) {
        if (CableBlock.plateKind(getBlockState(), side) == null) {
            CablePort gone = ports.remove(side);
            if (gone != null) {
                gone.detached = true;
            }
            return null;
        }
        return ports.computeIfAbsent(side, s -> new CablePort(this, s));
    }

    /** Every plate on this cable. */
    public List<CablePort> ports() {
        List<CablePort> all = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            CablePort port = port(side);
            if (port != null) {
                all.add(port);
            }
        }
        return all;
    }

    /** The plate on this side has been taken off. */
    void forget(Direction side) {
        CablePort gone = ports.remove(side);
        if (gone != null) {
            gone.detached = true;
        }
        setChanged();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CableNetwork.changed();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (Map.Entry<Direction, CablePort> entry : ports.entrySet()) {
            CablePort port = entry.getValue();
            ValueOutput child = output.child(entry.getKey().getSerializedName());
            child.storeNullable("device_id", UUIDUtil.CODEC, port.deviceId);
            child.storeNullable("owner", UUIDUtil.CODEC, port.owner);
            child.putString("name", port.name);
            child.putInt("host", port.hostNumber);
            child.putString("face", port.accessFace.word());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ports.clear();
        for (Direction side : Direction.values()) {
            ValueInput child = input.child(side.getSerializedName()).orElse(null);
            if (child == null) {
                continue;
            }
            CablePort port = new CablePort(this, side);
            port.deviceId = child.read("device_id", UUIDUtil.CODEC).orElse(null);
            port.owner = child.read("owner", UUIDUtil.CODEC).orElse(null);
            port.name = Addresses.cleanName(child.getStringOr("name", ""));
            port.hostNumber = child.getIntOr("host", 0);
            AccessFace face = AccessFace.parse(child.getStringOr("face", ""));
            port.accessFace = face == null ? AccessFace.NEAR : face;
            ports.put(side, port);
        }
    }

    /** A new identity for a plate that has just been put on (a new one on every placement, like a block's). */
    static void placed(CablePort port, @Nullable UUID placedBy) {
        port.deviceId = UUID.randomUUID();
        port.owner = placedBy;
        port.hostNumber = 0;
        port.host.setChanged();
    }
}
