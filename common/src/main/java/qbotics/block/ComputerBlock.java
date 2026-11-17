package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import qbotics.computer.ComputerManager;

/** The computer: a full cube with a screen on its front face. Right-click opens its terminal. */
public class ComputerBlock extends BaseEntityBlock {
    public static final MapCodec<ComputerBlock> CODEC = simpleCodec(ComputerBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    /** False while bricked (design doc §5.3): its screen is dark. */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty ACTIVE =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("active");

    public ComputerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, true));
    }

    @Override
    protected MapCodec<ComputerBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // The screen faces whoever placed it.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.onPlaced(placer instanceof Player player ? player.getUUID() : null);
            qbotics.limits.Limits.placed(serverLevel, computer, placer instanceof Player player ? player : null);
            // A Programmer facing the place it arrived programs it (design doc §5.4).
            Programmers.arrived(serverLevel, computer);
        }
    }

    /** With a laptop in hand, the laptop decides (it connects to this computer); see LaptopItem.useOn. */
    @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof qbotics.laptop.LaptopItem) {
            return InteractionResult.PASS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            if (computer.owner() == null && !computer.bricked()) {
                // Nobody placed it (a command, a structure): inert until a player takes it on, within their limit.
                computer.setBricked(true);
            }
            if (computer.bricked()) {
                // Inert: right-clicking asks for it to come back on (design doc §5.3).
                serverPlayer.sendOverlayMessage(qbotics.limits.Limits.tryActivate(serverPlayer.level(), computer, serverPlayer));
                return InteractionResult.SUCCESS;
            }
            if (!computer.mayOpen(serverPlayer)) {
                serverPlayer.sendOverlayMessage(ComputerBlockEntity.refusal());
                return InteractionResult.SUCCESS;
            }
            ComputerManager.get(serverPlayer.level().getServer()).openTerminal(serverPlayer, computer);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }
}
