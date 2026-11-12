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
    /** The SSH gateway (design doc §9.5): off unless the admin turns it on. */
    public static volatile boolean sshEnabled = false;
    public static volatile int sshPort = 2222;
    /** Where it listens: 127.0.0.1 (this PC only) unless the admin opens it wider (0.0.0.0). */
    public static volatile String sshBindAddress = "127.0.0.1";
    /** true: a logged-in player reaches every computer, not only their own (single-player, trusted servers). */
    public static volatile boolean sshAllComputers = false;
    /** The uplink (design doc §9.4): whether computers may reach the real internet at all. */
    public static volatile boolean uplinkEnabled = true;
    /** Sites it may reach (a domain covers the names under it); empty: any public site. */
    public static volatile java.util.List<String> uplinkAllowDomains = java.util.List.of();
    /** Sites it never reaches. */
    public static volatile java.util.List<String> uplinkDenyDomains = java.util.List.of();
    /** The largest answer a computer is given, in KB (a computer's memory is small). */
    public static volatile int uplinkMaxResponseKb = 24;
    public static volatile int uplinkTimeoutSeconds = 10;
    public static volatile int uplinkRequestsPerMinute = 30;

    /** git through the uplink (design doc §17.5): clone, pull, status; push only if allowed. */
    public static volatile boolean gitEnabled = true;
    public static volatile boolean gitAllowPush = false;
    /** The largest repository (its archive) a computer may clone. */
    public static volatile int gitMaxRepoKb = 512;
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
