package qbotics.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import qbotics.Qbotics;
import qbotics.QboticsConfig;
import qbotics.block.DeviceBlockEntity;

/**
 * Where a sensor or Collector may reach for things that never go through its owner's hands:
 * entity inventories, dropped items and experience, the raw data of other blocks. Those keep
 * to the land its owner may change, asked as a break by the owner would be (so a claims mod
 * such as FTB Chunks answers), unless the server's sensors.read_other_players_computers
 * setting lifts it. A device nobody placed has no owner to ask for: it reaches as before.
 *
 * <p>The answer for a chunk is kept for ten seconds: a Collector asks twice a second.
 */
public final class Boundaries {
    private record Key(UUID owner, ResourceKey<Level> dimension, long chunk) {
    }

    private static final Map<Key, long[]> ASKED = new ConcurrentHashMap<>();
    private static final int KEPT_TICKS = 200;

    private Boundaries() {
    }

    /** True if the device's owner may change things at this spot. */
    public static boolean mayReach(ServerLevel level, DeviceBlockEntity device, BlockPos spot) {
        UUID owner = device.owner();
        if (QboticsConfig.sensorsReadOtherPlayers || owner == null) {
            return true;
        }
        Key key = new Key(owner, level.dimension(), ChunkPos.containing(spot).pack());
        long now = level.getGameTime();
        long[] had = ASKED.get(key);
        if (had != null && had[1] > now && had[1] - now <= KEPT_TICKS) {
            return had[0] == 1;
        }
        boolean may;
        try {
            ServerPlayer standIn = Qbotics.platform().standIn(level, owner, device.deviceId(), device.getBlockPos(), Direction.UP);
            may = Qbotics.platform().mayBreak(level, standIn, spot);
        } catch (RuntimeException e) {
            may = false; // a claims mod that can't answer is taken as a no
        }
        if (ASKED.size() > 4096) {
            ASKED.clear();
        }
        ASKED.put(key, new long[] {may ? 1 : 0, now + KEPT_TICKS});
        return may;
    }

    /** Forgets every answer (a server stopping; a test changing the setting). */
    public static void clear() {
        ASKED.clear();
    }
}
