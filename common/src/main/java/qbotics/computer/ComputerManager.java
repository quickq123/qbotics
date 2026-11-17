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
    /** The computer each player opened last (for the AI agent: "this computer"). */
    private final Map<UUID, UUID> lastOpened = new java.util.concurrent.ConcurrentHashMap<>();

    /** The computer a player opened last, or null. */
    public UUID lastOpened(UUID player) {
        return lastOpened.get(player);
    }
    /** For each player with an editor open: the computer's file change count they last saw. */
    private final Map<UUID, Long> filesSeen = new HashMap<>();

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
            if (level.getBlockEntity(entry.getValue().pos()) instanceof qbotics.block.ServerBlockEntity rack) {
                // A Server's own hard drives, at /net/disks.
                Map<String, qbotics.engine.HostFileSystem> own = new java.util.LinkedHashMap<>();
                rack.mounted().forEach((name, id) -> own.put(name, mediaFiles(id)));
                if (!own.isEmpty()) {
                    mounts.put(qbotics.laptop.Laptops.DISKS_FOLDER, own);
                }
            }
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
            running.lessonOwner = block.owner();
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
        boolean newDrive = !java.nio.file.Files.exists(driveFolder(block.driveId())) && !(block instanceof qbotics.qbot.QbotBlockEntity);
        ServerComputer computer = new ServerComputer(block.deviceId(), block.driveId(), driveFolder(block.driveId()), cpu, listener,
                worldCalls(computerId, computerId, dimension, pos), block instanceof qbotics.qbot.QbotBlockEntity,
                block instanceof qbotics.qbot.QbotBlockEntity qbot && qbot.programRunning(),
                block instanceof qbotics.block.ServerBlockEntity ? qbotics.block.ServerBlockEntity.HEAP_BYTES : ServerComputer.BASIC_HEAP_BYTES,
                block instanceof qbotics.block.ServerBlockEntity ? qbotics.block.ServerBlockEntity.DRIVE_BYTES : ServerComputer.DRIVE_QUOTA_BYTES);
        computer.lessonOwner = block.owner();
        if (newDrive) {
            try {
                computer.drive().call(HostFileSystem.WRITE, "/startup.sh", ServerComputer.STARTUP_TEMPLATE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (HostFileSystem.FsException e) {
                // It starts without the example file.
            }
        }
        computers.put(block.deviceId(), computer);
        places.put(block.deviceId(), new Viewing(block.deviceId(), dimension, pos));
        if (block instanceof qbotics.qbot.QbotBlockEntity qbot && block.getLevel() != null && block.getLevel().getServer() != null) {
            qbot.radioIp(block.getLevel().getServer()); // its radio has an address from the start
        }
        booting.remove(block.deviceId());
        block.setOn(true); // it comes back on by itself when its chunk next loads
        if (block instanceof qbotics.block.ServerBlockEntity) {
            startServices(computer, computerId, dimension, pos);
        }
        return computer;
    }

    /**
     * How a computer's program reaches the world: asked on the computer's thread, answered on
     * the server thread at the next tick, from where its block is then (a Qbot moves).
     *
     * @param callerId the computer asking: itself, or one of a Server's services
     * @param placeId  the computer whose block it is (a service's is its Server's)
     */
    /** Items and recipes (the game module): read-only, so answered off the server's main thread (a big plan takes a while). */
    private static final String GAME_PREFIX = "[\"game_";
    private static final java.util.concurrent.ExecutorService GAME_WORKER = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private java.util.function.Function<String, CompletableFuture<String>> worldCalls(UUID callerId, UUID placeId,
            ResourceKey<Level> dimension, BlockPos pos) {
        return call -> {
            // Drawing on a display it holds a handle for is done at once, on the computer's own thread.
            if (call.startsWith(qbotics.display.Displays.DRAW_PREFIX)) {
                return CompletableFuture.completedFuture(qbotics.display.Displays.drawFast(callerId, call));
            }
            CompletableFuture<String> answer = new CompletableFuture<>();
            if (call.startsWith(HTTP_PREFIX)) {
                // The internet: checked on the server thread, fetched on the uplink's own threads.
                outbox.add(server -> {
                    Viewing place = places.get(placeId);
                    ServerComputer self = computers.get(callerId);
                    qbotics.uplink.Uplink.fromComputer(server, place == null ? dimension : place.dimension(), place == null ? pos : place.pos(), call,
                            self == null ? ServerComputer.BASIC_HEAP_BYTES : self.heapBytes(), answer);
                });
                return answer;
            }
            if (call.startsWith(GAME_PREFIX)) {
                outbox.add(server -> GAME_WORKER.execute(() -> answer.complete(qbotics.agent.GameKnowledge.answerCall(server, call))));
                return answer;
            }
            if (call.startsWith(GIT_PREFIX)) {
                outbox.add(server -> {
                    Viewing place = places.get(placeId);
                    ServerComputer self = computers.get(callerId);
                    qbotics.uplink.GitService.fromComputer(server, place == null ? dimension : place.dimension(),
                            place == null ? pos : place.pos(), self == null ? qbotics.engine.HostFileSystem.NONE : self.drive(), call, answer);
                });
                return answer;
            }
            outbox.add(server -> {
                Viewing place = places.get(placeId);
                answer.complete(NetworkCalls.handle(server, place == null ? dimension : place.dimension(),
                        place == null ? pos : place.pos(), call, callerId.equals(placeId) ? null : computers.get(callerId)));
            });
            return answer;
        };
    }

    /** A Server came on: its services, which start now and whenever it comes back on. */
    private void startServices(ServerComputer server, UUID serverId, ResourceKey<Level> dimension, BlockPos pos) {
        Services services = new Services(server.drive(), new Services.Runner() {
            @Override
            public ServerComputer start(String name, String file) {
                UUID id = UUID.randomUUID();
                ServerComputer service = ServerComputer.service(id, server, cpu, listener, worldCalls(id, serverId, dimension, pos), name, file);
                computers.put(id, service);
                return service;
            }

            @Override
            public void stop(ServerComputer service) {
                computers.remove(service.deviceId());
                service.shutDown();
            }
        });
        server.services = services;
        services.boot();
    }

    /** Every few ticks: each Server's rack lights show how its services are doing. */
    private void updateServiceLights(MinecraftServer server) {
        for (Map.Entry<UUID, Viewing> entry : places.entrySet()) {
            ServerComputer computer = computers.get(entry.getKey());
            net.minecraft.server.level.ServerLevel level = server.getLevel(entry.getValue().dimension());
            if (computer == null || computer.services() == null || level == null || !level.isLoaded(entry.getValue().pos())) {
                continue;
            }
            qbotics.block.ServerBlock.showLights(level, entry.getValue().pos(), computer.services().lights());
        }
    }

    /** A running computer (or Qbot) in the world, and where its block is: for the SSH gateway to find it. */
    public record Running(UUID deviceId, ResourceKey<Level> dimension, BlockPos pos, ServerComputer computer) {
    }

    /** Every running computer that has a block (not laptops). Call on the server thread. */
    public java.util.List<Running> running() {
        java.util.List<Running> all = new java.util.ArrayList<>();
        places.forEach((id, place) -> {
            ServerComputer computer = computers.get(id); // (a Server's services have no place of their own, so aren't listed)
            if (computer != null) {
                all.add(new Running(id, place.dimension(), place.pos(), computer));
            }
        });
        return all;
    }

    /** Where computers' own drives are kept (one folder each, named by drive ID). */
    public Path drivesFolder() {
        return drivesFolder;
    }

    /** Calls that go to the real internet (the uplink), not the world. Must match requests.py in the ROM. */
    private static final String HTTP_PREFIX = "[\"http\",";
    /** Calls to git (through the uplink). Must match git.py in the ROM. */
    private static final String GIT_PREFIX = "[\"git\",";

    // ---- laptops (design doc §5.6) ---------------------------------------------------------

    /** Each player's laptop computer, by player: one each, whichever laptop item they use. */
    private final Map<UUID, ServerComputer> laptops = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<UUID, ServerComputer> laptops() {
        return laptops;
    }

    /** A player's laptop computer, started if it isn't running. Its calls are a laptop's (ssh, not cables). */
    public ServerComputer getOrStartLaptop(ServerPlayer player) {
        UUID owner = player.getUUID();
        UUID id = qbotics.laptop.Laptops.deviceId(owner);
        ServerComputer running = computers.get(id);
        if (running != null) {
            laptops.put(owner, running);
            return running;
        }
        UUID driveId = qbotics.laptop.Laptops.driveId(owner);
        ServerComputer computer = new ServerComputer(id, driveId, driveFolder(driveId), cpu, listener, call -> {
            CompletableFuture<String> answer = new CompletableFuture<>();
            if (call.startsWith(HTTP_PREFIX)) {
                qbotics.uplink.Uplink.fromLaptop(owner, call, qbotics.laptop.Laptops.HEAP_BYTES, answer); // its built-in antenna is its uplink
                return answer;
            }
            if (call.startsWith(GIT_PREFIX)) {
                outbox.add(server -> {
                    ServerComputer self = computers.get(id);
                    qbotics.uplink.GitService.run(server, owner, "laptop", self == null ? qbotics.engine.HostFileSystem.NONE : self.drive(), call, answer);
                });
                return answer;
            }
            if (call.startsWith(GAME_PREFIX)) {
                outbox.add(server -> GAME_WORKER.execute(() -> answer.complete(qbotics.agent.GameKnowledge.answerCall(server, call))));
                return answer;
            }
            if (call.startsWith("[\"events_start\"")) {
                // events.run(): the laptop's own computer handles events (its timers, env.watch()); it has
                // no cable network, so there are no pins, displays or messages to sign up for.
                outbox.add(server -> {
                    ServerComputer self = computers.get(id);
                    com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
                    if (self == null) {
                        reply.addProperty("e", "This laptop is switched off.");
                    } else {
                        reply.addProperty("r", self.startEvents(false));
                    }
                    answer.complete(reply.toString());
                });
                return answer;
            }
            outbox.add(server -> answer.complete(NetworkCalls.handleLaptop(server, owner, call)));
            return answer;
        }, false, false, qbotics.laptop.Laptops.HEAP_BYTES, ServerComputer.DRIVE_QUOTA_BYTES);
        computer.lessonOwner = owner; // a laptop is its player's
        computers.put(id, computer);
        laptops.put(owner, computer);
        return computer;
    }

    /** Opens a player's laptop screen: the editor, terminal and files of their laptop computer. */
    public void openLaptopScreen(ServerPlayer player) {
        ServerComputer computer = getOrStartLaptop(player);
        UUID id = computer.deviceId();
        lastOpened.put(player.getUUID(), id);
        viewers.put(player.getUUID(), new Viewing(id, player.level().dimension(), player.blockPosition()));
        Qbotics.platform().sendToPlayer(player, new TerminalStatePayload(id, true, computer.asShown(computer.shownTerminal().full())));
    }

    // ---- the boot queue (design doc §6.3) ------------------------------------------------

    /** Where each running computer is, so a program's shutdown can find its block. */
    private final Map<UUID, Viewing> places = new HashMap<>();

    /** The running Qbot whose radio has this external address ("100.64.0.9"), loaded, or null. */
    public qbotics.qbot.@org.jspecify.annotations.Nullable QbotBlockEntity qbotByRadio(MinecraftServer server, String ip) {
        for (Viewing place : places.values()) {
            net.minecraft.server.level.ServerLevel level = server.getLevel(place.dimension());
            if (level != null && level.isLoaded(place.pos()) && level.getBlockEntity(place.pos()) instanceof qbotics.qbot.QbotBlockEntity qbot
                    && qbot.deviceId().equals(place.deviceId()) && ip.equals(qbot.radioIpIfAny())) {
                return qbot;
            }
        }
        return null;
    }

    /** A running computer's block moved (a Qbot stepped): its program and the players watching it follow. */
    public void moved(UUID deviceId, ResourceKey<Level> dimension, BlockPos to) {
        BlockPos at = to.immutable();
        if (places.containsKey(deviceId)) {
            places.put(deviceId, new Viewing(deviceId, dimension, at));
        }
        viewers.replaceAll((player, viewing) -> viewing.deviceId().equals(deviceId) ? new Viewing(deviceId, dimension, at) : viewing);
        Boot waiting = booting.get(deviceId);
        if (waiting != null) {
            booting.put(deviceId, new Boot(deviceId, dimension, at, waiting.owner()));
        }
    }

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
        if (block.owner() == null && !(block instanceof qbotics.qbot.QbotBlockEntity)) {
            return; // nobody placed it (a command, a structure): it waits for a player to take it on
        }
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

    /**
     * A computer whose part of the world is no longer loaded is stopped. Its block still says it
     * was on, so when that part loads again it starts as from new and runs its startup file.
     * (Left running, its program would fail at its first call on the world, and nothing would
     * start it again.)
     */
    private void stopUnloaded(MinecraftServer server) {
        java.util.List<UUID> gone = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Viewing> entry : places.entrySet()) {
            Viewing place = entry.getValue();
            net.minecraft.server.level.ServerLevel level = server.getLevel(place.dimension());
            if (level == null || !level.isLoaded(place.pos())) {
                gone.add(entry.getKey());
            }
        }
        for (UUID id : gone) {
            places.remove(id);
            viewers.values().removeIf(viewing -> viewing.deviceId().equals(id));
            remove(id);
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
                qbotics.block.ServerBlock.showLights(level, place.pos(), new int[0]); // its services stopped with it
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
        public void programRunning(UUID deviceId, boolean running) {
            outbox.add(server -> {
                Viewing place = places.get(deviceId);
                net.minecraft.server.level.ServerLevel level = place == null ? null : server.getLevel(place.dimension());
                if (level != null && level.isLoaded(place.pos()) && level.getBlockEntity(place.pos()) instanceof qbotics.qbot.QbotBlockEntity qbot) {
                    qbot.setProgramRunning(running);
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
        if (request.op() == IdePayloads.FileRequest.DEVICES) {
            // On the server thread: what is on the computer's cables now. A laptop has none.
            Viewing at = computer == null ? null : places.get(request.deviceId());
            String listed = "[]";
            if (at != null) {
                com.google.gson.JsonObject reply = com.google.gson.JsonParser.parseString(qbotics.network.NetworkCalls.handle(
                        player.level().getServer(), at.dimension(), at.pos(), "[\"devices\"]")).getAsJsonObject();
                if (reply.has("r")) {
                    listed = reply.get("r").toString();
                }
            }
            reply(playerId, request, 0, listed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (request.op() == IdePayloads.FileRequest.CHECK) {
            // Only for someone with this editor open; the drive isn't touched.
            if (computer == null && viewedDrive(player, request.deviceId()) == null) {
                reply(playerId, request, HostFileSystem.EACCES, new byte[0]);
                return;
            }
            FILE_WORKER.execute(() -> reply(playerId, request, 0,
                    CompileCheck.check(request.path(), request.data()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return;
        }
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
                // Its settings (who may open it, its password) are its owner's to change, not a guest's.
                boolean owners = block.owner() == null || block.owner().equals(player.getUUID())
                        || player.level().getServer().getPlayerList().isOp(player.nameAndId());
                String problem = !owners ? "Only this computer's owner changes its settings."
                        : ComputerSettings.write(player.level(), block, new String(request.data(), java.nio.charset.StandardCharsets.UTF_8));
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
            case IdePayloads.FileRequest.RENAME: {
                // Renaming or dragging onto a name that is taken is refused: the file there would be lost unasked.
                String target = new String(request.data(), java.nio.charset.StandardCharsets.UTF_8);
                boolean taken = true;
                try {
                    drive.call(HostFileSystem.STAT, target, none);
                } catch (HostFileSystem.FsException missing) {
                    taken = false;
                }
                if (taken && !target.equals(path)) {
                    throw new HostFileSystem.FsException(HostFileSystem.EEXIST);
                }
                return drive.call(HostFileSystem.RENAME, path, request.data());
            }
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
        lastOpened.put(player.getUUID(), deviceId);
        viewers.put(player.getUUID(), new Viewing(deviceId, player.level().dimension(), block.getBlockPos()));
        Qbotics.platform().sendToPlayer(player, new TerminalStatePayload(deviceId, true, computer.asShown(computer.shownTerminal().full())));
    }

    /**
     * A player opened a Qbot's screen: they are sent its terminal as it changes, so the screen
     * can show the last lines its program printed. Nothing opens; there is no editor.
     */
    public ServerComputer watchOutput(ServerPlayer player, ComputerBlockEntity block) {
        ServerComputer computer = getOrStart(block);
        lastOpened.put(player.getUUID(), block.deviceId());
        viewers.put(player.getUUID(), new Viewing(block.deviceId(), player.level().dimension(), block.getBlockPos()));
        Qbotics.platform().sendToPlayer(player, new TerminalStatePayload(block.deviceId(), false, computer.asShown(computer.shownTerminal().full())));
        return computer;
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

    /** Ctrl+Z in the terminal the player has open. */
    public void onPause(ServerPlayer player, UUID deviceId) {
        ServerComputer computer = viewedComputer(player, deviceId);
        if (computer != null) {
            computer.togglePause();
        }
    }

    /** F8 in the editor the player has open. */
    public void onStep(ServerPlayer player, UUID deviceId, String openFile) {
        ServerComputer computer = viewedComputer(player, deviceId);
        if (computer != null) {
            computer.step(openFile == null || openFile.isEmpty() ? null : openFile);
        }
    }

    /** What each player was last told about where the program they have open is. */
    private final Map<UUID, ServerComputer.Trace> traceSent = new HashMap<>();

    /**
     * Computers someone has open note which line they are on (their engine's traced loop;
     * others run the plain one, at full speed), and those players are told when it changes.
     */
    private void updateTraces(MinecraftServer server) {
        java.util.Set<UUID> open = new java.util.HashSet<>();
        for (Viewing viewing : viewers.values()) {
            open.add(viewing.deviceId());
        }
        for (Map.Entry<UUID, ServerComputer> entry : computers.entrySet()) {
            entry.getValue().setWatched(open.contains(entry.getKey()));
        }
        traceSent.keySet().retainAll(viewers.keySet());
        for (Map.Entry<UUID, Viewing> entry : viewers.entrySet()) {
            ServerComputer computer = computers.get(entry.getValue().deviceId());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (computer == null || player == null) {
                continue;
            }
            ServerComputer.Trace now = computer.trace();
            if (!now.equals(traceSent.get(entry.getKey()))) {
                traceSent.put(entry.getKey(), now);
                Qbotics.platform().sendToPlayer(player, new qbotics.net.TracePayload(entry.getValue().deviceId(), now.file(), now.line(), now.paused()));
            }
        }
    }

    /** The computer a player has open in the editor, or null. */
    public UUID viewedDevice(UUID player) {
        Viewing viewing = viewers.get(player);
        return viewing == null ? null : viewing.deviceId();
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
        lastOpened.put(player.getUUID(), programmer.deviceId());
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
        if (qbotics.laptop.Laptops.isLaptopOf(viewing.deviceId(), player.getUUID())) {
            return true; // a laptop goes where its player goes
        }
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
        if (server.getTickCount() % 20 == 0) {
            stopUnloaded(server);
        }
        qbotics.laptop.Laptops.tick(server);
        qbotics.display.Displays.tick(server);
        if (server.getTickCount() % 10 == 0) {
            updateMounts(server);
            updateServiceLights(server);
        }
        qbotics.network.ScreenSessions.tick(server, session -> NetworkCalls.interfaces(
                qbotics.network.CableNetwork.at(qbotics.network.ScreenSessions.levelOf(session), qbotics.network.ScreenSessions.busPosOf(session))));
        if (server.getTickCount() % 4 == 0) {
            updateTraces(server);
        }
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
                filesSeen.remove(entry.getKey());
                continue;
            }
            TerminalStatePayload update = changed.get(entry.getValue().deviceId());
            if (update != null) {
                Qbotics.platform().sendToPlayer(player, update);
            }
            // Files changed from the shell or a program (git clone, a script writing): the
            // explorer only knows of its own changes, so tell it to look again. Twice a second.
            ServerComputer computer = computers.get(entry.getValue().deviceId());
            if (computer != null && server.getTickCount() % 10 == 0) {
                long now = computer.fileChanges();
                Long seen = filesSeen.put(entry.getKey(), now);
                if (seen != null && seen != now) {
                    Qbotics.platform().sendToPlayer(player, new IdePayloads.FilesChanged(entry.getValue().deviceId()));
                }
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
        if (computer == null) {
            return false;
        }
        // A Server: if its terminal's program isn't listening for messages but a service is, the service gets them.
        Services services = computer.services();
        if (services != null && !computer.listensForMessages()) {
            ServerComputer listening = services.listening();
            if (listening != null) {
                return listening.deliver(sender, data);
            }
        }
        return computer.deliver(sender, data);
    }

    /** The computer block was broken. */
    public void remove(UUID deviceId) {
        booting.remove(deviceId);
        ServerComputer computer = computers.remove(deviceId);
        if (computer != null) {
            laptops.values().removeIf(laptop -> laptop == computer);
            if (computer.services() != null) {
                computer.services().shutDownAll();
            }
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
