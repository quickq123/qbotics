package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qbotics.net.SensorPayloads;

/**
 * A device that works a box around it: the Entity Sensor and the Collector. The box reaches
 * {@code radius} blocks out on every side and {@code height} up and down, each 1 to
 * {@link #MOST}. Its screen sets them and has Show area, which outlines the box in the world.
 */
public abstract class AreaBlockEntity extends DeviceBlockEntity implements HasArea {
    public static final int MOST = 20;
    public static final int DEFAULT = 8;
    /** How far a player may be to change it from its screen. */
    private static final double MAX_USE_DISTANCE = 8;

    private int radius = DEFAULT;
    private int height = DEFAULT;

    protected AreaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int radius() {
        return radius;
    }

    public int height() {
        return height;
    }

    /** Sets how far it reaches, each from 1 to 20; players' games are told, for the Show area outline. */
    public void setArea(int newRadius, int newHeight) {
        radius = Math.clamp(newRadius, 1, MOST);
        height = Math.clamp(newHeight, 1, MOST);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** The box it works: whole blocks, from radius (and height) out on each side of its own. */
    @Override
    public AABB area() {
        return new AABB(getBlockPos()).inflate(radius, height, radius);
    }

    /** True if an entity stands in its area: where it is, not any edge of it, so one by the border is plainly in or out. */
    public boolean holds(net.minecraft.world.entity.Entity entity) {
        return area().contains(entity.position());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("radius", radius);
        output.putInt("height", height);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        radius = Math.clamp(input.getIntOr("radius", DEFAULT), 1, MOST);
        height = Math.clamp(input.getIntOr("height", DEFAULT), 1, MOST);
    }

    /** Players' games need its size, to draw the area. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("radius", radius);
        tag.putInt("height", height);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The owner changes its size from its screen; anyone may when it has no owner, and so may an operator. */
    public boolean mayChange(ServerPlayer player) {
        return owner() == null || owner().equals(player.getUUID())
                || player.level().getServer().getProfilePermissions(new net.minecraft.server.players.NameAndId(player.getUUID(), player.getName().getString()))
                        .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public SensorPayloads.Info info(ServerPlayer player) {
        return new SensorPayloads.Info(getBlockPos(), radius, height, mayChange(player));
    }

    /** A player changed the size on its screen. */
    public static void onAction(ServerPlayer player, SensorPayloads.Action action) {
        if (!player.level().isLoaded(action.pos()) || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > MAX_USE_DISTANCE * MAX_USE_DISTANCE
                || !(player.level().getBlockEntity(action.pos()) instanceof AreaBlockEntity device)) {
            return;
        }
        if (device.mayChange(player)) {
            device.setArea(action.radius(), action.height());
        }
        qbotics.Qbotics.platform().sendToPlayer(player, device.info(player));
    }
}
