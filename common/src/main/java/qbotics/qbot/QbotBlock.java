package qbotics.qbot;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import qbotics.block.ComputerBlock;
import qbotics.block.ProbeItem;

/**
 * A Qbot's block (design doc §8). It is a computer, drawn by its renderer (so that it can
 * step, turn and swing its arms) rather than as a still block. Right-click: its terminal and
 * editor, as a computer's. Sneak and right-click with an empty hand: its inventory, tool,
 * upgrade, energy and tanks.
 */
public class QbotBlock extends ComputerBlock {
    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(2, 0, 2, 14, 15, 14);
    private final QbotTier tier;
    private final MapCodec<QbotBlock> codec;

    public QbotBlock(BlockBehaviour.Properties properties, QbotTier tier) {
        super(properties);
        this.tier = tier;
        this.codec = simpleCodec(p -> new QbotBlock(p, tier));
    }

    public static QbotBlock iron(BlockBehaviour.Properties properties) {
        return new QbotBlock(properties, QbotTier.IRON);
    }

    public QbotTier tier() {
        return tier;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected MapCodec codec() {
        return codec;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE; // its renderer draws it
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QbotBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!ProbeItem.heldBy(player) && player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof QbotBlockEntity qbot) {
            if (player instanceof ServerPlayer serverPlayer && !qbot.bricked()) {
                serverPlayer.openMenu(qbot);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
