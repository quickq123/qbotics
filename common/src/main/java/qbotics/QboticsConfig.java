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
    /** Active Qbots a player may own; -1 for no limit. */
    public static volatile int qbotsPerPlayer = 5;
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

    // ---- [chunk_loading] (antenna upgrades) -------------------------------------------------

    /** Whether Chunk Loader Upgrades work at all. */
    public static volatile boolean chunkLoadingEnabled = true;
    /** Whether laptops can be opened at all. */
    public static volatile boolean laptopsEnabled = true;
    /** Antennas with a working Chunk Loader Upgrade a player may have; -1 for no limit. */
    public static volatile int chunkLoadersPerPlayer = 4;
    /** The most chunks one antenna keeps loaded (its network's, nearest first). */
    public static volatile int chunksPerLoader = 25;
    /** Whether loaders keep working while their owner is offline. */
    public static volatile boolean chunkLoadingWhileOffline = true;

    // ---- [transport] (antenna upgrades) ------------------------------------------------------

    /** Items one antenna carries to or from other networks each second. */
    public static volatile int transportItemsPerSecond = 1024;
    /** Fluid one antenna carries each second, in mB. */
    public static volatile int transportFluidPerSecond = 64_000;
    /** Energy one antenna carries each second, in FE. */
    public static volatile int transportEnergyPerSecond = 2_000_000;
}
