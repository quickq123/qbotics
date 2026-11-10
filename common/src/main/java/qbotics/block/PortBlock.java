package qbotics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;

/**
 * The bus and the output (design doc §11.0). Both point at the block they were placed
 * against and work on that block's contents. An output only puts items in; a bus can also
 * read the contents and take items out.
 */
public class PortBlock extends BaseEntityBlock {
    public static final MapCodec<PortBlock> BUS_CODEC = simpleCodec(PortBlock::bus);
    public static final MapCodec<PortBlock> OUTPUT_CODEC = simpleCodec(PortBlock::output);
    /** The direction from this block to its target. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    private final NetworkDevice.Kind kind;

    private PortBlock(NetworkDevice.Kind kind, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static PortBlock bus(BlockBehaviour.Properties properties) {
        return new PortBlock(NetworkDevice.Kind.BUS, properties);
    }

    public static PortBlock output(BlockBehaviour.Properties properties) {
        return new PortBlock(NetworkDevice.Kind.OUTPUT, properties);
    }

    public NetworkDevice.Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<PortBlock> codec() {
        return kind == NetworkDevice.Kind.BUS ? BUS_CODEC : OUTPUT_CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PortBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Point at the block it was placed against: click a chest to attach to that chest.
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            port.onPlaced(placer instanceof Player player ? player.getUUID() : null);
        }
    }

    /** Right-click: tells the player the block's addresses and what it is attached to. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            CableNetwork.at(serverLevel, pos); // makes sure it has been given an address
            Block target = level.getBlockState(port.targetPos()).getBlock();
            serverPlayer.sendOverlayMessage(Component.translatable("message.qbotics.port",
                    describe(port), BuiltInRegistries.BLOCK.getKey(target).toString()));
        }
        return InteractionResult.SUCCESS;
    }

    /** "bus furnace_bus 192.168.1.4 02:1A:2B:3C:4D:5E" */
    public static String describe(NetworkDevice device) {
        String name = device.deviceName().isEmpty() ? "" : " \"" + device.deviceName() + "\"";
        return device.kind().word + name + "  " + device.ip() + "  " + device.mac();
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
