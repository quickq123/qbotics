package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import qbotics.Qbotics;
import qbotics.network.CableNetwork;

/**
 * Cable: joins Qbotics blocks into one network (design doc §11.0), and a crafting
 * ingredient. A cable holds nothing itself: {@link CableNetwork} walks along cables to find
 * which devices are joined.
 */
public class CableBlock extends PipeBlock {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    /** Blocks a cable reaches out to: other cables, computers, and later every network block. */
    public static final TagKey<Block> CONNECTABLE = TagKey.create(Registries.BLOCK, Qbotics.id("cable_connectable"));

    /** Thickness of the cable in pixels (sixteenths of a block). */
    private static final float THICKNESS = 4.0f;

    public CableBlock(BlockBehaviour.Properties properties) {
        super(THICKNESS, properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override
    protected MapCodec<CableBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withConnections(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    public static BlockState withConnections(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                    connectsTo(level.getBlockState(pos.relative(direction)), direction));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour), connectsTo(neighbourState, directionToNeighbour));
    }

    /** True if a cable should reach out to this neighbour, which lies in the given direction. */
    private static boolean connectsTo(BlockState neighbour, Direction direction) {
        if (!neighbour.is(CONNECTABLE)) {
            return false;
        }
        // A bus or output doesn't join the network on the side that points at its target.
        return !(neighbour.getBlock() instanceof PortBlock) || neighbour.getValue(PortBlock.FACING) != direction.getOpposite();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(state.getBlock())) {
            CableNetwork.changed();
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CableNetwork.changed();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }
}
