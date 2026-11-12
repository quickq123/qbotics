package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The Server: a computer block. Right-click opens its terminal; sneak + right-click, its hard drives. */
public class ServerBlock extends ComputerBlock {
    private static final MapCodec<ServerBlock> SERVER_CODEC = simpleCodec(ServerBlock::new);

    public ServerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected MapCodec codec() {
        return SERVER_CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSecondaryUseActive() && !ProbeItem.heldBy(player)) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ServerBlockEntity server) {
                player.openMenu(server);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
