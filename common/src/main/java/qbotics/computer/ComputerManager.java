package qbotics.computer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Semaphore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import qbotics.Qbotics;
import qbotics.block.ComputerBlockEntity;
import qbotics.engine.Engine;
import qbotics.net.TerminalStatePayload;

/**
 * All running computers on one server, and which players are looking at which screen.
 * Everything here runs on the server thread except the computers' own threads.
 */
public final class ComputerManager {
    /** How far a player may be from a computer and still use its terminal. */
    private static final double MAX_USE_DISTANCE = 8.0;
    /** Design doc §4: scheduler.worker_cores. Will come from config. */
    private static final int WORKER_CORES = 2;

    private static final Map<MinecraftServer, ComputerManager> MANAGERS = new WeakHashMap<>();
    private static Engine engine;

    private final Semaphore cpu = new Semaphore(WORKER_CORES, true);
    private final Map<UUID, ServerComputer> computers = new HashMap<>();
    private final Path drivesFolder;
    /** Player UUID -> the terminal that player has open. */
    private final Map<UUID, Viewing> viewers = new HashMap<>();

    private record Viewing(UUID deviceId, ResourceKey<Level> dimension, BlockPos pos) {
    }

    private ComputerManager(Path drivesFolder) {
        this.drivesFolder = drivesFolder;
    }

    public static synchronized ComputerManager get(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, s -> new ComputerManager(
                s.getWorldPath(LevelResource.ROOT).resolve("data").resolve(Qbotics.MOD_ID).resolve("drives")));
    }

    /** Where a drive's files are: <world>/data/qbotics/drives/<drive-id>/ (design doc §17.2). */
    public Path driveFolder(UUID driveId) {
        return drivesFolder.resolve(driveId.toString());
    }

    /** The shared engine. Loaded (and compiled, about half a second) on first use. */
    static synchronized Engine engine() {
        if (engine == null) {
            long start = System.nanoTime();
            engine = Engine.bundled();
            Qbotics.LOGGER.info("Qbotics Python engine loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
        }
        return engine;
    }

    /** The running computer for this block, booting it if it isn't running yet. */
    public ServerComputer getOrStart(ComputerBlockEntity block) {
        ServerComputer running = computers.get(block.deviceId());
        if (running != null) {
            return running;
        }
        // Two placed computers must never share a drive (design doc §17.1 step 3). That can
        // happen with a creative-mode copy of a block; the later one gets a new, empty drive.
        UUID driveId = block.driveId();
        if (computers.values().stream().anyMatch(other -> other.driveId().equals(driveId))) {
            block.replaceDrive();
        }
        ServerComputer computer = new ServerComputer(block.deviceId(), block.driveId(), driveFolder(block.driveId()), cpu);
        computers.put(block.deviceId(), computer);
        return computer;
    }

    public boolean isRunning(UUID deviceId) {
        return computers.containsKey(deviceId);
    }

    /** A player right-clicked a computer: start it if needed and show them its screen. */
    public void openTerminal(ServerPlayer player, ComputerBlockEntity block) {
        UUID deviceId = block.deviceId();
        ServerComputer computer = getOrStart(block);
        viewers.put(player.getUUID(), new Viewing(deviceId, player.level().dimension(), block.getBlockPos()));
        computer.terminal().takeDirty();
        Qbotics.platform().sendToPlayer(player, TerminalStatePayload.of(deviceId, true, computer.terminal().snapshot()));
    }

    /** The player closed the screen. */
    public void closeTerminal(ServerPlayer player) {
        viewers.remove(player.getUUID());
    }

    /** A line typed into the terminal the player has open. */
    public void onInputLine(ServerPlayer player, UUID deviceId, String line) {
        ServerComputer computer = viewedComputer(player, deviceId);
        if (computer != null) {
            computer.submitLine(line);
        }
    }

    /** Ctrl+C in the terminal the player has open. */
    public void onInterrupt(ServerPlayer player, UUID deviceId) {
        ServerComputer computer = viewedComputer(player, deviceId);
        if (computer != null) {
            computer.interrupt();
        }
    }

    /** Only the terminal a player really has open, and is still close to, accepts their input. */
    private ServerComputer viewedComputer(ServerPlayer player, UUID deviceId) {
        Viewing viewing = viewers.get(player.getUUID());
        if (viewing == null || !viewing.deviceId().equals(deviceId) || !inRange(player, viewing)) {
            return null;
        }
        return computers.get(deviceId);
    }

    private static boolean inRange(ServerPlayer player, Viewing viewing) {
        return player.level().dimension().equals(viewing.dimension())
                && player.distanceToSqr(viewing.pos().getX() + 0.5, viewing.pos().getY() + 0.5, viewing.pos().getZ() + 0.5)
                        <= MAX_USE_DISTANCE * MAX_USE_DISTANCE;
    }

    /** Once per server tick: send changed screens to the players watching them. */
    public void tick(MinecraftServer server) {
        if (viewers.isEmpty()) {
            return;
        }
        Map<UUID, TerminalStatePayload> changed = new HashMap<>();
        for (Viewing viewing : viewers.values()) {
            ServerComputer computer = computers.get(viewing.deviceId());
            if (computer != null && !changed.containsKey(viewing.deviceId()) && computer.terminal().takeDirty()) {
                changed.put(viewing.deviceId(), TerminalStatePayload.of(viewing.deviceId(), false, computer.terminal().snapshot()));
            }
        }
        for (Iterator<Map.Entry<UUID, Viewing>> it = viewers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Viewing> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !inRange(player, entry.getValue()) || !computers.containsKey(entry.getValue().deviceId())) {
                it.remove();
                continue;
            }
            TerminalStatePayload update = changed.get(entry.getValue().deviceId());
            if (update != null) {
                Qbotics.platform().sendToPlayer(player, update);
            }
        }
    }

    /** The computer block was broken. */
    public void remove(UUID deviceId) {
        ServerComputer computer = computers.remove(deviceId);
        if (computer != null) {
            computer.shutDown();
        }
    }

    public void onPlayerLeft(ServerPlayer player) {
        viewers.remove(player.getUUID());
    }

    /** The server is stopping. Computers reboot from scratch next time (design doc §1.4). */
    public static synchronized void shutDown(MinecraftServer server) {
        ComputerManager manager = MANAGERS.remove(server);
        if (manager != null) {
            manager.computers.values().forEach(ServerComputer::shutDown);
            manager.computers.clear();
            manager.viewers.clear();
        }
    }
}
