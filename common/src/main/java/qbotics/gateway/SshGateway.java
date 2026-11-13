package qbotics.gateway;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsConfig;
import qbotics.block.ComputerBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.laptop.Laptops;

/**
 * The SSH gateway (design doc §9.5): an SSH and SFTP server inside the game, so tools on a
 * player's PC reach in-game computers. VS Code's SSH FS extension opens a computer's files as a
 * folder and its terminal as a terminal; its SFTP extension syncs them with a folder on the PC
 * (where real Git works); ssh and scp work as anywhere.
 *
 * <ul>
 *   <li><b>Off by default</b>; it listens on 127.0.0.1 (this PC only) unless the admin opens it.</li>
 *   <li><b>Keys only:</b> a player adds their public key in game ({@code /qbotics ssh key add});
 *       the key says who is logging in. Banned players are refused.</li>
 *   <li><b>The login name picks the computer:</b> "laptop" (their laptop), or a computer's or
 *       Qbot's name or hardware address. Only their own, unless {@code ssh_gateway.all_computers}.</li>
 *   <li><b>The shell</b> is that computer's terminal (Ctrl+C, Ctrl+Z as in game); <b>SFTP</b>
 *       serves its own drive, the real folder, within its quota. Nothing else (no forwarding).</li>
 * </ul>
 */
public final class SshGateway {
    private SshGateway() {
    }

    /** Who a session's key belongs to. */
    static final AttributeRepository.AttributeKey<UUID> PLAYER = new AttributeRepository.AttributeKey<>();

    private static @Nullable SshServer server;
    private static @Nullable SshKeys keys;
    private static @Nullable MinecraftServer game;

    /** The players' keys, kept with the world (the command uses them whether or not the gateway is on). */
    public static synchronized SshKeys keys(MinecraftServer mc) {
        if (keys == null || game != mc) {
            game = mc;
            keys = new SshKeys(mc.getWorldPath(LevelResource.ROOT).resolve("data/qbotics/ssh_keys.json"));
        }
        return keys;
    }

    public static synchronized boolean running() {
        return server != null && server.isStarted();
    }

    /** Starts the gateway, if the server config turns it on. */
    public static synchronized void start(MinecraftServer mc) {
        if (!QboticsConfig.sshEnabled || running()) {
            return;
        }
        start(mc, QboticsConfig.sshBindAddress, QboticsConfig.sshPort);
    }

    /** Starts it on this address and port (tests use a free port). Returns the port it listens on. */
    public static synchronized int start(MinecraftServer mc, String address, int port) {
        stop();
        SshKeys known = keys(mc);
        Path data = mc.getWorldPath(LevelResource.ROOT).resolve("data/qbotics");
        SshServer ssh = SshServer.setUpDefaultServer();
        ssh.setHost(address);
        ssh.setPort(port);
        ssh.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(data.resolve("ssh_host_key")));
        // Keys only: the key says which player this is.
        ssh.setPasswordAuthenticator(null);
        ssh.setKeyboardInteractiveAuthenticator(null);
        ssh.setPublickeyAuthenticator((username, key, session) -> {
            UUID player = known.ownerOf(key);
            if (player == null || banned(mc, player)) {
                return false;
            }
            session.setAttribute(PLAYER, player);
            return true;
        });
        ssh.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        ssh.setShellFactory(channel -> new GatewayShell(mc));
        // ssh name@server "command": one command, its output and status (scripts, AI tools).
        ssh.setCommandFactory((channel, command) -> new GatewayExec(mc, command));
        SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder().build();
        sftp.addSftpEventListener(QUOTA);
        ssh.setSubsystemFactories(List.of(sftp));
        ssh.setFileSystemFactory(new FileSystemFactory() {
            @Override
            public Path getUserHomeDir(SessionContext session) throws IOException {
                return root(session); // the top of the computer's drive is home
            }

            @Override
            public FileSystem createFileSystem(SessionContext session) throws IOException {
                return new RootedFileSystemProvider().newFileSystem(root(session), Map.of());
            }

            /** The computer's drive folder: absolute and tidy, as the rooted file system needs it. */
            private Path root(SessionContext session) throws IOException {
                Path known = session.getAttribute(ROOT);
                if (known != null) {
                    return known;
                }
                Target target = target(mc, (ServerSession) session);
                Path root = target.drive().toAbsolutePath().normalize();
                session.setAttribute(QUOTA_BYTES, target.computer() == null ? ServerComputer.DRIVE_QUOTA_BYTES : target.computer().quotaBytes());
                Files.createDirectories(root);
                session.setAttribute(ROOT, root);
                return root;
            }
        });
        try {
            ssh.start();
        } catch (IOException e) {
            Qbotics.LOGGER.error("The SSH gateway could not start on {}:{}", address, port, e);
            return -1;
        }
        server = ssh;
        Qbotics.LOGGER.info("SSH gateway listening on {}:{}", address, ssh.getPort());
        return ssh.getPort();
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop(true);
            } catch (IOException e) {
                Qbotics.LOGGER.warn("The SSH gateway didn't stop cleanly", e);
            }
            server = null;
        }
    }

    static boolean banned(MinecraftServer mc, UUID player) {
        return mc.getPlayerList().getBans().isBanned(new NameAndId(player, "?"));
    }

    // ---- which computer a login reaches ---------------------------------------------------

    /** A refusal, in words for the player at the other end. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    /** The computer a session reaches: its identity, its drive folder, what to call it, and it running (or null). */
    record Target(UUID deviceId, Path drive, String label, @Nullable ServerComputer computer, boolean laptop) {
    }

    /** The root of a session's SFTP files, for the quota check. */
    static final AttributeRepository.AttributeKey<Path> ROOT = new AttributeRepository.AttributeKey<>();
    static final AttributeRepository.AttributeKey<Long> QUOTA_BYTES = new AttributeRepository.AttributeKey<>();

    /** The computer this session's login name picks, worked out on the server thread. */
    static Target target(MinecraftServer mc, ServerSession session) throws IOException {
        UUID player = session.getAttribute(PLAYER);
        if (player == null) {
            throw new Refused("Not logged in.");
        }
        String name = session.getUsername();
        Object found;
        try {
            found = mc.submit(() -> {
                try {
                    return (Object) resolve(mc, player, name);
                } catch (Refused refused) {
                    return refused;
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("The game couldn't look that computer up: " + e.getCause(), e);
        } catch (Exception e) {
            throw new IOException("The game didn't answer in time.", e);
        }
        if (found instanceof Refused refused) {
            throw refused;
        }
        return (Target) found;
    }

    /** On the server thread. */
    private static Target resolve(MinecraftServer mc, UUID player, String name) throws Refused {
        if (banned(mc, player)) {
            throw new Refused("You are banned from this server.");
        }
        ComputerManager manager = ComputerManager.get(mc);
        if (name.equalsIgnoreCase("laptop")) {
            return new Target(Laptops.deviceId(player), manager.driveFolder(Laptops.driveId(player)), "your laptop",
                    manager.laptops().get(player), true);
        }
        boolean everyone = QboticsConfig.sshAllComputers;
        List<Target> found = new ArrayList<>();
        List<String> yours = new ArrayList<>();
        for (ComputerManager.Running running : manager.running()) {
            ServerLevel level = mc.getLevel(running.dimension());
            if (level == null || !level.isLoaded(running.pos())
                    || !(level.getBlockEntity(running.pos()) instanceof ComputerBlockEntity block)) {
                continue;
            }
            if (!everyone && !player.equals(block.owner())) {
                continue;
            }
            String label = block.deviceName().isEmpty() ? block.mac() : block.deviceName();
            yours.add(label);
            String id = running.deviceId().toString();
            if (name.equalsIgnoreCase(block.deviceName()) || name.equalsIgnoreCase(block.mac()) || name.equals(id)
                    || (name.length() >= 8 && id.startsWith(name.toLowerCase(java.util.Locale.ROOT)))) {
                found.add(new Target(running.deviceId(), manager.driveFolder(block.driveId()), label, running.computer(), false));
            }
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        if (found.size() > 1) {
            throw new Refused("Several computers are called " + name + ". Log in with one's hardware address instead (the probe shows it).");
        }
        String others = yours.isEmpty() ? "none is running (their areas must be loaded)" : String.join(", ", yours);
        throw new Refused("No " + (everyone ? "" : "computer of yours ") + "called " + name + " is running. Log in as \"laptop\", or one of: "
                + others + ".");
    }

    // ---- SFTP stays within a drive's quota --------------------------------------------------

    private static final SftpEventListener QUOTA = new SftpEventListener() {
        @Override
        public void writing(ServerSession session, String remoteHandle, FileHandle localHandle, long offset, byte[] data,
                int dataOffset, int dataLen) throws IOException {
            Path root = session.getAttribute(ROOT);
            if (root == null) {
                return;
            }
            long used;
            try (Stream<Path> files = Files.walk(root)) {
                used = files.filter(Files::isRegularFile).mapToLong(file -> {
                    try {
                        return Files.size(file);
                    } catch (IOException e) {
                        return 0;
                    }
                }).sum();
            }
            Long quota = session.getAttribute(QUOTA_BYTES);
            long limit = quota == null ? ServerComputer.DRIVE_QUOTA_BYTES : quota;
            if (used + dataLen > limit) {
                throw new IOException("The computer's drive is full (" + limit / 1024 + " KB).");
            }
        }
    };
}
