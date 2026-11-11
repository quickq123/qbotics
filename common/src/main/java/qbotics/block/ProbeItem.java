package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.net.ProbePayloads;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;
import qbotics.network.RadioNetwork;

/**
 * The Network Probe: right-click any device on a network (a computer, an interface, an
 * antenna, a plate on a cable) to see what it is called and every way of addressing it,
 * each ready to copy into a program. The same pop-up gives the device a name, which is the
 * easiest thing to write in code (design doc §9.3).
 */
public class ProbeItem extends Item {
    /** How far from a device a player may be and still rename it. */
    private static final double MAX_RENAME_DISTANCE = 8.0;

    public ProbeItem(Item.Properties properties) {
        super(properties);
    }

    /** True if the player is holding a probe. Devices then leave the right-click to it. */
    public static boolean heldBy(Player player) {
        return player.getMainHandItem().getItem() instanceof ProbeItem;
    }

    /** For a plate on a cable, its side's number; -1 for a device that is a whole block. */
    private static int sideNumber(@Nullable Direction side) {
        return side == null ? -1 : side.get3DDataValue();
    }

    /** The device at a position: the plate on one side of a cable, or (with side -1) the block itself. */
    public static @Nullable NetworkDevice deviceAt(Level level, BlockPos pos, int side) {
        if (side >= 0 && side < 6) {
            return level.getBlockEntity(pos) instanceof PortBlockEntity host ? host.port(Direction.from3DDataValue(side)) : null;
        }
        return level.getBlockEntity(pos) instanceof NetworkDevice device && !device.isRemoved() ? device : null;
    }

    /**
     * What the probe shows for a click on a block, or null if the click wasn't on a device.
     *
     * @param clicked where on the block the click landed; it picks out one plate on a cable
     */
    public static ProbePayloads.@Nullable Info inspect(ServerLevel level, BlockPos pos, Vec3 clicked) {
        BlockState state = level.getBlockState(pos);
        int side = state.getBlock() instanceof CableBlock ? sideNumber(CableBlock.plateAt(state, pos, clicked)) : -1;
        if (state.getBlock() instanceof CableBlock && side < 0) {
            return null; // the cable itself has no address
        }
        return describe(level, pos, side);
    }

    private static ProbePayloads.@Nullable Info describe(ServerLevel level, BlockPos pos, int side) {
        CableNetwork network = CableNetwork.at(level, pos); // gives the device its address, if it had none
        NetworkDevice device = deviceAt(level, pos, side);
        if (device == null) {
            return null;
        }
        AntennaBlockEntity gateway = network.gateway();
        String external = gateway == null || device.hostNumber() <= 0 ? ""
                : gateway.externalIp() + ":" + (RadioNetwork.PORT_BASE + device.hostNumber());
        return new ProbePayloads.Info(pos, side, device.kind().word, device.deviceName(), device.ip(), device.mac(), external);
    }

    /** A player asked, from the probe's pop-up, to name a device. Returns what the device is now, or null. */
    public static ProbePayloads.@Nullable Info rename(ServerLevel level, @Nullable Player player, ProbePayloads.Rename request) {
        BlockPos pos = request.pos();
        if (!level.isLoaded(pos) || (player != null && player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_RENAME_DISTANCE * MAX_RENAME_DISTANCE)) {
            return null;
        }
        NetworkDevice device = deviceAt(level, pos, request.side());
        if (device == null) {
            return null;
        }
        device.setDeviceName(request.name());
        return describe(level, pos, request.side());
    }

    /** The handler for a rename message from a player's game. */
    public static void onRename(ServerPlayer player, ProbePayloads.Rename request) {
        ProbePayloads.Info info = rename(player.level(), player, request);
        if (info != null) {
            Qbotics.platform().sendToPlayer(player, info);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level instanceof ServerLevel serverLevel) || !(context.getPlayer() instanceof ServerPlayer player)) {
            // In the player's game: claim the click if this could be a device; the server decides.
            BlockState state = level.getBlockState(pos);
            boolean device = state.getBlock() instanceof CableBlock ? CableBlock.plateAt(state, pos, context.getClickLocation()) != null
                    : level.getBlockEntity(pos) instanceof NetworkDevice;
            return device ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        ProbePayloads.Info info = inspect(serverLevel, pos, context.getClickLocation());
        if (info == null) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.probe_nothing"));
            return InteractionResult.PASS;
        }
        Qbotics.platform().sendToPlayer(player, info);
        return InteractionResult.SUCCESS;
    }
}
