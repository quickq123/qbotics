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

    private static final Map<MinecraftServer, ComputerManager> MANAGERS = new WeakHashMap<>();
    private static Engine engine;

    /** How many computers may run Python at once (design doc §4: scheduler.worker_cores, read at server start). */
    private final Semaphore cpu = new Semaphore(Math.max(1, qbotics.QboticsConfig.workerCores), true);
    /** Read by computers' own threads too (an ssh session looks the other computer up), so it must be safe for that. */
    private final Map<UUID, ServerComputer> computers = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** Where a hard drive's files are: <world>/data/qbotics/media/<media-id>/ (design doc §17.2). */
    public Path mediaFolder(UUID mediaId) {
        return drivesFolder.resolveSibling("media").resolve(mediaId.toString());
    }

    /** One file system per hard drive, shared by every computer that sees it, so its space is counted once. */
    private final Map<UUID, qbotics.engine.HostFileSystem> media = new java.util.concurrent.ConcurrentHashMap<>();

    /** The files on a hard drive, by its ID. */
    public qbotics.engine.HostFileSystem mediaFiles(UUID mediaId) {
        return media.computeIfAbsent(mediaId, id -> new qbotics.engine.DriveFileSystem(mediaFolder(id), qbotics.block.HardDriveItem.CAPACITY_BYTES));
    }

    /**
     * Every few ticks: tells each running computer which drive bays and drives its network
     * has, for its /net folder (design doc §17.6).
     */
    private void updateMounts(MinecraftServer server) {
        for (Map.Entry<UUID, Viewing> entry : places.entrySet()) {
            ServerComputer computer = computers.get(entry.getKey());
            net.minecraft.server.level.ServerLevel level = server.getLevel(entry.getValue().dimension());
            if (computer == null || level == null || !level.isLoaded(entry.getValue().pos())) {
                continue;
            }
            Map<String, Map<String, qbotics.engine.HostFileSystem>> mounts = new java.util.TreeMap<>();
            for (qbotics.network.NetworkDevice device : qbotics.network.CableNetwork.at(level, entry.getValue().pos()).devices()) {
                if (device instanceof qbotics.block.DriveBayBlockEntity bay) {
                    Map<String, qbotics.engine.HostFileSystem> drives = new java.util.LinkedHashMap<>();
                    bay.mounted().forEach((name, id) -> drives.put(name, mediaFiles(id)));
                    mounts.put(bay.folderName(), drives);
                }
            }
            computer.setMounts(mounts);
        }
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
        UUID computerId = block.deviceId();
        ServerComputer computer = new ServerComputer(block.deviceId(), block.driveId(), driveFolder(block.driveId()), cpu, listener,
                call -> {
                    // Drawing on a display it holds a handle for is done at once, on the computer's own thread.
                    if (call.startsWith(qbotics.display.Displays.DRAW_PREFIX)) {
                        return CompletableFuture.completedFuture(qbotics.display.Displays.drawFast(computerId, call));
                    }
                    // Asked on the computer's thread; answered on the server thread at the next tick.
                    CompletableFuture<String> answer = new CompletableFuture<>();
                    outbox.add(server -> answer.complete(NetworkCalls.handle(server, dimension, pos, call)));
                    return answer;
                });
        computers.put(block.deviceId(), computer);
        places.put(block.deviceId(), new Viewing(block.deviceId(), dimension, pos));
        booting.remove(block.deviceId());
        block.setOn(true); // it comes back on by itself when its chunk next loads
        return computer;
    }

    // ---- the boot queue (design doc §6.3) ------------------------------------------------

    /** Where each running computer is, so a program's shutdown can find its block. */
    private final Map<UUID, Viewing> places = new HashMap<>();

    /** A computer waiting to come back on: where it is, whose it is, and how far from the world's origin. */
    private record Boot(UUID deviceId, ResourceKey<Level> dimension, BlockPos pos, String owner) {
        long distance() {
            return (long) pos.getX() * pos.getX() + (long) pos.getZ() * pos.getZ();
        }
    }

    private final Map<UUID, Boot> booting = new java.util.LinkedHashMap<>();

    /**
     * A computer that was on has loaded: it comes back on through the queue, a few per tick,
     * so a server start or a large area loading doesn't start hundreds at once.
     */
    public void queueBoot(net.minecraft.server.level.ServerLevel level, ComputerBlockEntity block) {
        UUID id = block.deviceId();
        if (!computers.containsKey(id)) {
            booting.put(id, new Boot(id, level.dimension(), block.getBlockPos().immutable(), String.valueOf(block.owner())));
        }
    }

    /** How many computers are waiting to come back on. */
    public int bootQueueSize() {
        return booting.size();
    }

    /**
     * The order computers come back on: the Overworld first, then the other dimensions;
     * within one, owners take turns; each owner's computers nearest the world's origin first,
     * then by position. The same every time, and fair between players.
     */
    private java.util.List<Boot> bootOrder() {
        Map<String, Map<String, java.util.List<Boot>>> byPlace = new java.util.TreeMap<>((a, b) -> {
            boolean aHome = a.equals(Level.OVERWORLD.identifier().toString());
            boolean bHome = b.equals(Level.OVERWORLD.identifier().toString());
            return aHome != bHome ? (aHome ? -1 : 1) : a.compareTo(b);
        });
        for (Boot boot : booting.values()) {
            byPlace.computeIfAbsent(boot.dimension().identifier().toString(), d -> new java.util.TreeMap<>())
                    .computeIfAbsent(boot.owner(), o -> new java.util.ArrayList<>()).add(boot);
        }
        java.util.List<Boot> order = new java.util.ArrayList<>();
        for (Map<String, java.util.List<Boot>> owners : byPlace.values()) {
            java.util.List<java.util.List<Boot>> lines = new java.util.ArrayList<>();
            for (java.util.List<Boot> mine : owners.values()) {
                mine.sort(java.util.Comparator.comparingLong(Boot::distance).thenComparing(Boot::pos));
                lines.add(mine);
            }
            for (int turn = 0; ; turn++) {
                boolean any = false;
                for (java.util.List<Boot> mine : lines) {
                    if (turn < mine.size()) {
                        order.add(mine.get(turn));
                        any = true;
                    }
                }
                if (!any) {
                    break;
                }
            }
        }
        return order;
    }

    /** Once a tick: starts the next few computers in the boot queue. */
    private void bootSome(MinecraftServer server) {
        if (booting.isEmpty()) {
            return;
        }
        int started = 0;
        for (Boot boot : bootOrder()) {
            if (started >= Math.max(1, qbotics.QboticsConfig.bootPerTick)) {
                break;
            }
            booting.remove(boot.deviceId());
            net.minecraft.server.level.ServerLevel level = server.getLevel(boot.dimension());
            if (level == null || !level.isLoaded(boot.pos())
                    || !(level.getBlockEntity(boot.pos()) instanceof ComputerBlockEntity block)
                    || !block.deviceId().equals(boot.deviceId()) || block.bricked() || !block.wasOn()) {
                continue; // gone, unloaded again, or switched off since
            }
            getOrStart(block);
            started++;
        }
    }

    /** A program asked its computer to shut down: it stops, and stays off until someone opens it. */
    private void shutDownComputer(MinecraftServer server, UUID deviceId) {
        Viewing place = places.remove(deviceId);
        remove(deviceId);
        if (place != null) {
            net.minecraft.server.level.ServerLevel level = server.getLevel(place.dimension());
            if (level != null && level.isLoaded(place.pos()) && level.getBlockEntity(place.pos()) instanceof ComputerBlockEntity block) {
                block.setOn(false);
            }
        }
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
        public ServerComputer running(UUID deviceId) {
            return computers.get(deviceId);
        }

        @Override
        public void shutDownRequested(UUID deviceId) {
            outbox.add(server -> shutDownComputer(server, deviceId));
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
            // Perhaps a Programmer's drive, open in the editor without a terminal.
            HostFileSystem drive = viewedDrive(player, request.deviceId());
            if (drive == null || ComputerSettings.covers(request.path())) {
                reply(playerId, request, HostFileSystem.EACCES, new byte[0]);
                return;
            }
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
        Qbotics.platform().sendToPlayer(player, new TerminalStatePayload(deviceId, true, computer.asShown(computer.shownTerminal().full())));
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
    /**
     * Opens a Programmer's hard drive in the editor, for one player: files only, with no
     * terminal and nothing to run (design doc §5.4).
     */
    public void openDriveEditor(ServerPlayer player, qbotics.block.ProgrammerBlockEntity programmer) {
        viewers.put(player.getUUID(), new Viewing(programmer.deviceId(), player.level().dimension(), programmer.getBlockPos().immutable()));
        player.closeContainer();
        Qbotics.platform().sendToPlayer(player, new IdePayloads.OpenEditor(programmer.deviceId(), "Programmer"));
    }

    /** The drive of the Programmer a player has open in the editor, if this is it and they are still near it. */
    private HostFileSystem viewedDrive(ServerPlayer player, UUID deviceId) {
        Viewing viewing = viewers.get(player.getUUID());
        if (viewing == null || !viewing.deviceId().equals(deviceId) || !inRange(player, viewing)
                || !(player.level().getBlockEntity(viewing.pos()) instanceof qbotics.block.ProgrammerBlockEntity programmer)
                || !programmer.deviceId().equals(deviceId) || programmer.mediaId() == null) {
            return null;
        }
        return mediaFiles(programmer.mediaId());
    }

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
        bootSome(server);
        qbotics.display.Displays.tick(server);
        if (server.getTickCount() % 10 == 0) {
            updateMounts(server);
        }
        qbotics.network.ScreenSessions.tick(server, session -> NetworkCalls.interfaces(
                qbotics.network.CableNetwork.at(qbotics.network.ScreenSessions.levelOf(session), qbotics.network.ScreenSessions.busPosOf(session))));
        if (viewers.isEmpty()) {
            return;
        }
        // What each watched computer shows is its own terminal, or, during an ssh session, the
        // terminal of the computer it is logged in to. A terminal hands out its new lines only
        // once, so each is asked once a tick however many computers are showing it.
        Map<UUID, TerminalStatePayload> changed = new HashMap<>();
        Map<Terminal, Terminal.State> taken = new HashMap<>();
        for (Viewing viewing : viewers.values()) {
            UUID id = viewing.deviceId();
            ServerComputer computer = computers.get(id);
            if (computer == null || changed.containsKey(id)) {
                continue;
            }
            Terminal shown = computer.shownTerminal();
            if (computer.takeViewChanged()) {
                // A session began or ended: send the whole of what is now shown.
                changed.put(id, new TerminalStatePayload(id, false, computer.asShown(shown.full())));
            } else if (taken.containsKey(shown) || shown.isDirty()) {
                changed.put(id, new TerminalStatePayload(id, false, computer.asShown(taken.computeIfAbsent(shown, Terminal::takeUpdate))));
            }
        }
        for (Iterator<Map.Entry<UUID, Viewing>> it = viewers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Viewing> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            boolean driveOnly = !computers.containsKey(entry.getValue().deviceId()) && player != null
                    && player.level().getBlockEntity(entry.getValue().pos()) instanceof qbotics.block.ProgrammerBlockEntity;
            if (player == null || !inRange(player, entry.getValue()) || (!computers.containsKey(entry.getValue().deviceId()) && !driveOnly)) {
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
    /** The running computer with this device ID, or null if it isn't switched on. */
    public ServerComputer computer(UUID deviceId) {
        return computers.get(deviceId);
    }

    /**
     * Hands a computer an event, under the session its program signed up with. Returns false
     * if the computer is off, its program has stopped listening, or its queue is full: the
     * sender can then forget it.
     */
    public boolean pushEvent(UUID deviceId, int session, com.google.gson.JsonObject event) {
        ServerComputer computer = computers.get(deviceId);
        return computer != null && computer.pushEvent(session, event);
    }

    public boolean deliver(UUID deviceId, String sender, String data) {
        ServerComputer computer = computers.get(deviceId);
        return computer != null && computer.deliver(sender, data);
    }

    /** The computer block was broken. */
    public void remove(UUID deviceId) {
        booting.remove(deviceId);
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
