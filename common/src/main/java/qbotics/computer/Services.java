package qbotics.computer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import qbotics.engine.HostFileSystem;

/**
 * A Server's services (design doc §3.4): up to four programs running in the background, each
 * in its own Python with its own memory, beside whatever the Server's terminal is doing. Each
 * has a light on the rack: green while it runs, red if it failed.
 *
 * <p>The list is kept on the Server's drive, in {@code /.services}, so the services start again
 * when the Server comes back on. {@code service stop} takes one off the list.
 */
public final class Services {
    public static final int MAX = 4;
    /** Each service's memory: a computer's. The terminal keeps the Server's 2 MB. */
    public static final int HEAP_BYTES = 256 * 1024;
    static final String FILE = "/.services";
    /** Lines {@code service log} shows. */
    private static final int LOG_LINES = 40;

    /** Light states on the rack, as the block's light properties hold them. */
    public static final int OFF = 0;
    public static final int RUNNING = 1;
    public static final int FAILED = 2;

    /** Starts a service's own Python, and stops it. Given by the ComputerManager. */
    interface Runner {
        ServerComputer start(String name, String file);

        void stop(ServerComputer service);
    }

    private final HostFileSystem drive;
    private final Runner runner;
    private final String[] names = new String[MAX];
    private final String[] files = new String[MAX];
    private final ServerComputer[] running = new ServerComputer[MAX];

    Services(HostFileSystem drive, Runner runner) {
        this.drive = drive;
        this.runner = runner;
    }

    /** The Server came on: start the services on its list. */
    synchronized void boot() {
        try {
            JsonArray list = JsonParser.parseString(new String(drive.call(HostFileSystem.READ, FILE, new byte[0]), StandardCharsets.UTF_8))
                    .getAsJsonArray();
            for (JsonElement entry : list) {
                JsonObject service = entry.getAsJsonObject();
                int slot = free();
                if (slot < 0) {
                    break;
                }
                names[slot] = service.get("name").getAsString();
                files[slot] = service.get("file").getAsString();
                running[slot] = runner.start(names[slot], files[slot]);
            }
        } catch (HostFileSystem.FsException | RuntimeException e) {
            // No list yet, or one that was edited badly: nothing to start.
        }
    }

    private int free() {
        for (int slot = 0; slot < MAX; slot++) {
            if (names[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    private int find(String name) {
        for (int slot = 0; slot < MAX; slot++) {
            if (name.equals(names[slot])) {
                return slot;
            }
        }
        return -1;
    }

    private void save() {
        JsonArray list = new JsonArray();
        for (int slot = 0; slot < MAX; slot++) {
            if (names[slot] != null) {
                JsonObject service = new JsonObject();
                service.addProperty("name", names[slot]);
                service.addProperty("file", files[slot]);
                list.add(service);
            }
        }
        try {
            drive.call(HostFileSystem.WRITE, FILE, list.toString().getBytes(StandardCharsets.UTF_8));
        } catch (HostFileSystem.FsException e) {
            // A full drive: the services run, but won't start again by themselves.
        }
    }

    /** Starts a program as a service. Returns what to tell the player. */
    synchronized String start(String file, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,24}")) {
            return "A service's name is letters, digits, - and _ (up to 24).";
        }
        if (find(name) >= 0) {
            return "There is already a service called " + name + ". service restart " + name + " runs it again.";
        }
        int slot = free();
        if (slot < 0) {
            return "A Server runs " + MAX + " services at most. service stop <name> frees one.";
        }
        names[slot] = name;
        files[slot] = file;
        running[slot] = runner.start(name, file);
        save();
        return "Started " + name + " (" + file + "). service log " + name + " shows what it prints.";
    }

    synchronized String stop(String name) {
        int slot = find(name);
        if (slot < 0) {
            return "No service is called " + name + ".";
        }
        runner.stop(running[slot]);
        names[slot] = null;
        files[slot] = null;
        running[slot] = null;
        save();
        return "Stopped " + name + "; it won't start again.";
    }

    synchronized String restart(String name) {
        int slot = find(name);
        if (slot < 0) {
            return "No service is called " + name + ".";
        }
        runner.stop(running[slot]);
        running[slot] = runner.start(names[slot], files[slot]);
        return "Restarted " + name + ".";
    }

    synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        for (int slot = 0; slot < MAX; slot++) {
            if (names[slot] != null) {
                out.add(String.format("%d  %-12s %-8s %s", slot + 1, names[slot], running[slot].serviceState().word, files[slot]));
            }
        }
        if (out.isEmpty()) {
            out.add("No services. service start <file.py> [name] runs a program in the background.");
        }
        return out;
    }

    /** The last lines a service printed. */
    synchronized String log(String name) {
        int slot = find(name);
        if (slot < 0) {
            return "No service is called " + name + ".\n";
        }
        Terminal.State state = running[slot].terminal().full();
        List<String> lines = new ArrayList<>(state.scrolled());
        lines.addAll(state.lines());
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size());
        return tail.isEmpty() ? "(" + name + " has printed nothing yet)\n" : String.join("\n", tail) + "\n";
    }

    /** What each of the rack's four lights shows. */
    public synchronized int[] lights() {
        int[] lights = new int[MAX];
        for (int slot = 0; slot < MAX; slot++) {
            ServerComputer service = running[slot];
            if (service != null) {
                ServerComputer.ServiceState state = service.serviceState();
                lights[slot] = state == ServerComputer.ServiceState.RUNNING || state == ServerComputer.ServiceState.STARTING ? RUNNING
                        : state == ServerComputer.ServiceState.FAILED ? FAILED : OFF;
            }
        }
        return lights;
    }

    /** The first service whose program waits for messages, or null. */
    synchronized ServerComputer listening() {
        for (ServerComputer service : running) {
            if (service != null && service.listensForMessages()) {
                return service;
            }
        }
        return null;
    }

    /** The running service with this name, or null. For tests. */
    public synchronized ServerComputer get(String name) {
        int slot = find(name);
        return slot < 0 ? null : running[slot];
    }

    /** The Server went off: every service stops, and the list stays for next time. */
    synchronized void shutDownAll() {
        for (int slot = 0; slot < MAX; slot++) {
            if (running[slot] != null) {
                runner.stop(running[slot]);
                running[slot] = null;
            }
        }
    }
}
