package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
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
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.display.Displays;

/**
 * A Text Monitor or a Control Panel (design doc §10). Its screen faces the player who placed
 * it, or matches the display it was placed against. Right-click the screen to touch it (a
 * panel's buttons, toggles and sliders); sneak and right-click with an empty hand for a
 * close-up view.
 */
public class DisplayBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    /** The kinds of display: a Text Monitor, a Control Panel, a Web Panel. */
    public enum Type {
        MONITOR, PANEL, WEB
    }

    final Type type;
    private final MapCodec<DisplayBlock> codec;

    public DisplayBlock(BlockBehaviour.Properties properties, Type type) {
        super(properties);
        this.type = type;
        this.codec = simpleCodec(p -> new DisplayBlock(p, type));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static DisplayBlock monitor(BlockBehaviour.Properties properties) {
        return new DisplayBlock(properties, Type.MONITOR);
    }

    public static DisplayBlock panel(BlockBehaviour.Properties properties) {
        return new DisplayBlock(properties, Type.PANEL);
    }

    public static DisplayBlock web(BlockBehaviour.Properties properties) {
        return new DisplayBlock(properties, Type.WEB);
    }

    public Type type() {
        return type;
    }

    /**
     * A Web Panel is touched in the player's own game, where the page is laid out: it finds
     * what was clicked and tells the server (see qbotics.client.display.ClientWebPanels).
     * Set by the client; null on a dedicated server.
     */
    public static volatile @Nullable WebTouch webTouch;

    public interface WebTouch {
        /** A click on a Web Panel at a point of its screen (fractions of its width and height); true if it was handled. */
        boolean touched(DisplayBlockEntity display, double fx, double fy, boolean closeUp);
    }

    @Override
    protected MapCodec<DisplayBlock> codec() {
        return codec;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayBlockEntity(pos, state);
    }

    /** It faces the player; placed against a display of the same kind, it faces the same way, to grow it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos against = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState there = context.getLevel().getBlockState(against);
        if (there.getBlock() == this && context.getClickedFace().getAxis() != there.getValue(FACING).getAxis()) {
            return defaultBlockState().setValue(FACING, there.getValue(FACING));
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
            display.onPlaced(placer instanceof Player player ? player.getUUID() : null);
            qbotics.network.CableNetwork.changed();
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(state.getBlock())) {
            Displays.reform(serverLevel, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        Displays.removed(level, pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        // Blocks in hand are placed (a display against a display grows the screen); the probe reads it.
        if (stack.getItem() instanceof BlockItem || ProbeItem.heldBy(player)) {
            return InteractionResult.PASS;
        }
        return touch(state, level, pos, player, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return touch(state, level, pos, player, hit);
    }

    private InteractionResult touch(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (hit.getDirection() != state.getValue(FACING)) {
            return InteractionResult.PASS; // only the screen's face is touched
        }
        if (type == Type.WEB) {
            // Clicked in the player's game, which has the page laid out; the server hears what was clicked.
            WebTouch hook = webTouch;
            if (level.isClientSide() && hook != null && level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
                double[] at = screenPoint(display, hit.getLocation());
                hook.touched(display, at[0], at[1], player.isShiftKeyDown() && player.getMainHandItem().isEmpty());
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
            if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
                Displays.openCloseUp(serverPlayer, display);
            } else {
                double[] at = screenPoint(display, hit.getLocation());
                Displays.touched(serverLevel, display, at[0], at[1], serverPlayer);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Where on its whole screen a point on a block's face is, as fractions of the screen's width and height. */
    public static double[] screenPoint(DisplayBlockEntity display, Vec3 point) {
        BlockPos pos = display.getBlockPos();
        Direction right = display.facing().getCounterClockWise();
        double dx = point.x - pos.getX() - 0.5;
        double dz = point.z - pos.getZ() - 0.5;
        double across = dx * right.getStepX() + dz * right.getStepZ() + 0.5;
        double down = 1 - (point.y - pos.getY());
        double fx = (display.col() + Math.clamp(across, 0, 1)) / display.screenWidth();
        double fy = (display.row() + Math.clamp(down, 0, 1)) / display.screenHeight();
        return new double[] {Math.clamp(fx, 0, 0.9999), Math.clamp(fy, 0, 0.9999)};
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
        builder.add(FACING);
    }
}
