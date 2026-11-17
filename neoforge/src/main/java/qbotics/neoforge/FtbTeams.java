package qbotics.neoforge;

import java.lang.reflect.Method;
import java.util.UUID;
import net.neoforged.fml.ModList;

/**
 * FTB Teams, if it is installed: are two players in one team? Asked through reflection, so the
 * mod is neither needed to build Qbotics nor to run it. (Checked against FTB Teams 26.1.2.5:
 * FTBTeamsAPI.api().getManager().arePlayersInSameTeam(UUID, UUID).)
 */
final class FtbTeams {
    private static boolean looked;
    private static Method api;
    private static Method managerLoaded;
    private static Method manager;
    private static Method sameTeam;

    private FtbTeams() {
    }

    static synchronized boolean sameTeam(UUID one, UUID other) {
        if (!looked) {
            looked = true;
            if (ModList.get().isLoaded("ftbteams")) {
                try {
                    Class<?> entry = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
                    Class<?> apiType = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
                    Class<?> managerType = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
                    api = entry.getMethod("api");
                    managerLoaded = apiType.getMethod("isManagerLoaded");
                    manager = apiType.getMethod("getManager");
                    sameTeam = managerType.getMethod("arePlayersInSameTeam", UUID.class, UUID.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    qbotics.Qbotics.LOGGER.warn("FTB Teams is installed, but its teams can't be read (a version this wasn't written for): "
                            + "\"team\" access uses the game's /team only", e);
                    sameTeam = null;
                }
            }
        }
        if (sameTeam == null) {
            return false;
        }
        try {
            Object teams = api.invoke(null);
            if (!(Boolean) managerLoaded.invoke(teams)) {
                return false;
            }
            return (Boolean) sameTeam.invoke(manager.invoke(teams), one, other);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
