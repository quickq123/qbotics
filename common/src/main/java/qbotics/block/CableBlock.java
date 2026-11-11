package qbotics.block;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
 * <p>Each of a cable's six sides is one of: nothing, an arm reaching to a neighbour, or a
 * plate (an Input, an Output or a Bus) that shares the cable's block and points at the
 * neighbour on that side. So one cable in a tight space can serve up to six containers.
 * Every plate is a device on the network; their details are kept in a
 * {@link PortBlockEntity}, which only cables with a plate have.
 */
public class CableBlock extends Block implements EntityBlock {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    /** Blocks a cable reaches out to: other cables, computers, and every network block. */
    public static final TagKey<Block> CONNECTABLE = TagKey.create(Registries.BLOCK, Qbotics.id("cable_connectable"));

    /** What is on one side of a cable. */
    public enum Side implements StringRepresentable {
        NONE("none", null), CABLE("cable", null), INPUT("input", NetworkDevice.Kind.INPUT),
        OUTPUT("output", NetworkDevice.Kind.OUTPUT), BUS("bus", NetworkDevice.Kind.BUS);

        private final String word;
        /** The kind of device the plate is; null where there is no plate. */
        public final NetworkDevice.@Nullable Kind plate;

        Side(String word, NetworkDevice.@Nullable Kind plate) {
            this.word = word;
            this.plate = plate;
        }

        @Override
        public String getSerializedName() {
            return word;
        }

        static Side of(NetworkDevice.Kind kind) {
            for (Side side : values()) {
                if (side.plate == kind) {
                    return side;
                }
            }
            throw new IllegalArgumentException(kind + " is not a plate");
        }
    }

    /** One property for each side, named after it: north, east, south, west, up, down. */
    public static final Map<Direction, EnumProperty<Side>> SIDES = new EnumMap<>(Direction.class);
    /** The cable's own shapes, and the space a plate takes up, for aiming at it and bumping into it. */
    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final Map<Direction, VoxelShape> ARMS = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> PLATES = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.values()) {
            SIDES.put(direction, EnumProperty.create(direction.getSerializedName(), Side.class));
        }
        ARMS.put(Direction.NORTH, Block.box(6, 6, 0, 10, 10, 6));
        ARMS.put(Direction.SOUTH, Block.box(6, 6, 10, 10, 10, 16));
        ARMS.put(Direction.WEST, Block.box(0, 6, 6, 6, 10, 10));
        ARMS.put(Direction.EAST, Block.box(10, 6, 6, 16, 10, 10));
        ARMS.put(Direction.DOWN, Block.box(6, 0, 6, 10, 6, 10));
        ARMS.put(Direction.UP, Block.box(6, 10, 6, 10, 16, 10));
        PLATES.put(Direction.NORTH, Block.box(3, 3, 0, 13, 13, 6));
        PLATES.put(Direction.SOUTH, Block.box(3, 3, 10, 13, 13, 16));
        PLATES.put(Direction.WEST, Block.box(0, 3, 3, 6, 13, 13));
        PLATES.put(Direction.EAST, Block.box(10, 3, 3, 16, 13, 13));
        PLATES.put(Direction.DOWN, Block.box(3, 0, 3, 13, 6, 13));
        PLATES.put(Direction.UP, Block.box(3, 10, 3, 13, 16, 13));
    }

    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();

    public CableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (EnumProperty<Side> property : SIDES.values()) {
            state = state.setValue(property, Side.NONE);
        }
        registerDefaultState(state);
    }

    @Override
    protected MapCodec<CableBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction direction : Direction.values()) {
            builder.add(SIDES.get(direction));
        }
    }

    public static Side side(BlockState state, Direction direction) {
        return state.getBlock() instanceof CableBlock ? state.getValue(SIDES.get(direction)) : Side.NONE;
    }

    /** The kind of plate on a side of this cable, or null if that side has none (or this is no cable). */
    public static NetworkDevice.@Nullable Kind plateKind(BlockState state, Direction direction) {
        return side(state, direction).plate;
    }

    public static boolean hasPlates(BlockState state) {
        for (Direction direction : Direction.values()) {
            if (plateKind(state, direction) != null) {
                return true;
            }
        }
        return false;
    }

    /** A cable with one plate. Used by tests; players attach plates with {@link PlateItem}. */
    public BlockState withPlate(NetworkDevice.Kind kind, Direction direction) {
        return defaultBlockState().setValue(SIDES.get(direction), Side.of(kind));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return hasPlates(state) ? new PortBlockEntity(pos, state) : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.computeIfAbsent(state, s -> {
            VoxelShape shape = CORE;
            for (Direction direction : Direction.values()) {
                Side side = side(s, direction);
                if (side == Side.CABLE) {
                    shape = Shapes.or(shape, ARMS.get(direction));
                } else if (side.plate != null) {
                    shape = Shapes.or(shape, PLATES.get(direction));
                }
            }
            return shape;
        });
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    // ---- connections -------------------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withConnections(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** This cable with an arm towards every neighbour it joins. Sides that carry plates are left alone. */
    public static BlockState withConnections(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = connect(state, level.getBlockState(pos.relative(direction)), direction);
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        return connect(state, neighbourState, directionToNeighbour);
    }

    private static BlockState connect(BlockState cable, BlockState neighbour, Direction direction) {
        if (plateKind(cable, direction) != null) {
            return cable; // a plate takes up its side
        }
        // A plate doesn't join the network through its face, so nothing reaches out to one.
        boolean joins = neighbour.is(CONNECTABLE) && plateKind(neighbour, direction.getOpposite()) == null;
        return cable.setValue(SIDES.get(direction), joins ? Side.CABLE : Side.NONE);
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

    /** Every plate on the cable at a position; empty if there is no cable there or it carries none. */
    public static List<CablePort> ports(Level level, BlockPos pos) {
        return hasPlates(level.getBlockState(pos)) && level.getBlockEntity(pos) instanceof PortBlockEntity host ? host.ports() : List.of();
    }

    /**
     * Puts a plate on one side of a cable. Returns the plate's device, or null if that side
     * already carries a plate.
     */
    public static @Nullable CablePort attach(Level level, BlockPos pos, NetworkDevice.Kind kind, Direction direction) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CableBlock) || plateKind(state, direction) != null) {
            return null;
        }
        BlockState changed = state.setValue(SIDES.get(direction), Side.of(kind));
        level.setBlock(pos, changed, Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof PortBlockEntity)) {
            level.setBlockEntity(new PortBlockEntity(pos, changed));
        }
        CableNetwork.changed();
        return level.getBlockEntity(pos) instanceof PortBlockEntity host ? host.port(direction) : null;
    }

    /** Takes the plate off one side of a cable and returns it as an item (named, if the plate was). */
    public static ItemStack detach(Level level, BlockPos pos, Direction direction) {
        BlockState state = level.getBlockState(pos);
        if (plateKind(state, direction) == null) {
            return ItemStack.EMPTY;
        }
        PortBlockEntity host = level.getBlockEntity(pos) instanceof PortBlockEntity found ? found : null;
        ItemStack plate = plateItem(state, direction, host);
        BlockState bare = state.setValue(SIDES.get(direction), Side.NONE);
        // Forget the plate before the state changes, so it isn't mistaken for one that is still there.
        if (host != null) {
            host.forget(direction);
        }
        if (!hasPlates(bare)) {
            level.removeBlockEntity(pos);
        }
        level.setBlock(pos, withConnections(level, pos, bare), Block.UPDATE_ALL);
        CableNetwork.changed();
        return plate;
    }

    private static ItemStack plateItem(BlockState state, Direction direction, @Nullable BlockEntity entity) {
        NetworkDevice.Kind kind = plateKind(state, direction);
        if (kind == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(QboticsContent.PLATE_ITEMS.get(kind).get());
        CablePort port = entity instanceof PortBlockEntity host ? host.port(direction) : null;
        if (port != null && !port.deviceName().isEmpty()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(port.deviceName()));
        }
        return stack;
    }

    /** Breaking the cable drops its plates too. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        for (Direction direction : Direction.values()) {
            ItemStack plate = plateItem(state, direction, entity);
            if (!plate.isEmpty()) {
                drops.add(plate);
            }
        }
        return drops;
    }

    /** Which plate a click landed on, or null if it was on the cable itself. */
    private static @Nullable Direction plateAt(BlockState state, BlockPos pos, BlockHitResult hit) {
        return plateAt(state, pos, hit.getLocation());
    }

    /** Which of a cable's plates a point in the world lies on, or null. */
    public static @Nullable Direction plateAt(BlockState state, BlockPos pos, Vec3 point) {
        Vec3 at = point.subtract(pos.getX(), pos.getY(), pos.getZ());
        for (Direction direction : Direction.values()) {
            if (plateKind(state, direction) != null && PLATES.get(direction).bounds().inflate(0.01).contains(at)) {
                return direction;
            }
        }
        return null;
    }

    /**
     * The plate a player is looking at on this cable, or null if they are looking at the
     * cable itself (or somewhere else). Breaking uses it: aimed at a plate, only that plate
     * comes off.
     */
    public static @Nullable Direction plateAimedAt(BlockState state, BlockPos pos, Player player) {
        if (!hasPlates(state)) {
            return null;
        }
        return player.pick(player.blockInteractionRange(), 1.0f, false) instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)
                ? plateAt(state, pos, hit) : null;
    }

    /**
     * A player has finished breaking this cable. If they were aiming at a plate, only that
     * plate breaks off and this returns true: the cable and its other plates must stay.
     * Each loader calls this from its own "block broken by a player" hook.
     */
    public static boolean breakPlateInstead(BlockState state, Level level, BlockPos pos, Player player) {
        Direction direction = plateAimedAt(state, pos, player);
        if (direction == null) {
            return false;
        }
        if (!level.isClientSide()) {
            ItemStack plate = detach(level, pos, direction);
            if (!player.hasInfiniteMaterials()) {
                Block.popResource(level, pos, plate);
            }
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.METAL_BREAK, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        }
        return true;
    }

    /**
     * Right-click a plate with an empty hand to read its addresses and what it points at;
     * sneak and right-click to take that plate off.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (ProbeItem.heldBy(player)) {
            return InteractionResult.PASS; // the probe reads this device instead
        }
        Direction direction = plateAt(state, pos, hit);
        if (direction == null) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof PortBlockEntity host) {
            CablePort port = host.port(direction);
            if (player.isSecondaryUseActive()) {
                ItemStack plate = detach(level, pos, direction);
                if (!player.hasInfiniteMaterials() || !player.getInventory().contains(plate)) {
                    player.getInventory().placeItemBackInInventory(plate);
                }
            } else if (port != null) {
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
        BlockState turned = state;
        for (Direction direction : Direction.values()) {
            turned = turned.setValue(SIDES.get(rotation.rotate(direction)), state.getValue(SIDES.get(direction)));
        }
        return turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState flipped = state;
        for (Direction direction : Direction.values()) {
            flipped = flipped.setValue(SIDES.get(mirror.mirror(direction)), state.getValue(SIDES.get(direction)));
        }
        return flipped;
    }
}
