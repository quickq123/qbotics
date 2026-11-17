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
    /** The rack's four lights, one per service: 0 off, 1 running (green), 2 failed (red). */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty[] LIGHTS = {
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("light1", 0, 2),
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("light2", 0, 2),
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("light3", 0, 2),
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("light4", 0, 2),
    };

    public ServerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = defaultBlockState();
        for (var light : LIGHTS) {
            state = state.setValue(light, 0);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIGHTS);
    }

    /** Sets the rack's lights to how its services are doing (no change, no block update). */
    public static void showLights(Level level, BlockPos pos, int[] lights) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ServerBlock)) {
            return;
        }
        BlockState shown = state;
        for (int i = 0; i < LIGHTS.length; i++) {
            shown = shown.setValue(LIGHTS[i], i < lights.length ? lights[i] : 0);
        }
        if (shown != state) {
            level.setBlock(pos, shown, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
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
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof ServerBlockEntity server) {
                if (!server.mayOpen(serverPlayer)) {
                    serverPlayer.sendOverlayMessage(ComputerBlockEntity.refusal());
                    return InteractionResult.SUCCESS;
                }
                player.openMenu(server);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
