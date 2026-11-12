package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
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

    /** The probe's modes, changed by sneaking and scrolling. The number is kept on the item. */
    public static final int LABELS = 0;
    public static final int SLOTS = 1;
    /** Right-click a bus, then another, to open a tunnel between them (design doc §11.0.6). */
    public static final int TUNNELS = 2;
    public static final int MODES = 3;

    public static int mode(ItemStack stack) {
        Integer mode = stack.get(QboticsContent.PROBE_MODE.get());
        return mode == null || mode < 0 || mode >= MODES ? LABELS : mode;
    }

    /**
     * True if the player is holding a probe in its Labels mode. Devices then leave the
     * right-click to it, so it can read them.
     */
    public static boolean heldBy(Player player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof ProbeItem && mode(held) == LABELS;
    }

    /** True if the player is holding a probe in its Slots mode: container screens then number their slots. */
    public static boolean showsSlots(Player player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof ProbeItem && mode(held) == SLOTS;
    }

    /** A player sneaked and scrolled with the probe in hand. */
    public static void onMode(ServerPlayer player, ProbePayloads.Mode request) {
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof ProbeItem && request.mode() >= 0 && request.mode() < MODES) {
            held.set(QboticsContent.PROBE_MODE.get(), request.mode());
            player.sendOverlayMessage(Component.translatable("message.qbotics.probe_mode." + request.mode()));
        }
    }

    /** The item's name says which mode it is in. */
    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.qbotics.probe.mode." + mode(stack));
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
        // Any block of a screen stands for the screen, whose identity is in its top-left block.
        if (level.getBlockEntity(pos) instanceof DisplayBlockEntity) {
            return qbotics.display.Displays.screenOf(level, pos);
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
        int side = state.getBlock() instanceof CableBlock ? sideNumber(CableBlock.plateAt(level, pos, clicked)) : -1;
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
        return new ProbePayloads.Info(pos, side, device.kindWord(), device.deviceName(), device.ip(), device.mac(), external);
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

    /** The bus each player chose first, in Tunnels mode, and when. */
    private record Chosen(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos, Direction side, java.util.UUID id, long at) {
    }

    private static final java.util.Map<java.util.UUID, Chosen> CHOSEN = new java.util.HashMap<>();
    /** A choice older than this (in ticks) is forgotten. */
    private static final long CHOICE_TICKS = 2 * 60 * 20;

    /** The bus a player chose earlier, if it is still there. */
    private static @Nullable CablePort chosen(ServerPlayer player, Chosen choice) {
        ServerLevel there = player.level().getServer().getLevel(choice.dimension());
        if (there == null || !there.isLoaded(choice.pos()) || player.level().getGameTime() - choice.at() > CHOICE_TICKS) {
            return null;
        }
        CablePort port = there.getBlockEntity(choice.pos()) instanceof PortBlockEntity host ? host.port(choice.side()) : null;
        return port != null && choice.id().equals(port.deviceId()) ? port : null;
    }

    /**
     * Tunnels mode: the first bus clicked is chosen; the next one is linked to it. Clicking
     * the chosen bus again closes its tunnel if it has one, or forgets the choice.
     */
    private static InteractionResult tunnelClick(ServerLevel level, ServerPlayer player, BlockPos pos, Vec3 clicked) {
        Direction side = level.getBlockState(pos).getBlock() instanceof CableBlock ? CableBlock.plateAt(level, pos, clicked) : null;
        CablePort port = side == null || !(level.getBlockEntity(pos) instanceof PortBlockEntity host) ? null : host.port(side);
        if (port == null || port.kind() != NetworkDevice.Kind.BUS) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tunnel.not_bus"));
            return InteractionResult.PASS;
        }
        Chosen choice = CHOSEN.remove(player.getUUID());
        CablePort first = choice == null ? null : chosen(player, choice);
        String here = qbotics.network.Tunnels.label(level, port);
        if (first == port) {
            if (port.tunnel() != null) {
                qbotics.network.Tunnels.unlink(level, port);
                player.sendOverlayMessage(Component.translatable("message.qbotics.tunnel.closed", here));
            } else {
                player.sendOverlayMessage(Component.translatable("message.qbotics.tunnel.cancelled"));
            }
            return InteractionResult.SUCCESS;
        }
        if (first != null && first.level() instanceof ServerLevel firstLevel) {
            String problem = qbotics.network.Tunnels.link(firstLevel, first, level, port);
            player.sendOverlayMessage(problem == null
                    ? Component.translatable("message.qbotics.tunnel.opened", qbotics.network.Tunnels.label(firstLevel, first), here)
                    : Component.translatable("message.qbotics.tunnel.refused", problem));
            return InteractionResult.SUCCESS;
        }
        CHOSEN.put(player.getUUID(), new Chosen(level.dimension(), pos.immutable(), side, port.deviceId(), level.getGameTime()));
        qbotics.network.Tunnels.Far far = port.tunnel() == null ? null : qbotics.network.Tunnels.far(level, port);
        if (port.tunnel() == null) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.tunnel.chosen", here));
        } else {
            String there = far == null ? Component.translatable("message.qbotics.tunnel.out_of_reach").getString()
                    : qbotics.network.Tunnels.label(far.level(), far.port());
            player.sendOverlayMessage(Component.translatable("message.qbotics.tunnel.linked", here, there));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level instanceof ServerLevel serverLevel) || !(context.getPlayer() instanceof ServerPlayer player)) {
            // In the player's game: claim the click if this could be a device; the server decides.
            if (mode(context.getItemInHand()) == SLOTS) {
                return InteractionResult.PASS;
            }
            BlockState state = level.getBlockState(pos);
            boolean device = state.getBlock() instanceof CableBlock ? CableBlock.plateAt(level, pos, context.getClickLocation()) != null
                    : level.getBlockEntity(pos) instanceof NetworkDevice;
            return device ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (mode(context.getItemInHand()) == SLOTS) {
            // A block with slots has already opened its screen by now; this one has none to show.
            player.sendOverlayMessage(Component.translatable("message.qbotics.probe_no_slots"));
            return InteractionResult.PASS;
        }
        if (mode(context.getItemInHand()) == TUNNELS) {
            return tunnelClick(serverLevel, player, pos, context.getClickLocation());
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
