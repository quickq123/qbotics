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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.network.CableNetwork;

/**
 * The Collector block (see {@link CollectorBlockEntity}). Right-click: its slots, as a chest's.
 * Sneak and right-click with an empty hand: its size and Show area.
 */
public class CollectorBlock extends BaseEntityBlock {
    public static final MapCodec<CollectorBlock> CODEC = simpleCodec(CollectorBlock::new);

    public CollectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<CollectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CollectorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (tickLevel, tickPos, tickState, entity) -> {
            if (entity instanceof CollectorBlockEntity collector) {
                collector.serverTick();
            }
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CollectorBlockEntity collector) {
            collector.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CollectorBlockEntity collector) {
            CableNetwork.at(serverLevel, pos);
            if (player.isShiftKeyDown()) {
                Qbotics.platform().sendToPlayer(serverPlayer, collector.info(serverPlayer)); // its size and Show area
            } else {
                serverPlayer.openMenu(collector);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
