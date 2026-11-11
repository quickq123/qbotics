package qbotics.block;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;

/**
 * Cable: joins Qbotics blocks into one network (design doc §11.0), and a crafting
 * ingredient. {@link CableNetwork} walks along cables to find which devices are joined.
 *
 * <p>A cable can also carry one plate (an Input, an Output or a Bus) on one of its faces,
 * sharing the cable's block. The plate points at the neighbour on that side and is a device
 * on the network; its details are kept in a {@link PortBlockEntity}, which only cables
 * with a plate have.
 */
public class CableBlock extends PipeBlock implements EntityBlock {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    /** Blocks a cable reaches out to: other cables, computers, and every network block. */
    public static final TagKey<Block> CONNECTABLE = TagKey.create(Registries.BLOCK, Qbotics.id("cable_connectable"));

    /** The plate a cable carries. */
    public enum Plate implements StringRepresentable {
        NONE("none", null), INPUT("input", NetworkDevice.Kind.INPUT), OUTPUT("output", NetworkDevice.Kind.OUTPUT),
        BUS("bus", NetworkDevice.Kind.BUS);

        private final String word;
        /** What kind of device the plate is; null for no plate. */
        public final NetworkDevice.@Nullable Kind kind;

        Plate(String word, NetworkDevice.@Nullable Kind kind) {
            this.word = word;
            this.kind = kind;
        }

        @Override
        public String getSerializedName() {
            return word;
        }

        public static Plate of(NetworkDevice.Kind kind) {
            for (Plate plate : values()) {
                if (plate.kind == kind) {
                    return plate;
                }
            }
            return NONE;
        }
    }

    public static final EnumProperty<Plate> PLATE = EnumProperty.create("plate", Plate.class);
    /** Which way the plate points. Means nothing while there is no plate. */
    public static final EnumProperty<Direction> PLATE_FACING = BlockStateProperties.FACING;

    /** Thickness of the cable in pixels (sixteenths of a block). */
    private static final float THICKNESS = 4.0f;
    /** The space a plate takes up on each side, for aiming at it and bumping into it. */
    private static final Map<Direction, VoxelShape> PLATE_SHAPES = new EnumMap<>(Direction.class);

    static {
        PLATE_SHAPES.put(Direction.NORTH, Block.box(3, 3, 0, 13, 13, 6));
        PLATE_SHAPES.put(Direction.SOUTH, Block.box(3, 3, 10, 13, 13, 16));
        PLATE_SHAPES.put(Direction.WEST, Block.box(0, 3, 3, 6, 13, 13));
        PLATE_SHAPES.put(Direction.EAST, Block.box(10, 3, 3, 16, 13, 13));
        PLATE_SHAPES.put(Direction.DOWN, Block.box(3, 0, 3, 13, 6, 13));
        PLATE_SHAPES.put(Direction.UP, Block.box(3, 10, 3, 13, 16, 13));
    }

    private final Map<BlockState, VoxelShape> shapesWithPlate = new java.util.concurrent.ConcurrentHashMap<>();

    public CableBlock(BlockBehaviour.Properties properties) {
        super(THICKNESS, properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false)
                .setValue(PLATE, Plate.NONE).setValue(PLATE_FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<CableBlock> codec() {
        return CODEC;
    }

    /** The direction this cable's plate points, or null if it has no plate. */
    public static @Nullable Direction plateSide(BlockState state) {
        return state.getBlock() instanceof CableBlock && state.getValue(PLATE) != Plate.NONE ? state.getValue(PLATE_FACING) : null;
    }

    /** This cable with a plate on one side. Used when a plate is attached, and by tests. */
    public BlockState withPlate(NetworkDevice.Kind kind, Direction side) {
        return defaultBlockState().setValue(PLATE, Plate.of(kind)).setValue(PLATE_FACING, side);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PLATE) == Plate.NONE ? null : new PortBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape cable = super.getShape(state, level, pos, context);
        Direction side = plateSide(state);
        if (side == null) {
            return cable;
        }
        return shapesWithPlate.computeIfAbsent(state, s -> Shapes.or(cable, PLATE_SHAPES.get(side)));
    }

    // ---- connections -------------------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withConnections(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    public static BlockState withConnections(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
                    connects(state, level.getBlockState(pos.relative(direction)), direction));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return state.setValue(PROPERTY_BY_DIRECTION.get(directionToNeighbour), connects(state, neighbourState, directionToNeighbour));
    }

    /** True if this cable should reach out to the neighbour that lies in the given direction. */
    private static boolean connects(BlockState cable, BlockState neighbour, Direction direction) {
        // A plate takes up its side of the cable, and doesn't join the network through its face.
        if (plateSide(cable) == direction || plateSide(neighbour) == direction.getOpposite()) {
            return false;
        }
        return neighbour.is(CONNECTABLE);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        CableNetwork.changed();
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CableNetwork.changed();
    }

    // ---- plates --------------------------------------------------------------------------

    /**
     * Puts a plate on a cable that has none. Returns the plate's device, or null if the
     * cable already carries one.
     */
    public static @Nullable PortBlockEntity attach(Level level, BlockPos pos, NetworkDevice.Kind kind, Direction side) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CableBlock) || state.getValue(PLATE) != Plate.NONE) {
            return null;
        }
        BlockState changed = withConnections(level, pos, state.setValue(PLATE, Plate.of(kind)).setValue(PLATE_FACING, side));
        level.setBlock(pos, changed, Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof PortBlockEntity)) {
            level.setBlockEntity(new PortBlockEntity(pos, changed));
        }
        CableNetwork.changed();
        return level.getBlockEntity(pos) instanceof PortBlockEntity port ? port : null;
    }

    /** Takes the plate off a cable and returns it as an item (named, if the plate was). */
    public static ItemStack detach(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (plateSide(state) == null) {
            return ItemStack.EMPTY;
        }
        ItemStack plate = plateItem(state, level.getBlockEntity(pos));
        level.removeBlockEntity(pos);
        level.setBlock(pos, withConnections(level, pos, state.setValue(PLATE, Plate.NONE).setValue(PLATE_FACING, Direction.NORTH)),
                Block.UPDATE_ALL);
        CableNetwork.changed();
        return plate;
    }

    private static ItemStack plateItem(BlockState state, @Nullable BlockEntity entity) {
        NetworkDevice.Kind kind = state.getValue(PLATE).kind;
        if (kind == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(QboticsContent.PLATE_ITEMS.get(kind).get());
        if (entity instanceof PortBlockEntity port && !port.deviceName().isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(port.deviceName()));
        }
        return stack;
    }

    /** Breaking the cable drops its plate too. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        ItemStack plate = plateItem(state, params.getOptionalParameter(LootContextParams.BLOCK_ENTITY));
        if (!plate.isEmpty()) {
            drops.add(plate);
        }
        return drops;
    }

    /**
     * Right-click a cable's plate with an empty hand to read its addresses and what it points
     * at; sneak and right-click to take the plate off.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (plateSide(state) == null) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            if (player.isSecondaryUseActive()) {
                ItemStack plate = detach(level, pos);
                if (!player.hasInfiniteMaterials() || !player.getInventory().contains(plate)) {
                    player.getInventory().placeItemBackInInventory(plate);
                }
            } else {
                CableNetwork.at(serverLevel, pos); // makes sure it has been given an address
                Block target = level.getBlockState(port.targetPos()).getBlock();
                serverPlayer.sendOverlayMessage(Component.translatable("message.qbotics.port",
                        describe(port), BuiltInRegistries.BLOCK.getKey(target).toString()));
            }
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
        return state.setValue(PLATE_FACING, rotation.rotate(state.getValue(PLATE_FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(PLATE_FACING, mirror.mirror(state.getValue(PLATE_FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, PLATE, PLATE_FACING);
    }
}
