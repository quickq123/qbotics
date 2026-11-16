package qbotics.block;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Experience arithmetic. The game shows levels, but a level costs more the higher it is, so
 * the only thing that can be stored and given back without gain or loss is points. Everything
 * here counts in points and converts at the edges.
 */
public final class Experience {
    /** Far beyond any real play, and safely inside what a long can hold as points. */
    public static final long MAX_LEVEL = 1_000_000_000L;

    private Experience() {
    }

    /** All the points an experience orb is worth: orbs that merge keep a count of themselves. */
    public static int orbPoints(net.minecraft.world.entity.ExperienceOrb orb) {
        int count = 1;
        try {
            java.lang.reflect.Field field = net.minecraft.world.entity.ExperienceOrb.class.getDeclaredField("count");
            field.setAccessible(true);
            count = Math.max(1, field.getInt(orb));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // an orb counts once
        }
        return orb.getValue() * count;
    }

    /** The points it takes to go from nothing to exactly this level (the game's own rule). */
    public static long pointsAtLevel(long level) {
        long l = Math.clamp(level, 0, MAX_LEVEL);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            return (5 * l * l - 81 * l + 720) / 2;
        }
        return (9 * l * l - 325 * l + 4440) / 2;
    }

    /** The highest level that this many points reaches from nothing. */
    public static long levelAt(long points) {
        long low = 0;
        long high = MAX_LEVEL;
        while (low < high) {
            long middle = (low + high + 1) / 2;
            if (pointsAtLevel(middle) <= points) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Everything a player has, in points: their whole levels plus their progress to the next. */
    public static long pointsOf(Player player) {
        return pointsAtLevel(player.experienceLevel) + Math.round(player.experienceProgress * (double) player.getXpNeededForNextLevel());
    }

    /** Sets a player to exactly this many points, as levels and progress. */
    public static void setPoints(ServerPlayer player, long points) {
        long level = Math.min(levelAt(Math.max(0, points)), Integer.MAX_VALUE - 1);
        player.setExperienceLevels((int) level);
        player.setExperiencePoints((int) Math.clamp(points - pointsAtLevel(level), 0, Integer.MAX_VALUE));
    }
}
