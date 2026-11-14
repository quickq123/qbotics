package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import qbotics.network.NetworkDevice;

/**
 * The game interfaces: blocks that put a part of the game itself on the network. The Player
 * Interface is a bus on the player who placed it (their inventory, armour and off hand); the
 * Chat Interface hears the server's chat, writes to it and runs commands as its owner; the
 * Command Interface runs commands as a command block does, and is only had by spawning it in.
 */
public class GameInterfaceBlock extends BaseEntityBlock {
    public static final MapCodec<GameInterfaceBlock> PLAYER_CODEC = simpleCodec(GameInterfaceBlock::player);
    public static final MapCodec<GameInterfaceBlock> CHAT_CODEC = simpleCodec(GameInterfaceBlock::chat);
    public static final MapCodec<GameInterfaceBlock> COMMAND_CODEC = simpleCodec(GameInterfaceBlock::command);

    public final NetworkDevice.Kind kind;

    private GameInterfaceBlock(NetworkDevice.Kind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public static GameInterfaceBlock player(BlockBehaviour.Properties properties) {
        return new GameInterfaceBlock(NetworkDevice.Kind.PLAYER, properties);
    }

    public static GameInterfaceBlock chat(BlockBehaviour.Properties properties) {
        return new GameInterfaceBlock(NetworkDevice.Kind.CHAT, properties);
    }

    public static GameInterfaceBlock command(BlockBehaviour.Properties properties) {
        return new GameInterfaceBlock(NetworkDevice.Kind.COMMAND, properties);
    }

    @Override
    protected MapCodec<GameInterfaceBlock> codec() {
        return switch (kind) {
            case CHAT -> CHAT_CODEC;
            case COMMAND -> COMMAND_CODEC;
            default -> PLAYER_CODEC;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GameInterfaceBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GameInterfaceBlockEntity device) {
            device.placedBy(placer instanceof Player player ? player : null);
        }
    }

    /** Right-click: who it works for, and whether it can now. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof GameInterfaceBlockEntity device) {
            CableNetwork.at(serverLevel, pos); // makes sure it has an address to show
            serverPlayer.sendOverlayMessage(Component.literal(device.status(serverLevel.getServer())));
        }
        return InteractionResult.SUCCESS;
    }
}
