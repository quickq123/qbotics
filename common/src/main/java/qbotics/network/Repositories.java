package qbotics.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import qbotics.block.DriveBayBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.engine.HostFileSystem;
import qbotics.network.NetworkCalls.CallError;

/**
 * Hard drives used as package repositories (design doc §17.6): a program, or the shell's pip,
 * reads libraries from a drive in a Drive Bay, on its own network or on another one reached by
 * radio, and installs them into its computer's /lib.
 *
 * <p>A source is written {@code [external IP/]bay/drive}: "100.64.0.2/shared/lib" is the drive
 * "lib" in the bay "shared" behind the antenna at 100.64.0.2; "shared/lib" is on this network.
 * Reaching another network follows the radio's rules (same frequency, in range, and that
 * antenna's access setting must let this computer's owner in), and other networks' drives can
 * only be read, never changed.
 */
final class Repositories {
    /** The most a package may hold, so an install can't run away. */
    private static final int MAX_FILES = 200;
    private static final int CHUNK_BYTES = 32 * 1024;

    private Repositories() {
    }

    static JsonElement call(String method, ServerLevel level, CableNetwork network, BlockPos computerPos, JsonArray args) {
        HostFileSystem drive = drive(level, network, computerPos, NetworkCalls.string(args, 1));
        return switch (method) {
            case "repo_list" -> list(drive);
            case "repo_tree" -> tree(drive, NetworkCalls.string(args, 2), NetworkCalls.string(args, 1));
            case "repo_read" -> read(drive, NetworkCalls.string(args, 2), args.size() > 3 ? args.get(3).getAsInt() : 0);
            default -> throw new CallError("This computer doesn't know the call '" + method + "'.");
        };
    }

    /** The drive a source names: on this network, or behind another antenna. */
    private static HostFileSystem drive(ServerLevel level, CableNetwork network, BlockPos computerPos, String source) {
        if (source == null || source.isBlank()) {
            throw new CallError("Say where the packages are: a source such as shared/lib (a bay and a drive on this network) "
                    + "or 100.64.0.2/shared/lib (behind another antenna).");
        }
        String[] parts = source.strip().split("/");
        CableNetwork where = network;
        ServerLevel whereLevel = level;
        int at = 0;
        if (parts.length == 3) {
            String ip = parts[0].contains(":") ? parts[0].substring(0, parts[0].indexOf(':')) : parts[0];
            NetworkCalls.Far far = NetworkCalls.far(level, network, ip, computerPos);
            if (far.network() == null) {
                throw new CallError("The antenna at " + ip + " isn't its network's router, so its drives can't be reached.");
            }
            where = far.network();
            whereLevel = far.level();
            at = 1;
        } else if (parts.length != 2) {
            throw new CallError("A source is a bay and a drive (shared/lib), with an antenna's address in front for "
                    + "another network (100.64.0.2/shared/lib); not '" + source + "'.");
        }
        String bayName = parts[at];
        String driveName = parts[at + 1];
        List<String> bays = new ArrayList<>();
        for (NetworkDevice device : where.devices()) {
            if (device instanceof DriveBayBlockEntity bay) {
                bays.add(bay.folderName());
                if (bay.folderName().equals(bayName)) {
                    UUID media = bay.mounted().get(driveName);
                    if (media == null) {
                        throw new CallError("The bay " + bayName + " has no drive called " + driveName + ". It has: "
                                + (bay.mounted().isEmpty() ? "no drives" : String.join(", ", bay.mounted().keySet())) + ".");
                    }
                    return ComputerManager.get(whereLevel.getServer()).mediaFiles(media);
                }
            }
        }
        throw new CallError("There is no drive bay called " + bayName + (where == network ? " on this network" : " there") + ". "
                + (bays.isEmpty() ? "There are no drive bays." : "There are: " + String.join(", ", bays) + "."));
    }

    /** What packages a drive has: its .py files and its folders, by name. */
    private static JsonArray list(HostFileSystem drive) {
        JsonArray names = new JsonArray();
        for (String[] entry : entries(drive, "/")) {
            if (entry[0].equals("d")) {
                names.add(entry[1]);
            } else if (entry[1].endsWith(".py")) {
                names.add(entry[1].substring(0, entry[1].length() - 3));
            }
        }
        return names;
    }

    /** Every file of one package, as paths to copy into /lib: "greetings.py", or "tools/__init__.py" and the rest. */
    private static JsonObject tree(HostFileSystem drive, String name, String source) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new CallError("'" + name + "' isn't a package name: letters, digits and _ only, not starting with a digit.");
        }
        List<String> files = new ArrayList<>();
        if (kind(drive, "/" + name + ".py") == 'f') {
            files.add(name + ".py");
        } else if (kind(drive, "/" + name) == 'd') {
            collect(drive, name, files);
        } else {
            JsonArray there = list(drive);
            List<String> names = new ArrayList<>();
            there.forEach(element -> names.add(element.getAsString()));
            throw new CallError("There is no package called " + name + " on " + source + ". "
                    + (names.isEmpty() ? "It has none." : "It has: " + String.join(", ", names) + "."));
        }
        JsonObject reply = new JsonObject();
        JsonArray paths = new JsonArray();
        files.forEach(paths::add);
        reply.add("files", paths);
        return reply;
    }

    private static void collect(HostFileSystem drive, String folder, List<String> files) {
        for (String[] entry : entries(drive, "/" + folder)) {
            String path = folder + "/" + entry[1];
            if (entry[0].equals("d")) {
                collect(drive, path, files);
            } else {
                files.add(path);
            }
            if (files.size() > MAX_FILES) {
                throw new CallError("That package has more than " + MAX_FILES + " files.");
            }
        }
    }

    /** Part of a file, as text: {"data": ..., "more": true while there is more after it}. */
    private static JsonObject read(HostFileSystem drive, String path, int offset) {
        if (path == null || path.contains("..")) {
            throw new CallError("Not a path in a package: " + path);
        }
        byte[] all;
        try {
            all = drive.call(HostFileSystem.READ, "/" + path, new byte[0]);
        } catch (HostFileSystem.FsException e) {
            throw new CallError("Couldn't read " + path + " (error " + e.errno + ").");
        }
        int from = Math.clamp(offset, 0, all.length);
        int to = Math.min(all.length, from + CHUNK_BYTES);
        // Never cut a character in two: back up to the start of one.
        while (to < all.length && to > from && (all[to] & 0xC0) == 0x80) {
            to--;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("data", new String(all, from, to - from, StandardCharsets.UTF_8));
        reply.addProperty("next", to);
        reply.addProperty("more", to < all.length);
        return reply;
    }

    private static char kind(HostFileSystem drive, String path) {
        try {
            return new String(drive.call(HostFileSystem.STAT, path, new byte[0]), StandardCharsets.UTF_8).charAt(0);
        } catch (HostFileSystem.FsException e) {
            return '-';
        }
    }

    /** A folder's entries as {"d" or "f", name}. */
    private static List<String[]> entries(HostFileSystem drive, String folder) {
        List<String[]> found = new ArrayList<>();
        try {
            String listing = new String(drive.call(HostFileSystem.LIST, folder, new byte[0]), StandardCharsets.UTF_8);
            for (String line : listing.split("\n")) {
                String[] fields = line.split("\t");
                if (fields.length == 3) {
                    found.add(new String[] {fields[0], fields[2]});
                }
            }
        } catch (HostFileSystem.FsException e) {
            // an empty or missing folder: nothing in it
        }
        return found;
    }
}
