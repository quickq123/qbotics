package qbotics.block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qbotics.QboticsContent;
import qbotics.net.BoxPayloads;

/**
 * A Block Sensor (docs/reference.md §6.14): reads the blocks in a box near it, tells programs
 * what changes there (fed by the game's own block changes, so a quiet area costs nothing), and
 * breaks, places and uses items there as its owner. Its box is given by offsets from the
 * sensor, each side up to {@link #MOST_SIZE} blocks long and within {@link #MOST_REACH}; it
 * starts as the one block above it, and the player grows it to what they need.
 */
public class BlockSensorBlockEntity extends DeviceBlockEntity implements HasArea {
    public static final int MOST_SIZE = 11;
    public static final int MOST_REACH = 11;
    /** How far a player may be to change it from its screen. */
    private static final double MAX_USE_DISTANCE = 8;
    /** Sensors that some program is watching: the only ones a block change is offered to. */
    private static final Set<BlockSensorBlockEntity> WATCHED = ConcurrentHashMap.newKeySet();

    /** The box: lowest and highest offsets from the sensor on each axis, inclusive. */
    private int minX;
    private int minY = 1;
    private int minZ;
    private int maxX;
    private int maxY = 1;
    private int maxZ;
    /** A block being broken: where, since when (game time), and what it was then. Not saved. */
    private BlockPos breaking;
    private long breakingSince;
    private BlockState breakingState;

    public BlockSensorBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.BLOCK_SENSOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.BLOCK_SENSOR;
    }

    /** The box as offsets: [minX, minY, minZ, maxX, maxY, maxZ]. */
    public int[] box() {
        return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Why a box can't be had, or null if it can. */
    public static String problem(int[] box) {
        for (int axis = 0; axis < 3; axis++) {
            int low = box[axis];
            int high = box[axis + 3];
            if (high < low) {
                return "each side's far end must be at or past its near end";
            }
            if (high - low + 1 > MOST_SIZE) {
                return "it is at most " + MOST_SIZE + " blocks along each side";
            }
            if (Math.abs(low) > MOST_REACH || Math.abs(high) > MOST_REACH) {
                return "it reaches at most " + MOST_REACH + " blocks from the sensor";
            }
        }
        return null;
    }

    public void setBox(int[] box) {
        if (problem(box) != null) {
            return;
        }
        minX = box[0];
        minY = box[1];
        minZ = box[2];
        maxX = box[3];
        maxY = box[4];
        maxZ = box[5];
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public AABB area() {
        BlockPos at = getBlockPos();
        return new AABB(at.getX() + minX, at.getY() + minY, at.getZ() + minZ, at.getX() + maxX + 1, at.getY() + maxY + 1, at.getZ() + maxZ + 1);
    }

    /** True if a block's position is in its box. */
    public boolean covers(BlockPos pos) {
        int dx = pos.getX() - getBlockPos().getX();
        int dy = pos.getY() - getBlockPos().getY();
        int dz = pos.getZ() - getBlockPos().getZ();
        return dx >= minX && dx <= maxX && dy >= minY && dy <= maxY && dz >= minZ && dz <= maxZ;
    }

    // ---- breaking, which takes time --------------------------------------------------------

    /** Ticks it has been breaking this block, starting now if it wasn't (or the block changed). */
    public long breakingFor(BlockPos pos, BlockState state, long now) {
        if (!pos.equals(breaking) || state != breakingState) {
            breaking = pos.immutable();
            breakingSince = now;
            breakingState = state;
        }
        return now - breakingSince;
    }

    public void doneBreaking() {
        breaking = null;
        breakingState = null;
    }

    // ---- watching for changes --------------------------------------------------------------

    /** A program has begun (or ended) watching it: only watched sensors hear of block changes. */
    public void watched(boolean on) {
        if (on) {
            WATCHED.add(this);
        } else {
            WATCHED.remove(this);
        }
    }

    /**
     * The game changed a block (from the hook on ServerLevel): each watched sensor whose box
     * holds it passes it to its watches. With nothing watched, this is one look at an empty set.
     */
    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState was, BlockState now) {
        if (WATCHED.isEmpty() || was == now) {
            return;
        }
        for (BlockSensorBlockEntity sensor : WATCHED) {
            if (sensor.isRemoved()) {
                WATCHED.remove(sensor);
            } else if (sensor.level == level && sensor.covers(pos)) {
                qbotics.network.Watches.blockChanged(sensor, pos, was, now);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        WATCHED.remove(this);
    }

    // ---- saving, and players' games ----------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putIntArray("box", box());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.getIntArray("box").ifPresent(this::setBoxQuietly);
    }

    private void setBoxQuietly(int[] box) {
        if (box.length == 6 && problem(box) == null) {
            minX = box[0];
            minY = box[1];
            minZ = box[2];
            maxX = box[3];
            maxY = box[4];
            maxZ = box[5];
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("box", box());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The owner changes its box from its screen; anyone may when it has no owner, and so may an operator. */
    public boolean mayChange(ServerPlayer player) {
        return owner() == null || owner().equals(player.getUUID())
                || player.level().getServer().getProfilePermissions(new net.minecraft.server.players.NameAndId(player.getUUID(), player.getName().getString()))
                        .hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public BoxPayloads.Info info(ServerPlayer player) {
        return new BoxPayloads.Info(getBlockPos(), java.util.Arrays.stream(box()).boxed().toList(), mayChange(player));
    }

    /** A player changed the box on its screen. */
    public static void onAction(ServerPlayer player, BoxPayloads.Action action) {
        if (!player.level().isLoaded(action.pos()) || player.distanceToSqr(Vec3.atCenterOf(action.pos())) > MAX_USE_DISTANCE * MAX_USE_DISTANCE
                || !(player.level().getBlockEntity(action.pos()) instanceof BlockSensorBlockEntity sensor)) {
            return;
        }
        if (sensor.mayChange(player) && action.box().size() == 6) {
            sensor.setBox(action.box().stream().mapToInt(Integer::intValue).toArray());
        }
        qbotics.Qbotics.platform().sendToPlayer(player, sensor.info(player));
    }
}
