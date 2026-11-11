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
import qbotics.network.CableNetwork;

/** The Drive Bay: right-click to put hard drives in or take them out (see {@link DriveBayBlockEntity}). */
public class DriveBayBlock extends BaseEntityBlock {
    public static final MapCodec<DriveBayBlock> CODEC = simpleCodec(DriveBayBlock::new);

    public DriveBayBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<DriveBayBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DriveBayBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DriveBayBlockEntity bay) {
            bay.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof DriveBayBlockEntity bay) {
            CableNetwork.at(serverLevel, pos); // makes sure it has its number, for its folder name
            serverPlayer.openMenu(bay);
        }
        return InteractionResult.SUCCESS;
    }
}
