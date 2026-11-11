package qbotics.limits;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsConfig;

/**
 * Per-player device limits and the bricked state (design doc §5).
 *
 * <ul>
 *   <li>A device placed by a player under their limit is active and counted.</li>
 *   <li>Over the limit it is bricked: inert and costing nothing, its files kept.</li>
 *   <li>A device placed by a machine (a dispenser, a Placer, any stand-in player) is always
 *       bricked. A player can then claim it by right-clicking it, if under their limit.</li>
 *   <li>A bricked device comes back on when its owner right-clicks it while under the limit
 *       (checked at most once a second per player), or is broken and placed again.</li>
 *   <li>Lowering a limit switches nothing off: devices already active stay active.</li>
 * </ul>
 */
public final class Limits {
    public static final String COMPUTER = "computer";
    public static final String ANTENNA = "antenna";

    /** When each player last asked a bricked device to come on, in game ticks. */
    private static final Map<UUID, Long> LAST_CHECK = new HashMap<>();

    private Limits() {
    }

    /** A type's limit from the config; -1 means none. */
    public static int limit(String type) {
        return type.equals(COMPUTER) ? QboticsConfig.computersPerPlayer : QboticsConfig.antennasPerPlayer;
    }

    private static String plural(String type) {
        return type.equals(COMPUTER) ? "computers" : "antennas";
    }

    /** True if a player may have any number of devices: an operator, when the config says so. */
    public static boolean exempt(MinecraftServer server, UUID player) {
        if (!QboticsConfig.operatorsExempt) {
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        return online != null && server.getPlayerList().isOp(online.nameAndId());
    }

    /** True if one more active device of this type would keep the owner within their limit. */
    public static boolean roomFor(MinecraftServer server, UUID owner, String type) {
        int limit = limit(type);
        return limit < 0 || exempt(server, owner) || DeviceRegistry.get(server).count(owner, type) < limit;
    }

    private static void register(ServerLevel level, Limited device) {
        BlockEntity entity = (BlockEntity) device;
        DeviceRegistry.get(level.getServer()).put(new DeviceRegistry.Entry(device.deviceId(), device.owner(), device.limitType(),
                level.dimension().identifier().toString(), entity.getBlockPos().immutable()));
    }

    /**
     * A limited device has just been placed and given its owner. Decides whether it is active
     * or bricked, and tells the player which.
     *
     * @param placer whatever placed it: a player, a machine's stand-in, or null
     */
    public static void placed(ServerLevel level, Limited device, @Nullable Player placer) {
        UUID owner = device.owner();
        if (placer == null || owner == null || Qbotics.platform().isFakePlayer(placer)) {
            // Placed by a machine: always bricked (design doc §5.4).
            device.setBricked(true);
            return;
        }
        if (roomFor(level.getServer(), owner, device.limitType())) {
            device.setBricked(false);
            register(level, device);
        } else {
            device.setBricked(true);
            if (placer instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal(overLimit(level.getServer(), owner, device.limitType())
                        + " This one is inactive. Break one you have, then right-click this one to switch it on."));
            }
        }
    }

    /** "You already have 10 of 10 active computers." */
    private static String overLimit(MinecraftServer server, UUID owner, String type) {
        return "You already have " + DeviceRegistry.get(server).count(owner, type) + " of " + limit(type) + " active " + plural(type) + ".";
    }

    /**
     * A device's block entity has loaded. One that is active but not in the registry (placed
     * before the registry existed, or the registry was lost) is counted now; this never
     * switches it off, even if it puts its owner over the limit.
     */
    public static void loaded(ServerLevel level, Limited device) {
        if (!device.bricked() && device.owner() != null && !DeviceRegistry.get(level.getServer()).has(device.deviceId())) {
            register(level, device);
        }
    }

    /** The device's block was broken or replaced: it no longer counts. */
    public static void removed(ServerLevel level, Limited device) {
        DeviceRegistry.get(level.getServer()).remove(device.deviceId());
    }

    /** The device has a new owner: it counts against them now. */
    public static void ownerChanged(ServerLevel level, Limited device) {
        if (!device.bricked() && device.owner() != null) {
            register(level, device);
        } else {
            removed(level, device);
        }
    }

    /**
     * A player right-clicked a bricked device: its owner (or anyone, for one a machine placed,
     * which has no owner) may switch it on if under the limit. Returns what to tell them.
     * Checked at most once a second per player, so clicking at many bricked blocks costs little.
     */
    public static Component tryActivate(ServerLevel level, Limited device, ServerPlayer player) {
        long now = level.getGameTime();
        long gap = 20L / Math.max(1, QboticsConfig.reactivationChecksPerSecond);
        Long last = LAST_CHECK.get(player.getUUID());
        if (last != null && now - last < gap) {
            return Component.literal("Inactive. Try again in a moment.");
        }
        LAST_CHECK.put(player.getUUID(), now);
        UUID owner = device.owner();
        if (owner != null && !owner.equals(player.getUUID())) {
            return Component.literal("Inactive: its owner has reached their limit. Only they can switch it on.");
        }
        if (!roomFor(level.getServer(), player.getUUID(), device.limitType())) {
            return Component.literal("Inactive. " + overLimit(level.getServer(), player.getUUID(), device.limitType())
                    + " Break one to switch this one on.");
        }
        if (owner == null && device instanceof Ownable ownable) {
            ownable.claim(player.getUUID()); // a machine placed it: it is the player's now
        }
        device.setBricked(false);
        register(level, device);
        return Component.literal("Switched on.");
    }

    /**
     * A Programmer switches a device on for its own owner (design doc §5.4): a device with no
     * owner becomes theirs. Counts against that owner's limit. Returns why not, or null if done.
     */
    public static @Nullable String activateFor(ServerLevel level, Limited device, UUID owner) {
        if (device.owner() != null && !device.owner().equals(owner)) {
            return "That device belongs to someone else.";
        }
        if (device.bricked() && !roomFor(level.getServer(), owner, device.limitType())) {
            return "Its owner already has " + DeviceRegistry.get(level.getServer()).count(owner, device.limitType()) + " of "
                    + limit(device.limitType()) + " active " + plural(device.limitType()) + ", so it stays inactive.";
        }
        if (device.owner() == null && device instanceof Ownable ownable) {
            ownable.claim(owner);
        }
        device.setBricked(false);
        register(level, device);
        return null;
    }

    /**
     * Lines for /qbotics limits: a player's active devices of each type against the limit,
     * and where each one is; with no player, everyone's, grouped by owner.
     */
    public static java.util.List<String> report(MinecraftServer server, @Nullable UUID player) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        DeviceRegistry registry = DeviceRegistry.get(server);
        java.util.Map<UUID, java.util.List<DeviceRegistry.Entry>> byOwner = new java.util.TreeMap<>();
        for (DeviceRegistry.Entry entry : registry.entries(player)) {
            byOwner.computeIfAbsent(entry.owner(), o -> new java.util.ArrayList<>()).add(entry);
        }
        if (player != null) {
            byOwner.putIfAbsent(player, java.util.List.of());
        }
        if (byOwner.isEmpty()) {
            lines.add("No player has any active computers or antennas.");
        }
        for (java.util.Map.Entry<UUID, java.util.List<DeviceRegistry.Entry>> owner : byOwner.entrySet()) {
            String name = qbotics.network.RadioNetwork.playerName(server, owner.getKey());
            String who = player != null ? "You have" : (name == null ? owner.getKey().toString() : name) + " has";
            for (String type : new String[] {COMPUTER, ANTENNA}) {
                int limit = limit(type);
                String of = limit < 0 || exempt(server, owner.getKey()) ? " (no limit)" : " of " + limit;
                lines.add(who + " " + registry.count(owner.getKey(), type) + of + " active " + plural(type) + ".");
            }
            for (DeviceRegistry.Entry entry : owner.getValue()) {
                lines.add("  " + entry.type() + " at " + entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ()
                        + " in " + entry.dimension());
            }
        }
        return lines;
    }

    /** A device that has no owner can be given one when a player switches it on. */
    public interface Ownable {
        void claim(UUID owner);
    }
}
