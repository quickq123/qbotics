package qbotics.client.shots;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import qbotics.Qbotics;

/**
 * A development aid, not part of play: when the game is started with
 * {@code -Dqbotics.playtest=true} (the {@code runPlaytest} Gradle task), it goes straight from
 * the title screen into the play-test world. The first time, it makes that world (creative,
 * flat, peaceful, cheats on) and builds the test stage beside the player; after that it opens
 * the same world, so whatever was built in it is still there.
 */
public final class PlaytestStart {
    public static final boolean ENABLED = Boolean.getBoolean("qbotics.playtest");
    private static final String WORLD = "Qbotics Playtest";

    private static int ticks;
    private static boolean asked;
    private static boolean fresh;
    private static boolean done;

    private PlaytestStart() {
    }

    /** Called once every client tick while switched on. */
    public static void tick(Minecraft minecraft) {
        if (done) {
            return;
        }
        ticks++;
        if (!asked) {
            // Once the game has finished loading and shows its first screen.
            if (minecraft.level == null && minecraft.getOverlay() == null && minecraft.screen != null && ticks > 40) {
                asked = true;
                if (minecraft.getLevelSource().levelExists(WORLD)) {
                    minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.setScreen(new TitleScreen()));
                } else {
                    fresh = true;
                    LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                    minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                            WorldPresets::createFlatWorldDimensions, new TitleScreen());
                }
            }
            return;
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (minecraft.player == null || server == null) {
            return;
        }
        done = true;
        if (fresh) {
            // A new world: put the test stage beside the player, as /qbotics stage would.
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "qbotics stage");
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "time set noon");
                Qbotics.LOGGER.info("Play test: made the world '{}' and built the test stage", WORLD);
            });
        }
    }
}
