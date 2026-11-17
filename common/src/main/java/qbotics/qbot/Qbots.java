package qbotics.qbot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import qbotics.Qbotics;
import qbotics.block.ComputerBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.network.CableNetwork;

/** How a Qbot moves (design doc §8.1): one block at a time, and turning on the spot. */
public final class Qbots {
    /** How long a step and a turn take, in ticks: what its renderer animates over, and what its program waits. */
    public static final int STEP_TICKS = 2;
    public static final int TURN_TICKS = 2;

    private Qbots() {
    }

    /** The direction a word names, as the Qbot sees it: forward, back, left, right, up or down. */
    public static Direction side(QbotBlockEntity qbot, String word) {
        Direction facing = qbot.facing();
        return switch (word) {
            case "front", "forward" -> facing;
            case "back" -> facing.getOpposite();
            case "left" -> facing.getCounterClockWise();
            case "right" -> facing.getClockWise();
            case "top", "up" -> Direction.UP;
            case "bottom", "down" -> Direction.DOWN;
            default -> throw new IllegalArgumentException("A side is front, back, left, right, top or bottom, not " + word + ".");
        };
    }

    /**
     * Moves the Qbot one block. Returns the Qbot at its new place; throws IllegalStateException,
     * saying why, if it can't go (something solid, a creature, the edge of the world, land its
     * owner may not build on).
     */
    public static QbotBlockEntity step(ServerLevel level, QbotBlockEntity qbot, Direction way, ServerPlayer standIn) {
        BlockPos from = qbot.getBlockPos();
        BlockPos to = from.relative(way);
        if (!level.isInWorldBounds(to)) {
            throw new IllegalStateException("It can't go " + way.getName() + ": that is the edge of the world.");
        }
        if (!level.isLoaded(to)) {
            throw new IllegalStateException("It can't go " + way.getName() + ": that part of the world isn't loaded.");
        }
        BlockState there = level.getBlockState(to);
        if (!there.canBeReplaced()) {
            throw new IllegalStateException("It can't go " + way.getName() + ": " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(there.getBlock())
                    + " is in the way.");
        }
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(to), entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) {
            throw new IllegalStateException("It can't go " + way.getName() + ": something alive is in the way.");
        }
        BlockState state = qbot.getBlockState();
        CompoundTag data = qbot.saveWithFullMetadata(level.registryAccess());
        BlockState left = level.getFluidState(from).isSource() ? level.getFluidState(from).createLegacyBlock() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        QbotBlockEntity arrived;
        boolean[] allowed = {true};
        ComputerBlockEntity.moving = true;
        try {
            // Its owner must be allowed to build where it goes (a land claim says no, it doesn't go).
            allowed[0] = Qbotics.platform().placeChecked(level, standIn, to, way.getOpposite(), () -> level.setBlock(to, state, Block.UPDATE_ALL));
            if (!allowed[0] || !(level.getBlockEntity(to) instanceof QbotBlockEntity fresh)) {
                throw new IllegalStateException("It can't go " + way.getName() + ": its owner may not build there.");
            }
            fresh.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
            fresh.stepFrom = way.getOpposite();
            fresh.stepAt = level.getGameTime();
            fresh.turnFrom = null;
            fresh.setChanged();
            level.setBlock(from, left, Block.UPDATE_ALL);
            arrived = fresh;
        } finally {
            ComputerBlockEntity.moving = false;
        }
        level.sendBlockUpdated(to, state, state, Block.UPDATE_CLIENTS);
        ComputerManager.get(level.getServer()).moved(arrived.deviceId(), level.dimension(), to);
        qbotics.upgrade.ChunkLoaders.moved(level, from, to);
        // Only the networks it left and joined (a Qbot walking a field touches none).
        CableNetwork.changed(from);
        CableNetwork.changed(to);
        return arrived;
    }

    /** Turns the Qbot a quarter to the left or right. */
    public static void turn(ServerLevel level, QbotBlockEntity qbot, boolean right) {
        Direction was = qbot.facing();
        Direction now = right ? was.getClockWise() : was.getCounterClockWise();
        BlockState state = qbot.getBlockState().setValue(QbotBlock.FACING, now);
        ComputerBlockEntity.moving = true;
        try {
            // Changing its state keeps its block entity: only its facing changes.
            level.setBlock(qbot.getBlockPos(), state, Block.UPDATE_ALL);
        } finally {
            ComputerBlockEntity.moving = false;
        }
        BlockEntity same = level.getBlockEntity(qbot.getBlockPos());
        if (same instanceof QbotBlockEntity turned) {
            turned.turnFrom = was;
            turned.turnAt = level.getGameTime();
            turned.setChanged();
            level.sendBlockUpdated(qbot.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        }
    }
}
