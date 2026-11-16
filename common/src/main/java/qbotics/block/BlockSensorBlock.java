package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.network.CableNetwork;

/** The Block Sensor block (see {@link BlockSensorBlockEntity}). Right-click: its screen, for its box and Show area. */
public class BlockSensorBlock extends BaseEntityBlock {
    public static final MapCodec<BlockSensorBlock> CODEC = simpleCodec(BlockSensorBlock::new);

    public BlockSensorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<BlockSensorBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlockSensorBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BlockSensorBlockEntity sensor) {
            sensor.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof BlockSensorBlockEntity sensor) {
            CableNetwork.at(serverLevel, pos);
            Qbotics.platform().sendToPlayer(serverPlayer, sensor.info(serverPlayer));
        }
        return InteractionResult.SUCCESS;
    }
}
