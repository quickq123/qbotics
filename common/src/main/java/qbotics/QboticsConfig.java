package qbotics;

/**
 * The server's settings (design doc §15.1), as the mod's code reads them. The loader fills
 * these in from {@code config/qbotics-server.toml} in the game or server folder when
 * it loads or changes; until then, and in the game tests, they hold the defaults below.
 */
public final class QboticsConfig {
    private QboticsConfig() {
    }

    // ---- [limits] (design doc §5) -----------------------------------------------------

    /** Active computers a player may own; -1 for no limit. More are placed bricked. */
    public static volatile int computersPerPlayer = 10;
    /** Active antennas a player may own; -1 for no limit. */
    public static volatile int antennasPerPlayer = 10;
    /** Server operators may have any number of active devices. */
    public static volatile boolean operatorsExempt = true;

    // ---- [boot] (design doc §6.3) -------------------------------------------------------

    /** Computers started per tick from the boot queue. */
    public static volatile int bootPerTick = 4;

    // ---- [scheduler] (design doc §4) --------------------------------------------------------

    /** How many computers may run Python at once. Read when the server starts. */
    public static volatile int workerCores = 2;
    /** How often one player may ask a bricked device to come back on, per second. */
    public static volatile int reactivationChecksPerSecond = 1;
}
