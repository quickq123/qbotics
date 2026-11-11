package qbotics.block;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.engine.DriveFileSystem;
import qbotics.engine.HostFileSystem;
import qbotics.limits.Limits;

/**
 * What a Programmer does (design doc §5.4): copies a program from its hard drive onto the
 * computer in front of its programming face, makes that computer its owner's (switching it on
 * if a machine placed it, within the owner's limit), and restarts it so its startup.py runs.
 *
 * <p>Which program: the drive's programs.txt maps redstone levels to folders on the drive
 * ("3: miner", and "*: basic" for any other level); with no programs.txt, the whole drive is
 * the program. When: the redstone signal rises from 0 (that level's program), a computer is
 * placed in front of it (the program for its signal now, or "*"), or a computer on its
 * network asks.
 *
 * <p>Two Programmers facing one block: each acts on its own redstone, but a block is
 * programmed at most once a tick. When a computer is placed with several facing it, only the
 * one with the strongest redstone signal acts; a tie goes to the first in the order down, up,
 * north, south, west, east.
 */
public final class Programmers {
    /** The file on a drive that maps redstone levels to programs. */
    public static final String PROGRAMS_FILE = "programs.txt";
    /** The most files one program may hold. */
    private static final int MAX_FILES = 200;
    /** When each block was last programmed, so two Programmers can't both do it in one tick. */
    private static final Map<String, Long> LAST = new HashMap<>();

    private Programmers() {
    }

    /** How a programming attempt went. */
    public record Result(boolean ok, String message) {
    }

    /** The redstone around a Programmer changed: on a rise from nothing, it programs with the new level. */
    public static void signalChanged(ServerLevel level, ProgrammerBlockEntity programmer) {
        int now = level.getBestNeighborSignal(programmer.getBlockPos());
        int before = programmer.lastSignal();
        programmer.setLastSignal(now);
        if (before == 0 && now > 0) {
            program(level, programmer, String.valueOf(now));
        }
    }

    /**
     * A computer has just been placed. If any Programmer faces it, the one with the strongest
     * redstone signal programs it (a tie: the first in Direction's order).
     */
    public static void arrived(ServerLevel level, ComputerBlockEntity computer) {
        ProgrammerBlockEntity best = null;
        int bestSignal = -1;
        for (Direction side : Direction.values()) {
            BlockPos at = computer.getBlockPos().relative(side);
            if (level.getBlockEntity(at) instanceof ProgrammerBlockEntity programmer && programmer.facing() == side.getOpposite()
                    && programmer.mediaId() != null) {
                int signal = level.getBestNeighborSignal(at);
                if (signal > bestSignal) {
                    best = programmer;
                    bestSignal = signal;
                }
            }
        }
        if (best != null) {
            program(level, best, bestSignal > 0 ? String.valueOf(bestSignal) : "*");
        }
    }

    /**
     * Programs the computer in front of a Programmer.
     *
     * @param choice a redstone level ("1" to "15"), "*" for the default, or the name of a folder on the drive
     */
    public static Result program(ServerLevel level, ProgrammerBlockEntity programmer, String choice) {
        Result result = attempt(level, programmer, choice);
        programmer.setStatus(result.message());
        // Players nearby see how it went, above their hotbar.
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(programmer.getBlockPos()) < 16 * 16) {
                player.sendOverlayMessage(Component.literal("Programmer: " + result.message()));
            }
        }
        return result;
    }

    private static Result attempt(ServerLevel level, ProgrammerBlockEntity programmer, String choice) {
        UUID media = programmer.mediaId();
        if (media == null) {
            return new Result(false, "No hard drive in it.");
        }
        BlockPos targetPos = programmer.getBlockPos().relative(programmer.facing());
        if (!(level.getBlockEntity(targetPos) instanceof ComputerBlockEntity target)) {
            return new Result(false, "Nothing it can program is in front of it.");
        }
        UUID owner = programmer.owner();
        if (owner == null) {
            return new Result(false, "This Programmer has no owner (it wasn't placed by a player), so it can't program anything.");
        }
        if (target.owner() != null && !target.owner().equals(owner)) {
            return new Result(false, "That computer belongs to someone else.");
        }
        ComputerManager manager = ComputerManager.get(level.getServer());
        HostFileSystem from = manager.mediaFiles(media);
        String folder;
        try {
            folder = chooseFolder(from, choice);
        } catch (IllegalArgumentException e) {
            return new Result(false, e.getMessage());
        }
        String key = level.dimension().identifier() + " " + targetPos.asLong();
        long now = level.getGameTime();
        Long last = LAST.get(key);
        if (last != null && last == now) {
            return new Result(false, "That computer was already programmed this tick (by this or another Programmer).");
        }
        if (target.bricked() || target.owner() == null) {
            String refused = Limits.activateFor(level, target, owner);
            if (refused != null) {
                return new Result(false, refused);
            }
        }
        // Onto the computer's own drive: through its running file system if it is on, else straight to its folder.
        ServerComputer running = manager.computer(target.deviceId());
        HostFileSystem to = running != null ? running.drive() : new DriveFileSystem(manager.driveFolder(target.driveId()), 1024 * 1024);
        int[] copied = {0};
        try {
            copy(from, folder.isEmpty() ? "/" : "/" + folder, to, "/", folder.isEmpty(), copied);
        } catch (HostFileSystem.FsException e) {
            return new Result(false, "Copying stopped part-way (error " + e.errno + "): the computer's drive may be full.");
        }
        LAST.values().removeIf(tick -> tick < now); // only this tick's matter
        LAST.put(key, now);
        // Restart it, so the new program's startup.py runs.
        manager.remove(target.deviceId());
        manager.getOrStart(target);
        String what = folder.isEmpty() ? "the drive" : "'" + folder + "'";
        return new Result(true, "Installed " + what + " (" + copied[0] + " file" + (copied[0] == 1 ? "" : "s") + ") and restarted the computer.");
    }

    /** The folder to install for a choice, by programs.txt; "" for the whole drive. */
    static String chooseFolder(HostFileSystem drive, String choice) {
        String table = null;
        try {
            table = new String(drive.call(HostFileSystem.READ, "/" + PROGRAMS_FILE, new byte[0]), StandardCharsets.UTF_8);
        } catch (HostFileSystem.FsException e) {
            // no programs.txt
        }
        boolean isLevel = choice.equals("*") || choice.matches("\\d{1,2}");
        if (!isLevel) {
            // A folder named outright (from a program).
            if (!isFolder(drive, "/" + choice)) {
                throw new IllegalArgumentException("The drive has no folder called " + choice + ".");
            }
            return choice;
        }
        if (table == null) {
            return ""; // no programs.txt: the whole drive is the program
        }
        String fallback = null;
        for (String raw : table.split("\n")) {
            String line = raw.split("#")[0].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("[:\\s]+", 2);
            if (parts.length < 2) {
                continue;
            }
            String folder = parts[1].strip();
            if (parts[0].equals(choice)) {
                return checked(drive, folder);
            }
            if (parts[0].equals("*")) {
                fallback = folder;
            }
        }
        if (fallback != null) {
            return checked(drive, fallback);
        }
        throw new IllegalArgumentException("programs.txt has no program for " + (choice.equals("*") ? "no signal" : "level " + choice)
                + ", and no '*' line for any other.");
    }

    private static String checked(HostFileSystem drive, String folder) {
        if (!folder.matches("[A-Za-z0-9_.\\-]+") || !isFolder(drive, "/" + folder)) {
            throw new IllegalArgumentException("programs.txt names " + folder + ", but the drive has no folder of that name.");
        }
        return folder;
    }

    private static boolean isFolder(HostFileSystem drive, String path) {
        try {
            return new String(drive.call(HostFileSystem.STAT, path, new byte[0]), StandardCharsets.UTF_8).startsWith("d");
        } catch (HostFileSystem.FsException e) {
            return false;
        }
    }

    /** Copies a folder's files into another, overwriting files of the same name and making folders as needed. */
    private static void copy(HostFileSystem from, String fromDir, HostFileSystem to, String toDir, boolean skipTable, int[] copied)
            throws HostFileSystem.FsException {
        String listing = new String(from.call(HostFileSystem.LIST, fromDir, new byte[0]), StandardCharsets.UTF_8);
        for (String line : listing.split("\n")) {
            String[] fields = line.split("\t");
            if (fields.length != 3) {
                continue;
            }
            String name = fields[2];
            if (skipTable && fromDir.equals("/") && name.equals(PROGRAMS_FILE)) {
                continue; // the table itself isn't part of the program
            }
            String source = (fromDir.endsWith("/") ? fromDir : fromDir + "/") + name;
            String target = (toDir.endsWith("/") ? toDir : toDir + "/") + name;
            if (fields[0].equals("d")) {
                try {
                    to.call(HostFileSystem.MKDIR, target, new byte[0]);
                } catch (HostFileSystem.FsException e) {
                    if (e.errno != HostFileSystem.EEXIST) {
                        throw e;
                    }
                }
                copy(from, source, to, target, false, copied);
            } else {
                if (++copied[0] > MAX_FILES) {
                    throw new HostFileSystem.FsException(HostFileSystem.EFBIG);
                }
                to.call(HostFileSystem.WRITE, target, from.call(HostFileSystem.READ, source, new byte[0]));
            }
        }
    }
}
