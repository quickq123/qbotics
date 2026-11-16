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
import qbotics.network.Medium;
import qbotics.network.NetworkDevice;

/**
 * What a cable remembers about the plates it carries: up to six, one per side (see
 * {@link CableBlock}). Each plate is a device of its own on the network, a
 * {@link CablePort}. Every cable has one of these, most of them empty.
 *
 * <p>This is where a cable's plates are: which sides carry which kind of plate, and who
 * each one is (its name, address and what it carries). The block state only has the arms.
 */
public class PortBlockEntity extends BlockEntity {
    private final Map<Direction, CablePort> ports = new EnumMap<>(Direction.class);
    /**
     * The kind on each side, by side number, for drawing: replaced whole whenever the plates
     * change, so the game's drawing threads always read a finished copy.
     */
    private volatile NetworkDevice.@Nullable Kind[] look = new NetworkDevice.Kind[6];

    public PortBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.PORT_BLOCK_ENTITY.get(), pos, state);
    }

    /** The plate on a side, or null if that side carries none. */
    public @Nullable CablePort port(Direction side) {
        return ports.get(side);
    }

    /** The kind of plate on a side, or null. */
    public NetworkDevice.@Nullable Kind kindAt(Direction side) {
        CablePort port = ports.get(side);
        return port == null ? null : port.kind;
    }

    public boolean hasPlates() {
        return !ports.isEmpty();
    }

    /** One bit for each side that carries a plate, by side number. */
    public int plateMask() {
        int mask = 0;
        for (Direction side : ports.keySet()) {
            mask |= 1 << side.get3DDataValue();
        }
        return mask;
    }

    /** The kind on each side by side number, for drawing. Never changed once handed out. */
    public NetworkDevice.@Nullable Kind[] look() {
        return look;
    }

    private void lookChanged() {
        NetworkDevice.Kind[] kinds = new NetworkDevice.Kind[6];
        for (Map.Entry<Direction, CablePort> entry : ports.entrySet()) {
            kinds[entry.getKey().get3DDataValue()] = entry.getValue().kind;
        }
        look = kinds;
    }

    /** Puts a new plate on a side (which must be free) and returns it. */
    CablePort add(Direction side, NetworkDevice.Kind kind, Medium medium) {
        CablePort port = new CablePort(this, side);
        port.kind = kind;
        port.medium = medium;
        ports.put(side, port);
        lookChanged();
        setChanged();
        return port;
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
        lookChanged();
        setChanged();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (!ports.isEmpty()) {
            CableNetwork.changed(); // its plates leave the network (an unloaded chunk, say)
        }
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
            child.putString("medium", port.medium.word);
            child.putString("kind", port.kind.word);
            if (port.tunnel != null) {
                child.store("tunnel_id", UUIDUtil.CODEC, port.tunnel.id());
                child.putString("tunnel_dimension", port.tunnel.dimension().identifier().toString());
                child.putLong("tunnel_pos", port.tunnel.pos().asLong());
                child.putInt("tunnel_side", port.tunnel.side().get3DDataValue());
                child.putBoolean("tunnel_live", port.tunnelLive);
            }
            if (port.filter != null) {
                child.putString("filter", String.join("\n", port.filter.names()));
                child.putBoolean("filter_matching", port.filter.matching());
                child.putBoolean("filter_exact", port.filter.exact());
            }
            if (port.kind == NetworkDevice.Kind.DROPPER) {
                child.putBoolean("dispense", port.dispense);
            }
            if (port.kind == NetworkDevice.Kind.PIN) {
                child.putBoolean("pin_output", port.pinOutput);
                child.putInt("pin_level", port.pinLevel);
                child.putLong("pulse_until", port.pulseUntil);
            }
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
            NetworkDevice.Kind kind = NetworkDevice.Kind.plate(child.getStringOr("kind", ""));
            if (kind == null) {
                continue; // saved before plates were kept here, or unreadable: the side is left bare
            }
            CablePort port = new CablePort(this, side);
            port.kind = kind;
            port.deviceId = child.read("device_id", UUIDUtil.CODEC).orElse(null);
            port.owner = child.read("owner", UUIDUtil.CODEC).orElse(null);
            port.name = Addresses.cleanName(child.getStringOr("name", ""));
            port.hostNumber = child.getIntOr("host", 0);
            AccessFace face = AccessFace.parse(child.getStringOr("face", ""));
            port.accessFace = face == null ? AccessFace.NEAR : face;
            port.medium = Medium.parse(child.getStringOr("medium", ""));
            port.pinOutput = child.getBooleanOr("pin_output", false);
            port.pinLevel = Math.clamp(child.getIntOr("pin_level", 0), 0, 15);
            port.pulseUntil = child.getLongOr("pulse_until", 0L);
            port.dispense = child.getBooleanOr("dispense", false);
            String filterNames = child.getStringOr("filter", null);
            if (filterNames != null) {
                port.filter = new qbotics.network.BusFilter(filterNames.isEmpty() ? List.of() : List.of(filterNames.split("\n")),
                        child.getBooleanOr("filter_matching", false), child.getBooleanOr("filter_exact", false));
            }
            UUID tunnelId = child.read("tunnel_id", UUIDUtil.CODEC).orElse(null);
            net.minecraft.resources.Identifier dimension = net.minecraft.resources.Identifier.tryParse(child.getStringOr("tunnel_dimension", ""));
            if (tunnelId != null && dimension != null) {
                port.tunnel = new qbotics.network.Tunnels.Link(tunnelId,
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension),
                        BlockPos.of(child.getLongOr("tunnel_pos", 0L)), Direction.from3DDataValue(child.getIntOr("tunnel_side", 0)));
                port.tunnelLive = child.getBooleanOr("tunnel_live", false);
            }
            ports.put(side, port);
        }
        lookChanged();
        if (!ports.isEmpty()) {
            CableNetwork.changed(); // plates coming in with a loaded chunk join the network
        }
        // In a player's game the plates' colours come from this, so the block is drawn again.
        if (level != null && level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    /** A tunnel light (or link) changed: saved, and players' games are told so the plate is drawn again. */
    void lightChanged() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** On the server, its tunnel ends are watched so their lights stay right. */
    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            for (CablePort port : ports.values()) {
                if (port.tunnel != null) {
                    qbotics.network.Tunnels.watch(port);
                }
            }
        }
    }

    /** What the plate on a side carries. Used to colour it; never changes anything. */
    public Medium mediumAt(Direction side) {
        CablePort port = ports.get(side);
        return port == null ? Medium.ITEM : port.medium;
    }

    // Players' games are told about the plates, because a plate's colour depends on what it carries.
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    /** A new identity for a plate that has just been put on (a new one on every placement, like a block's). */
    static void placed(CablePort port, @Nullable UUID placedBy) {
        port.deviceId = UUID.randomUUID();
        port.owner = placedBy;
        port.hostNumber = 0;
        port.host.setChanged();
    }
}
