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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
import qbotics.network.Medium;
import qbotics.network.NetworkDevice;

/**
 * Cable: joins Qbotics blocks into one network (design doc §11.0), and a crafting
 * ingredient. {@link CableNetwork} walks along cables to find which devices are joined.
 *
 * <p>Each of a cable's six sides is one of: nothing, an arm reaching to a neighbour, or a
 * plate (a Serial Bus, or a world plate) that shares the cable's block and points at the
 * neighbour on that side. So one cable in a tight space can serve up to six containers.
 *
 * <p>Only the arms are in the block state (one yes-or-no for each side). The plates are kept
 * by the cable's {@link PortBlockEntity}, which a cable has once a plate has been put on it,
 * and are drawn by a model built in code that reads them from there. Keeping plates out of the
 * block state means more kinds of plate don't multiply the cable's states.
 */
public class CableBlock extends Block implements EntityBlock {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    /** Blocks a cable reaches out to: other cables, computers, and every network block. */
    public static final TagKey<Block> CONNECTABLE = TagKey.create(Registries.BLOCK, Qbotics.id("cable_connectable"));

    /** One property for each side, named after it: true where an arm reaches out that way. */
    public static final Map<Direction, BooleanProperty> ARMS = new EnumMap<>(Direction.class);
    /** The cable's own shapes, and the space a plate takes up, for aiming at it and bumping into it. */
    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final Map<Direction, VoxelShape> ARM_SHAPES = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> PLATES = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.values()) {
            ARMS.put(direction, BooleanProperty.create(direction.getSerializedName()));
        }
        ARM_SHAPES.put(Direction.NORTH, Block.box(6, 6, 0, 10, 10, 6));
        ARM_SHAPES.put(Direction.SOUTH, Block.box(6, 6, 10, 10, 10, 16));
        ARM_SHAPES.put(Direction.WEST, Block.box(0, 6, 6, 6, 10, 10));
        ARM_SHAPES.put(Direction.EAST, Block.box(10, 6, 6, 16, 10, 10));
        ARM_SHAPES.put(Direction.DOWN, Block.box(6, 0, 6, 10, 6, 10));
        ARM_SHAPES.put(Direction.UP, Block.box(6, 10, 6, 10, 16, 10));
        PLATES.put(Direction.NORTH, Block.box(3, 3, 0, 13, 13, 6));
        PLATES.put(Direction.SOUTH, Block.box(3, 3, 10, 13, 13, 16));
        PLATES.put(Direction.WEST, Block.box(0, 3, 3, 6, 13, 13));
        PLATES.put(Direction.EAST, Block.box(10, 3, 3, 16, 13, 13));
        PLATES.put(Direction.DOWN, Block.box(3, 0, 3, 13, 6, 13));
        PLATES.put(Direction.UP, Block.box(3, 10, 3, 13, 16, 13));
    }

    /** Shapes by which sides have arms (the low six bits) and which carry plates (the next six). */
    private static final Map<Integer, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    public CableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (BooleanProperty property : ARMS.values()) {
            state = state.setValue(property, false);
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
            builder.add(ARMS.get(direction));
        }
    }

    /** True if this is a cable with an arm reaching out on that side. */
    public static boolean arm(BlockState state, Direction direction) {
        return state.getBlock() instanceof CableBlock && state.getValue(ARMS.get(direction));
    }

    /** The cable's plate holder, or null where there is no cable or it has never carried a plate. */
    private static @Nullable PortBlockEntity host(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PortBlockEntity host ? host : null;
    }

    /** The kind of plate on a side of the cable at a position, or null if that side has none (or there is no cable). */
    public static NetworkDevice.@Nullable Kind plateKind(BlockGetter level, BlockPos pos, Direction direction) {
        PortBlockEntity host = host(level, pos);
        return host == null ? null : host.kindAt(direction);
    }

    public static boolean hasPlates(BlockGetter level, BlockPos pos) {
        PortBlockEntity host = host(level, pos);
        return host != null && host.hasPlates();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Every cable has a plate holder, most of them empty. A player's game only keeps the
        // block entity data the server sends where the block itself makes one, so this can't
        // wait for the first plate.
        return new PortBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int key = 0;
        for (Direction direction : Direction.values()) {
            if (state.getValue(ARMS.get(direction))) {
                key |= 1 << direction.get3DDataValue();
            }
        }
        PortBlockEntity host = host(level, pos);
        if (host != null) {
            key |= host.plateMask() << 6;
        }
        return SHAPES.computeIfAbsent(key, k -> {
            VoxelShape shape = CORE;
            for (Direction direction : Direction.values()) {
                if ((k & (1 << direction.get3DDataValue())) != 0) {
                    shape = Shapes.or(shape, ARM_SHAPES.get(direction));
                }
                if ((k & (1 << (6 + direction.get3DDataValue()))) != 0) {
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

    /** This cable with an arm towards every neighbour it joins. Sides that carry plates get none. */
    public static BlockState withConnections(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            state = connect(state, level, pos, direction);
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        // A block changing its state tells its neighbours this way even when it sends no block
        // update (a crop growing): this is how an observer notices, and so how a Logic Pin does.
        if (level instanceof Level world && !world.isClientSide() && plateKind(level, pos, directionToNeighbour) == NetworkDevice.Kind.PIN
                && world.getBlockEntity(pos) instanceof PortBlockEntity host) {
            WorldPlates.sense(world, host.port(directionToNeighbour));
        }
        return connect(state, level, pos, directionToNeighbour);
    }

    // ---- redstone, for Logic Pins (design doc §11.0.1) ----------------------------------

    /** Any cable may carry a Logic Pin; those without one give no signal. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /** {@code direction} points from the block asking towards this cable. */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return WorldPlates.signalOut(level, pos, direction.getOpposite());
    }

    /** A pin powers the block it points at strongly, as a lever does the block it is on. */
    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return WorldPlates.signalOut(level, pos, direction.getOpposite());
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            net.minecraft.world.level.redstone.@Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        WorldPlates.sense(level, pos);
    }

    /** Scheduled when a pin starts a pulse: ends it. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        WorldPlates.endPulses(level, pos);
    }

    private static BlockState connect(BlockState cable, BlockGetter level, BlockPos pos, Direction direction) {
        boolean joins = false;
        if (plateKind(level, pos, direction) == null) {
            // A plate takes up its side, and a plate doesn't join the network through its face,
            // so nothing reaches out to one either.
            BlockPos neighbourPos = pos.relative(direction);
            BlockState neighbour = level.getBlockState(neighbourPos);
            joins = neighbour.is(CONNECTABLE) && plateKind(level, neighbourPos, direction.getOpposite()) == null;
        }
        return cable.setValue(ARMS.get(direction), joins);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        CableNetwork.changed(pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CableNetwork.changed(pos);
    }

    // ---- plates --------------------------------------------------------------------------

    /** Every plate on the cable at a position; empty if there is no cable there or it carries none. */
    public static List<CablePort> ports(Level level, BlockPos pos) {
        PortBlockEntity host = host(level, pos);
        return host == null ? List.of() : host.ports();
    }

    /**
     * Puts a plate on one side of a cable. Returns the plate's device, or null if that side
     * already carries a plate.
     */
    public static @Nullable CablePort attach(Level level, BlockPos pos, NetworkDevice.Kind kind, Direction direction) {
        return attach(level, pos, kind, Medium.ITEM, direction);
    }

    /** The same, for a plate that carries fluid or energy. */
    public static @Nullable CablePort attach(Level level, BlockPos pos, NetworkDevice.Kind kind, Medium medium, Direction direction) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CableBlock) || plateKind(level, pos, direction) != null) {
            return null;
        }
        PortBlockEntity host = host(level, pos);
        if (host == null) {
            host = new PortBlockEntity(pos, state);
            level.setBlockEntity(host);
        }
        // The plate is recorded first, so the arm on its side (and a neighbour's arm reaching to it) is dropped.
        CablePort port = host.add(direction, kind, medium);
        BlockState changed = withConnections(level, pos, state);
        level.setBlock(pos, changed, Block.UPDATE_ALL);
        CableNetwork.changed(pos);
        if (kind == NetworkDevice.Kind.PIN) {
            WorldPlates.sense(level, port); // what it sees now is where it starts counting from
            // Redstone dust beside it may now join to it.
            changed.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
        level.sendBlockUpdated(pos, state, changed, Block.UPDATE_ALL); // tells players' games about the plate
        return port;
    }

    /** Takes the plate off one side of a cable and returns it as an item (named, if the plate was). */
    public static ItemStack detach(Level level, BlockPos pos, Direction direction) {
        BlockState state = level.getBlockState(pos);
        PortBlockEntity host = host(level, pos);
        if (host == null || host.kindAt(direction) == null) {
            return ItemStack.EMPTY;
        }
        ItemStack plate = plateItem(direction, host);
        // Forget the plate before the arms are worked out again, so it isn't mistaken for one that is still there.
        // The holder stays, empty if this was the last plate: players' games are told about it as it changes.
        boolean pin = host.kindAt(direction) == NetworkDevice.Kind.PIN;
        CablePort leaving = host.port(direction);
        if (leaving != null && leaving.tunnel() != null && level instanceof ServerLevel server) {
            qbotics.network.Tunnels.unlink(server, leaving); // its tunnel closes at both ends
        }
        host.forget(direction);
        BlockState changed = withConnections(level, pos, state);
        level.setBlock(pos, changed, Block.UPDATE_ALL);
        level.sendBlockUpdated(pos, state, changed, Block.UPDATE_ALL);
        CableNetwork.changed(pos);
        if (pin) {
            // Its signal is gone, and redstone dust that joined to it lets go.
            level.updateNeighborsAt(pos, changed.getBlock(), null);
            level.updateNeighborsAt(pos.relative(direction), changed.getBlock(), null);
            changed.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
        return plate;
    }

    private static ItemStack plateItem(Direction direction, @Nullable BlockEntity entity) {
        CablePort port = entity instanceof PortBlockEntity host ? host.port(direction) : null;
        if (port == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(QboticsContent.plateItem(port.kind(), port.medium()));
        if (!port.deviceName().isEmpty()) {
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
            ItemStack plate = plateItem(direction, entity);
            if (!plate.isEmpty()) {
                drops.add(plate);
            }
        }
        return drops;
    }

    /** Which plate a click landed on, or null if it was on the cable itself. */
    private static @Nullable Direction plateAt(BlockGetter level, BlockPos pos, BlockHitResult hit) {
        return plateAt(level, pos, hit.getLocation());
    }

    /** Which of a cable's plates a point in the world lies on, or null. */
    public static @Nullable Direction plateAt(BlockGetter level, BlockPos pos, Vec3 point) {
        Vec3 at = point.subtract(pos.getX(), pos.getY(), pos.getZ());
        for (Direction direction : Direction.values()) {
            if (plateKind(level, pos, direction) != null && PLATES.get(direction).bounds().inflate(0.01).contains(at)) {
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
    public static @Nullable Direction plateAimedAt(BlockGetter level, BlockPos pos, Player player) {
        if (!hasPlates(level, pos)) {
            return null;
        }
        return player.pick(player.blockInteractionRange(), 1.0f, false) instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)
                ? plateAt(level, pos, hit) : null;
    }

    /**
     * A player has finished breaking this cable. If they were aiming at a plate, only that
     * plate breaks off and this returns true: the cable and its other plates must stay.
     * Each loader calls this from its own "block broken by a player" hook.
     */
    public static boolean breakPlateInstead(BlockState state, Level level, BlockPos pos, Player player) {
        Direction direction = plateAimedAt(level, pos, player);
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
        Direction direction = plateAt(level, pos, hit);
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

    // Turning or mirroring a cable (in a structure) turns its arms. Its plates, kept apart from
    // the block state, are not turned: they keep pointing as they did.
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState turned = state;
        for (Direction direction : Direction.values()) {
            turned = turned.setValue(ARMS.get(rotation.rotate(direction)), state.getValue(ARMS.get(direction)));
        }
        return turned;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState flipped = state;
        for (Direction direction : Direction.values()) {
            flipped = flipped.setValue(ARMS.get(mirror.mirror(direction)), state.getValue(ARMS.get(direction)));
        }
        return flipped;
    }
}
