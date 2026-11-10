package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import qbotics.Qbotics;

/**
 * Cable: joins Qbotics blocks into one network (design doc §11.0), and a crafting
 * ingredient. For now it only connects visually; networks arrive with the logistics blocks.
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
                    level.getBlockState(pos.relative(direction)).is(CONNECTABLE));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour), neighbourState.is(CONNECTABLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }
}
