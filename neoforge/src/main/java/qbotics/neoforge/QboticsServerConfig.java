package qbotics.neoforge;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import qbotics.QboticsConfig;

/**
 * The server settings file (design doc §15.1): {@code config/qbotics-server.toml} in the
 * game or server folder. NeoForge writes it with these comments the first time; when it is loaded or
 * edited, the values are copied into {@link QboticsConfig}, which the rest of the mod reads.
 */
final class QboticsServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue COMPUTERS;
    private static final ModConfigSpec.IntValue ANTENNAS;
    private static final ModConfigSpec.BooleanValue OPERATORS_EXEMPT;
    private static final ModConfigSpec.IntValue BOOT_PER_TICK;
    private static final ModConfigSpec.IntValue WORKER_CORES;
    private static final ModConfigSpec.IntValue REACTIVATION_CHECKS;

    static {
        BUILDER.push("limits");
        BUILDER.comment("Per-player device limits by type. A device placed over the limit is 'bricked': inert, costing nothing,",
                "its files kept. Its owner switches it on by right-clicking it once under the limit.",
                "Lowering a limit does NOT switch off devices that are already active (they are grandfathered);",
                "players over the new limit can't switch on more until they drop below it. Set reasonable values from the start.",
                "Computers and antennas placed by machines (a dispenser, a Placer) are always bricked.",
                "-1 means no limit.");
        COMPUTERS = BUILDER.defineInRange("computers_per_player", 10, -1, 100_000);
        ANTENNAS = BUILDER.defineInRange("antennas_per_player", 10, -1, 100_000);
        OPERATORS_EXEMPT = BUILDER.comment("Server operators may have any number of active devices.")
                .define("operators_exempt", true);
        BUILDER.pop();

        BUILDER.push("boot");
        BOOT_PER_TICK = BUILDER.comment("Computers that were on come back on when their chunk loads, through one queue:",
                        "this many per tick. Order: the Overworld first, owners taking turns, nearest the world's origin first.")
                .defineInRange("per_tick", 4, 1, 1000);
        BUILDER.pop();

        BUILDER.push("scheduler");
        WORKER_CORES = BUILDER.comment("How many computers may run Python at the same moment. Read when the server starts.")
                .defineInRange("worker_cores", 2, 1, 64);
        REACTIVATION_CHECKS = BUILDER.comment("How often one player may ask a bricked device to switch on, per second.")
                .defineInRange("reactivation_checks_per_s", 1, 1, 20);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private QboticsServerConfig() {
    }

    /** The file was loaded or edited: the mod uses its values from now on. */
    static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        QboticsConfig.computersPerPlayer = COMPUTERS.get();
        QboticsConfig.antennasPerPlayer = ANTENNAS.get();
        QboticsConfig.operatorsExempt = OPERATORS_EXEMPT.get();
        QboticsConfig.bootPerTick = BOOT_PER_TICK.get();
        QboticsConfig.workerCores = WORKER_CORES.get();
        QboticsConfig.reactivationChecksPerSecond = REACTIVATION_CHECKS.get();
    }
}
