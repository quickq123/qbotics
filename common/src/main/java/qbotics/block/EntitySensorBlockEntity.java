package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qbotics.QboticsContent;
import qbotics.net.SensorPayloads;

/**
 * An Entity Sensor (docs/reference.md §6.12): sees the creatures, players and dropped things
 * in a box around it, reads them, and acts on them as its owner: using an item on them,
 * attacking, trading with villagers, picking things up. The box reaches {@code radius} blocks
 * out on every side and {@code height} up and down, at most {@link #MOST} each way.
 */
public class EntitySensorBlockEntity extends DeviceBlockEntity {
    public static final int MOST = 20;
    public static final int DEFAULT = 8;
    /** How far a player may be to change it from its screen. */
    private static final double MAX_USE_DISTANCE = 8;

    private int radius = DEFAULT;
    private int height = DEFAULT;
    /** When its stand-in last swung (game time), for the attack cooldown. Not saved. */
    private long lastSwing = Long.MIN_VALUE / 2;

    public EntitySensorBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ENTITY_SENSOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.ENTITY_SENSOR;
    }

    public int radius() {
        return radius;
    }

    public int height() {
        return height;
    }

    /** Sets how far it sees, each from 1 to 20; players' games are told, for the Show area outline. */
    public void setArea(int newRadius, int newHeight) {
        radius = Math.clamp(newRadius, 1, MOST);
        height = Math.clamp(newHeight, 1, MOST);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** The box it sees: whole blocks, from radius (and height) out on each side of its own. */
    public AABB area() {
        return new AABB(getBlockPos()).inflate(radius, height, radius);
    }

    public long lastSwing() {
        return lastSwing;
    }

    public void swung(long gameTime) {
        lastSwing = gameTime;
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
                || !(player.level().getBlockEntity(action.pos()) instanceof EntitySensorBlockEntity sensor)) {
            return;
        }
        if (sensor.mayChange(player)) {
            sensor.setArea(action.radius(), action.height());
        }
        qbotics.Qbotics.platform().sendToPlayer(player, sensor.info(player));
    }
}
