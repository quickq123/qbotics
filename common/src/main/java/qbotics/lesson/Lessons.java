package qbotics.lesson;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;

/**
 * The lessons: advancements (data/qbotics/advancement/lessons, written by make_assets.py) met
 * when a player's programs do what each lesson asks. The game's hooks tell {@link #award} a step
 * by name ("move", "sorting", "pin_out", ...) and whose program it was: the owner of the
 * computer, Qbot or Server, or a laptop's player. The criteria do the rest.
 *
 * Steps are told only while the owner is online; a step done while they are away counts the
 * next time it is done. Each step is told once per owner per game, so hooks may call this as
 * often as the thing happens, from any thread.
 */
public final class Lessons {
    private Lessons() {
    }

    /** How long a program must run unattended (from startup, or as a service) for its lesson: a game day. */
    public static volatile int dayTicks = 24_000;

    /** "owner step" pairs already told this game. */
    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();

    /** A new game (or none): nothing has been told in it. */
    public static void reset() {
        TOLD.clear();
    }

    /** The server's tick count now, or 0 between games. Any thread. */
    public static int tick() {
        MinecraftServer server = Qbotics.server();
        return server == null ? 0 : server.getTickCount();
    }

    /** Tells the lessons that a program of this owner's did a step. Any thread; does nothing for no owner. */
    public static void award(@Nullable UUID owner, String step) {
        MinecraftServer server = Qbotics.server();
        if (owner == null || server == null || QboticsContent.LESSON_TRIGGER == null || TOLD.contains(owner + " " + step)) {
            return;
        }
        if (server.isSameThread()) {
            tell(server, owner, step);
        } else {
            server.execute(() -> tell(server, owner, step));
        }
    }

    private static void tell(MinecraftServer server, UUID owner, String step) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) {
            // Players the game knows but that aren't on its list (the game tests' stand-ins).
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getPlayerByUUID(owner) instanceof ServerPlayer there) {
                    player = there;
                    break;
                }
            }
        }
        if (player == null) {
            return; // offline: told the next time it happens
        }
        QboticsContent.LESSON_TRIGGER.get().trigger(player, step);
        TOLD.add(owner + " " + step);
    }

}
