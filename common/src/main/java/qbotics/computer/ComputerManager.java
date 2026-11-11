package qbotics.computer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import qbotics.Qbotics;
import qbotics.block.ComputerBlockEntity;
import qbotics.engine.Engine;
import qbotics.engine.HostFileSystem;
import qbotics.net.IdePayloads;
import qbotics.net.TerminalStatePayload;
import qbotics.network.NetworkCalls;

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

    /** Messages for players, queued from other threads and sent on the server thread. */
    private final Queue<Consumer<MinecraftServer>> outbox = new ConcurrentLinkedQueue<>();
    /** Does the editor's disk work, so a slow disk never stalls the game. */
    private static final ExecutorService FILE_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "qbotics-files");
        thread.setDaemon(true);
        return thread;
    });

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
        ResourceKey<Level> dimension = block.getLevel().dimension();
        BlockPos pos = block.getBlockPos().immutable();
        ServerComputer computer = new ServerComputer(block.deviceId(), block.driveId(), driveFolder(block.driveId()), cpu, listener,
                call -> {
                    // Asked on the computer's thread; answered on the server thread at the next tick.
                    CompletableFuture<String> answer = new CompletableFuture<>();
                    outbox.add(server -> answer.complete(NetworkCalls.handle(server, dimension, pos, call)));
                    return answer;
                });
        computers.put(block.deviceId(), computer);
        return computer;
    }

    /**
     * What computers report from their own threads. Nothing may touch players or the world
     * from there, so each report is queued and delivered on the next server tick.
     */
    private final ServerComputer.Listener listener = new ServerComputer.Listener() {
        @Override
        public void scratchResult(UUID deviceId, UUID player, int runId, boolean ok, String output) {
            outbox.add(server -> {
                ServerPlayer target = server.getPlayerList().getPlayer(player);
                if (target != null) {
                    Qbotics.platform().sendToPlayer(target, new IdePayloads.ScratchResult(deviceId, runId, ok, output));
                }
            });
        }

        @Override
        public void openInEditor(UUID deviceId, String path) {
            outbox.add(server -> viewers.forEach((playerId, viewing) -> {
                ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                if (target != null && viewing.deviceId().equals(deviceId)) {
                    Qbotics.platform().sendToPlayer(target, new IdePayloads.OpenFile(deviceId, path));
                }
            }));
        }
    };

    /** The editor's Run button. */
    public void onRunFile(ServerPlayer player, UUID deviceId, String path) {
        ServerComputer computer = viewedComputer(player, deviceId);
        if (computer != null) {
            computer.submitRunFile(path);
        }
    }

    /** The scratchpad's Run button. */
    public void onScratchRun(ServerPlayer player, IdePayloads.ScratchRun run) {
        ServerComputer computer = viewedComputer(player, run.deviceId());
        if (computer != null) {
            computer.submitScratch(player.getUUID(), run.runId(), run.code());
        }
    }

    /**
     * The editor asks to list, read or change files. Disk work is done off the server
     * thread; the answer is sent from the server thread on a later tick.
     */
    public void onFileRequest(ServerPlayer player, IdePayloads.FileRequest request) {
        ServerComputer computer = viewedComputer(player, request.deviceId());
        UUID playerId = player.getUUID();
        if (computer == null) {
            reply(playerId, request, HostFileSystem.EACCES, new byte[0]);
            return;
        }
        if (ComputerSettings.covers(request.path())) {
            // The settings page isn't on the drive: it is written from, and applied to, the computer itself.
            Viewing viewing = viewers.get(playerId);
            boolean page = request.path().equals(ComputerSettings.PATH);
            if (!page || !(player.level().getBlockEntity(viewing.pos()) instanceof qbotics.block.ComputerBlockEntity block)) {
                reply(playerId, request, HostFileSystem.EACCES, new byte[0]);
            } else if (request.op() == IdePayloads.FileRequest.READ) {
                reply(playerId, request, 0, ComputerSettings.read(player.level(), block).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if (request.op() == IdePayloads.FileRequest.WRITE) {
                String problem = ComputerSettings.write(player.level(), block, new String(request.data(), java.nio.charset.StandardCharsets.UTF_8));
                // A refusal carries its reason, which the editor shows.
                reply(playerId, request, problem == null ? 0 : HostFileSystem.EINVAL,
                        problem == null ? new byte[0] : problem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                reply(playerId, request, HostFileSystem.EACCES, new byte[0]);
            }
            return;
        }
        HostFileSystem drive = computer.drive();
        FILE_WORKER.execute(() -> {
            try {
                reply(playerId, request, 0, runFileRequest(drive, request));
            } catch (HostFileSystem.FsException e) {
                reply(playerId, request, e.errno, new byte[0]);
            } catch (RuntimeException e) {
                Qbotics.LOGGER.error("File request failed", e);
                reply(playerId, request, HostFileSystem.EIO, new byte[0]);
            }
        });
    }

    private static byte[] runFileRequest(HostFileSystem drive, IdePayloads.FileRequest request) throws HostFileSystem.FsException {
        byte[] none = new byte[0];
        String path = request.path();
        switch (request.op()) {
            case IdePayloads.FileRequest.LIST:
                return drive.call(HostFileSystem.LIST, path, none);
            case IdePayloads.FileRequest.READ: {
                byte[] data = drive.call(HostFileSystem.READ, path, none);
                if (data.length > IdePayloads.MAX_FILE_BYTES) {
                    throw new HostFileSystem.FsException(HostFileSystem.EFBIG);
                }
                return data;
            }
            case IdePayloads.FileRequest.WRITE:
                return drive.call(HostFileSystem.WRITE, path, request.data());
            case IdePayloads.FileRequest.MKDIR:
                return drive.call(HostFileSystem.MKDIR, path, none);
            case IdePayloads.FileRequest.DELETE:
                ServerComputer.deleteTree(drive, path);
                return none;
            case IdePayloads.FileRequest.RENAME:
                return drive.call(HostFileSystem.RENAME, path, request.data());
            case IdePayloads.FileRequest.CREATE:
                try {
                    drive.call(HostFileSystem.STAT, path, none);
                } catch (HostFileSystem.FsException missing) {
                    return drive.call(HostFileSystem.WRITE, path, none);
                }
                throw new HostFileSystem.FsException(HostFileSystem.EEXIST);
            default:
                throw new HostFileSystem.FsException(HostFileSystem.EINVAL);
        }
    }

    private void reply(UUID playerId, IdePayloads.FileRequest request, int errno, byte[] data) {
        outbox.add(server -> {
            ServerPlayer target = server.getPlayerList().getPlayer(playerId);
            if (target != null) {
                Qbotics.platform().sendToPlayer(target,
                        new IdePayloads.FileResponse(request.deviceId(), request.requestId(), errno, data));
            }
        });
    }

    public boolean isRunning(UUID deviceId) {
        return computers.containsKey(deviceId);
    }

    /** A player right-clicked a computer: start it if needed and show them its screen. */
    public void openTerminal(ServerPlayer player, ComputerBlockEntity block) {
        UUID deviceId = block.deviceId();
        ServerComputer computer = getOrStart(block);
        viewers.put(player.getUUID(), new Viewing(deviceId, player.level().dimension(), block.getBlockPos()));
        Qbotics.platform().sendToPlayer(player, new TerminalStatePayload(deviceId, true, computer.terminal().full()));
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
        for (Consumer<MinecraftServer> message; (message = outbox.poll()) != null; ) {
            message.accept(server);
        }
        if (viewers.isEmpty()) {
            return;
        }
        Map<UUID, TerminalStatePayload> changed = new HashMap<>();
        for (Viewing viewing : viewers.values()) {
            ServerComputer computer = computers.get(viewing.deviceId());
            if (computer != null && !changed.containsKey(viewing.deviceId()) && computer.terminal().isDirty()) {
                changed.put(viewing.deviceId(), new TerminalStatePayload(viewing.deviceId(), false, computer.terminal().takeUpdate()));
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

    /**
     * Hands a message to a computer. Returns false if that computer isn't switched on (it
     * starts the first time someone opens it) or its inbox is full.
     */
    public boolean deliver(UUID deviceId, String sender, String data) {
        ServerComputer computer = computers.get(deviceId);
        return computer != null && computer.deliver(sender, data);
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
