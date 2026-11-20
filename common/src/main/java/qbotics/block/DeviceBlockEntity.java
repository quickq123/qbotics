package qbotics.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.network.Addresses;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;

/**
 * What every block with a network address remembers: who it is, what it is called and the
 * address its network gave it. The item blocks extend this; the computer keeps the same
 * things itself.
 */
public abstract class DeviceBlockEntity extends BlockEntity implements NetworkDevice {
    /** A new one on every placement, like the computer's (design doc §17.1). */
    private @Nullable UUID deviceId;
    private @Nullable UUID owner;
    private String name = "";
    private int hostNumber;

    protected DeviceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        CableNetwork.changed(pos);
    }

    public void onPlaced(@Nullable UUID placedBy) {
        deviceId = UUID.randomUUID();
        owner = placedBy;
        hostNumber = 0;
        setChanged();
    }

    @Override
    public UUID deviceId() {
        if (deviceId == null) {
            deviceId = UUID.randomUUID();
            setChanged();
        }
        return deviceId;
    }

    /**
     * Takes over another block's identity: its ID (so its hardware address), owner, name and
     * internal address. A screen made of several blocks keeps its identity in its top-left
     * block, and hands it on when that block changes.
     */
    protected void takeIdentity(DeviceBlockEntity from) {
        deviceId = from.deviceId();
        owner = from.owner;
        name = from.name;
        hostNumber = from.hostNumber;
        setChanged();
    }

    /** A new identity of its own, keeping its owner: for a block that gave its identity away. */
    protected void freshIdentity() {
        deviceId = UUID.randomUUID();
        name = "";
        hostNumber = 0;
        setChanged();
    }

    /** Gives the device an owner it lacked (a player claiming one a machine placed). */
    public void claim(UUID newOwner) {
        owner = newOwner;
        setChanged();
    }

    public @Nullable UUID owner() {
        return owner;
    }

    @Override
    public String deviceName() {
        return name;
    }

    @Override
    public void setDeviceName(String newName) {
        name = Addresses.cleanName(newName);
        setChanged();
    }

    @Override
    public int hostNumber() {
        return hostNumber;
    }

    @Override
    public void setHostNumber(int number) {
        if (hostNumber != number) {
            hostNumber = number;
            setChanged();
        }
    }

    // ---- what players see on its sides (ShowsContents) ----------------------------------------

    /** What was last sent to players' games, for a block that shows its contents. */
    private @Nullable String lookSent;
    private boolean lookQueued;

    /**
     * Saved, and for a block that shows its contents, checked at the end of the tick for a
     * change players would see. Checked then, not now, because other mods' transfers change
     * a store and may take the change back again before the tick is over.
     */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this instanceof ShowsContents && !lookQueued && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.server.MinecraftServer server = serverLevel.getServer();
            lookQueued = true;
            server.schedule(server.wrapRunnable(this::sendLookIfChanged));
        }
    }

    private void sendLookIfChanged() {
        lookQueued = false;
        if (isRemoved() || level == null || level.getBlockEntity(getBlockPos()) != this) {
            return;
        }
        String now = ((ShowsContents) this).look();
        if (!now.equals(lookSent)) {
            lookSent = now;
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** A block that shows its contents sends players' games what it holds, to draw it. */
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return this instanceof ShowsContents ? saveCustomOnly(registries) : super.getUpdateTag(registries);
    }

    @Override
    public net.minecraft.network.protocol.@Nullable Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return this instanceof ShowsContents ? net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this) : super.getUpdatePacket();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CableNetwork.changed(getBlockPos());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("device_id", UUIDUtil.CODEC, deviceId);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.putString("name", name);
        output.putInt("host", hostNumber);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        deviceId = input.read("device_id", UUIDUtil.CODEC).orElse(null);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        name = Addresses.cleanName(input.getStringOr("name", ""));
        hostNumber = input.getIntOr("host", 0);
    }

    // An item renamed in an anvil names the device it becomes, and a named device drops a named item.

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component custom = components.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            name = Addresses.cleanName(custom.getString());
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!name.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
    }
}
