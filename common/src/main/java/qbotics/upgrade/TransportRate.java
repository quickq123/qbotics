package qbotics.upgrade;

import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import qbotics.QboticsConfig;
import qbotics.block.AntennaBlockEntity;
import qbotics.network.Medium;

/**
 * How much each antenna with a Transport Upgrade has carried to and from other networks this
 * second, against the server's limits (items, fluid and energy per second). Experience isn't
 * limited. Server thread only.
 */
public final class TransportRate {
    /** Per antenna: the second it last carried something in, and how much of each medium. */
    private static final Map<AntennaBlockEntity, long[]> USED = new WeakHashMap<>();

    private TransportRate() {
    }

    private static long limit(Medium medium) {
        return switch (medium) {
            case ITEM -> QboticsConfig.transportItemsPerSecond;
            case FLUID -> QboticsConfig.transportFluidPerSecond;
            case ENERGY -> QboticsConfig.transportEnergyPerSecond;
            default -> Long.MAX_VALUE;
        };
    }

    private static long[] used(AntennaBlockEntity antenna, long second) {
        long[] used = USED.computeIfAbsent(antenna, a -> new long[] {second, 0, 0, 0});
        if (used[0] != second) {
            used[0] = second;
            used[1] = used[2] = used[3] = 0;
        }
        return used;
    }

    /** How much more of a medium may cross through all these antennas this second. */
    public static long room(Collection<AntennaBlockEntity> antennas, Medium medium, long tick) {
        long limit = limit(medium);
        if (limit == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long room = limit;
        for (AntennaBlockEntity antenna : antennas) {
            room = Math.min(room, limit - used(antenna, tick / 20)[1 + medium.ordinal()]);
        }
        return Math.max(0, room);
    }

    /** Counts what crossed against each antenna. */
    public static void spend(Collection<AntennaBlockEntity> antennas, Medium medium, long amount, long tick) {
        if (amount <= 0 || medium.ordinal() > 2) {
            return;
        }
        for (AntennaBlockEntity antenna : antennas) {
            used(antenna, tick / 20)[1 + medium.ordinal()] += amount;
        }
    }

    /** The limit for a medium, for messages. */
    public static long perSecond(Medium medium) {
        return limit(medium);
    }
}
