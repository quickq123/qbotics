package qbotics.computer;

import java.nio.charset.StandardCharsets;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import qbotics.Qbotics;
import qbotics.engine.Computer;
import qbotics.engine.DriveFileSystem;
import qbotics.engine.HostFileSystem;
import qbotics.engine.HostFileSystem.FsException;
import qbotics.ide.Paths;

/**
 * One running computer on the server: its Python instance, its screen, and the thread that
 * runs its shell. It is independent of the block's chunk being loaded (design doc §6.2).
 *
 * <p>The terminal starts in a small shell (ls, cd, run, python, ...), the way a terminal in
 * a code editor does. Everything the computer is asked to do arrives as a job on one queue
 * and is done by the computer's own thread, one at a time.
 */
public final class ServerComputer {
    /** Basic tier heap (design doc §3.4). */
    private static final int HEAP_BYTES = 256 * 1024;
    /** Design doc §15: files.drive_quota_kb. Will come from config. */
    private static final long DRIVE_QUOTA_BYTES = 1024 * 1024;
    private static final int MAX_QUEUED_JOBS = 256;
    private static final int MAX_CAT_BYTES = 16 * 1024;
    public static final int MAX_SCRATCH_OUTPUT = 8000;
    private static final String STARTUP_FILE = "/startup.py";

    /** Things a computer tells the players looking at it. Called on the computer's thread. */
    public interface Listener {
        void scratchResult(UUID deviceId, UUID player, int runId, boolean ok, String output);

        void openInEditor(UUID deviceId, String path);

        /** Another computer that is switched on, or null. Used by ssh. */
        ServerComputer running(UUID deviceId);

        /** This computer's program typed shutdown: switch it off until someone opens it again. */
        void shutDownRequested(UUID deviceId);
    }

    private sealed interface Job {
    }

    private record Line(String text) implements Job {
    }

    private record RunFile(String path) implements Job {
    }

    private record Scratch(UUID player, int runId, String code) implements Job {
    }

    private record Interrupt() implements Job {
    }

    private final UUID deviceId;
    private final UUID driveId;
    /** Its own drive, with the drive bays on its network mounted under /net (design doc §17.6). */
    private final qbotics.engine.MountedFileSystem drive;
    private final Terminal terminal = new Terminal();
    private final BlockingQueue<Job> jobs = new LinkedBlockingQueue<>(MAX_QUEUED_JOBS);
    private final Semaphore cpu;
    private final Listener listener;
    /** Sends a request to the game world; it is answered on the server thread (design doc §2.2). */
    private final Function<String, CompletableFuture<String>> worldCalls;
    private final Thread thread;

    private volatile Computer vm;
    private volatile boolean running;
    private volatile boolean shutDown;
    /** True while a program is blocked in input(), waiting for a line. */
    private volatile boolean waitingForInput;
    /** The shell's current folder. */
    private String cwd = "/";
    /** True once the prompt for the next job is on screen, so it isn't printed twice. */
    private boolean promptShown;

    ServerComputer(UUID deviceId, UUID driveId, Path driveFolder, Semaphore cpu, Listener listener,
            Function<String, CompletableFuture<String>> worldCalls) {
        this.worldCalls = worldCalls;
        this.deviceId = deviceId;
        this.driveId = driveId;
        this.drive = new qbotics.engine.MountedFileSystem(new DriveFileSystem(driveFolder, DRIVE_QUOTA_BYTES));
        this.cpu = cpu;
        this.listener = listener;
        this.thread = Computer.newThread(this::run, "qbotics-computer-" + deviceId.toString().substring(0, 8));
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public UUID deviceId() {
        return deviceId;
    }

    public UUID driveId() {
        return driveId;
    }

    public Terminal terminal() {
        return terminal;
    }

    /** The computer's files. Safe to use from any thread. */
    /** The drive bays' drives this computer sees under /net, by bay name then drive name (set by the server). */
    public void setMounts(java.util.Map<String, java.util.Map<String, HostFileSystem>> mounts) {
        drive.setMounts(mounts);
    }

    public HostFileSystem drive() {
        return drive;
    }

    // ---- requests from players (any thread) --------------------------------------

    /** A line the player typed and entered. Dropped if the computer is too far behind. */
    public void submitLine(String line) {
        jobs.offer(new Line(line));
    }

    /** The editor's Run button: run a Python file, as if "run file" had been typed. */
    public void submitRunFile(String path) {
        jobs.offer(new RunFile(path));
    }

    /** The scratchpad's Run button. The result goes back to that player only. */
    public void submitScratch(UUID player, int runId, String code) {
        if (!jobs.offer(new Scratch(player, runId, code))) {
            listener.scratchResult(deviceId, player, runId, false, "The computer is too busy. Try again in a moment.\n");
        }
    }

    /** Ctrl+C: stop the running program, or cancel what is being typed. */
    public void interrupt() {
        Computer current = vm;
        // Decided together with readInputLine(), so a Ctrl+C that arrives just as a program
        // starts waiting in input() is neither lost nor counted twice.
        synchronized (inputLock) {
            if (running && current != null && !waitingForInput) {
                current.stop = Computer.StopMode.POLITE;
            } else {
                // At a prompt, or a program waiting in input(): the waiting side is told directly.
                jobs.offer(new Interrupt());
            }
        }
    }

    private final Object inputLock = new Object();

    /** The block was removed or the server is stopping. The thread ends and the instance is dropped. */
    void shutDown() {
        shutDown = true;
        Computer current = vm;
        if (current != null) {
            current.stop = Computer.StopMode.HARD;
        }
        thread.interrupt();
    }

    // ---- messages from other computers (design doc §9.3) ----------------------------

    /** What a program submits to wait for its next message. Must match network.py in the ROM. */
    private static final String RECEIVE_CALL = "[\"recv\"]";
    private static final int MAX_INBOX = 64;

    /** Messages nobody has asked for yet, each already in the form the program reads. */
    private final ArrayDeque<String> inbox = new ArrayDeque<>();
    /** Set while a program is waiting and the inbox is empty; the next message completes it. */
    private CompletableFuture<String> waitingReceive;

    /**
     * Hands this computer a message. Returns false if its inbox is full: a computer that
     * never reads its messages can't be made to hold an unlimited number.
     *
     * @param data the message as JSON text, passed on untouched
     */
    public boolean deliver(String sender, String data) {
        JsonObject message = new JsonObject();
        message.addProperty("sender", sender);
        message.addProperty("data", data);
        if (messagesAsEvents) {
            // The program handles messages as events (events.on_message): they join the event queue.
            message.addProperty("type", "message");
            return pushEvent(eventSession, message);
        }
        JsonObject reply = new JsonObject();
        reply.add("r", message);
        synchronized (inbox) {
            if (waitingReceive != null) {
                waitingReceive.complete(reply.toString());
                waitingReceive = null;
                return true;
            }
            if (inbox.size() >= MAX_INBOX) {
                return false;
            }
            inbox.add(reply.toString());
            return true;
        }
    }

    /** Answers with the oldest waiting message, now or when one arrives. */
    private CompletableFuture<String> receive() {
        synchronized (inbox) {
            String ready = inbox.poll();
            if (ready != null) {
                return CompletableFuture.completedFuture(ready);
            }
            if (waitingReceive == null) {
                waitingReceive = new CompletableFuture<>();
            }
            return waitingReceive;
        }
    }

    // ---- events (design doc §4: events over polling) ----------------------------------

    /** What a program submits to wait for its next event. Must match events.py in the ROM. */
    private static final String EVENT_CALL = "[\"event\"]";
    private static final int MAX_EVENTS = 256;

    /** Events nobody has taken yet, each already in the form the program reads. */
    private final ArrayDeque<String> events = new ArrayDeque<>();
    /** Set while a program is waiting and there is no event yet; the next one completes it. */
    private CompletableFuture<String> waitingEvent;
    /**
     * Counts the times a program has started listening. Whatever signed up to send events
     * (a Logic Pin, say) remembers the number it signed up under, and is ignored once it
     * changes: a program that has ended gets nothing more.
     */
    private volatile int eventSession;
    /** True while a program handles messages as events, so they go to the event queue instead of the inbox. */
    private volatile boolean messagesAsEvents;

    /** The number events are sent under for the program listening now. */
    public int eventSession() {
        return eventSession;
    }

    /**
     * A program starts listening for events afresh: anything left from before is dropped,
     * and earlier sign-ups no longer count. Returns the new session number.
     *
     * @param messages true if messages should arrive as events rather than wait in the inbox
     */
    public int startEvents(boolean messages) {
        synchronized (events) {
            events.clear();
            waitingEvent = null;
            messagesAsEvents = messages;
            return ++eventSession;
        }
    }

    /** The program has stopped listening (it ended): messages go back to the inbox, and nothing more is queued. */
    void stopEvents() {
        synchronized (events) {
            events.clear();
            waitingEvent = null;
            messagesAsEvents = false;
            eventSession++;
        }
    }

    /**
     * Hands this computer an event. Returns false if it was sent under an old session (the
     * program that asked for it has ended) or the queue is full, so the sender can forget it.
     */
    public boolean pushEvent(int session, JsonObject event) {
        JsonObject reply = new JsonObject();
        reply.add("r", event);
        synchronized (events) {
            if (session != eventSession) {
                return false;
            }
            if (waitingEvent != null) {
                waitingEvent.complete(reply.toString());
                waitingEvent = null;
                return true;
            }
            if (events.size() >= MAX_EVENTS) {
                return false;
            }
            events.add(reply.toString());
            return true;
        }
    }

    /** Answers with the oldest waiting event, now or when one arrives. */
    private CompletableFuture<String> nextEvent() {
        synchronized (events) {
            String ready = events.poll();
            if (ready != null) {
                return CompletableFuture.completedFuture(ready);
            }
            if (waitingEvent == null) {
                waitingEvent = new CompletableFuture<>();
            }
            return waitingEvent;
        }
    }

    // ---- the computer's thread ----------------------------------------------------

    private void run() {
        try {
            terminal.write("Qbotics computer starting...\n");
            Computer computer = new Computer(ComputerManager.engine(), HEAP_BYTES,
                    call -> call.equals(RECEIVE_CALL) ? receive() : call.equals(EVENT_CALL) ? nextEvent() : worldCalls.apply(call), drive);
            computer.cpu = cpu;
            computer.outputSink = terminal::write;
            computer.lineReader = this::readInputLine;
            vm = computer;
            terminal.clear();
            terminal.write("Qbotics computer. Type help for a list of commands.\n");
            if (exists(STARTUP_FILE)) {
                runPython(computer, "execfile(" + pyString(STARTUP_FILE) + ")");
            }
            shell(computer);
        } catch (InterruptedException e) {
            // Shut down while waiting for input.
        } catch (Throwable e) {
            if (!shutDown) {
                Qbotics.LOGGER.error("Computer {} crashed", deviceId, e);
                terminal.write("\nThis computer crashed. Break it and place it again to restart it.\n");
            }
        } finally {
            vm = null;
        }
    }

    /**
     * Shows the prompt and waits for the next thing to do. Scratchpad runs are done here,
     * quietly, without disturbing what is on the terminal.
     */
    private Job nextJob(Computer computer, String prompt) throws InterruptedException {
        while (true) {
            if (!promptShown) {
                terminal.write(prompt);
                promptShown = true;
            }
            Job job = jobs.take();
            if (job instanceof Scratch scratch) {
                runScratch(computer, scratch);
                continue;
            }
            promptShown = false;
            return job;
        }
    }

    // ---- remote login (ssh) ------------------------------------------------------------------

    /** True while this computer sits at its shell prompt, waiting for a command. */
    private volatile boolean atShellPrompt;
    /** During an ssh session from this computer: the computer it is logged in to. */
    private volatile ServerComputer sshRemote;
    /** Set when a session begins or ends, so players watching get the whole new screen. */
    private volatile boolean viewChanged;
    private volatile int sshSessions;

    /** The terminal a player looking at this computer should see. */
    Terminal shownTerminal() {
        ServerComputer remote = sshRemote;
        return remote == null ? terminal : remote.terminal;
    }

    boolean takeViewChanged() {
        boolean changed = viewChanged;
        viewChanged = false;
        return changed;
    }

    /**
     * A terminal state, marked so the player's game can tell this computer's own screen
     * from another computer's shown during a session, and one session from the next.
     */
    Terminal.State asShown(Terminal.State state) {
        return sshRemote == null ? state : new Terminal.State(state.epoch() + sshSessions * 1_000_003, state.scrolledStart(),
                state.scrolled(), state.lines(), state.cursorX(), state.cursorY());
    }

    private String sshProblem = "";

    /** Asks the game something about the network, from this computer's own thread. Null (with sshProblem set) if it said no. */
    private JsonObject askWorld(Object... request) throws InterruptedException {
        com.google.gson.JsonArray call = new com.google.gson.JsonArray();
        for (Object part : request) {
            call.add(part == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(part.toString()));
        }
        try {
            JsonObject reply = com.google.gson.JsonParser.parseString(
                    worldCalls.apply(call.toString()).get(10, java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            if (reply.has("e")) {
                sshProblem = reply.get("e").getAsString();
                return null;
            }
            return reply.getAsJsonObject("r");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
            sshProblem = "The game didn't answer.";
            return null;
        }
    }

    /**
     * "ssh address": this computer's terminal becomes the other computer's, until "exit".
     * Whether the other computer can be reached, and whether it lets this one in, is decided
     * by the game (NetworkCalls): first the antennas, then the computer's own settings.
     */
    private void ssh(String address) throws InterruptedException {
        JsonObject opened = askWorld("ssh_open", address, null);
        if (opened != null && opened.has("need_password")) {
            terminal.write("Password: ");
            Job typed = jobs.take();
            terminal.write("\n"); // what was typed is not written out
            if (!(typed instanceof Line password)) {
                return;
            }
            opened = askWorld("ssh_open", address, password.text());
        }
        if (opened == null) {
            terminal.write(sshProblem + "\n");
            return;
        }
        UUID remoteId = UUID.fromString(opened.get("id").getAsString());
        String label = opened.get("label").getAsString();
        ServerComputer remote = listener.running(remoteId);
        if (remote == null || remote == this) {
            terminal.write("Could not connect to " + label + ".\n");
            return;
        }
        // If this login switched the other computer on, let it finish starting: it clears its screen as it does.
        for (int waited = 0; waited < 100 && remote.vm == null && !remote.shutDown; waited++) {
            Thread.sleep(50);
        }
        // Both ends see that someone has come in.
        remote.terminal.write("\n[ssh] login from " + opened.get("from").getAsString() + "\n");
        if (remote.atShellPrompt) {
            remote.submitLine(""); // brings its prompt back under the notice
        }
        sshSessions++;
        sshRemote = remote;
        viewChanged = true;
        String ending = "Connection to " + label + " closed.";
        try {
            while (!shutDown) {
                Job job = jobs.take();
                if (listener.running(remoteId) != remote) {
                    ending = "Connection to " + label + " lost: that computer is gone.";
                    break;
                }
                switch (job) {
                    case Line line -> {
                        String text = line.text().strip();
                        if ((text.equals("exit") || text.equals("logout")) && remote.atShellPrompt) {
                            return;
                        }
                        // The link is checked again each time: an antenna may have been retuned, moved or made private.
                        if (askWorld("ssh_reach", address, null) == null) {
                            ending = "Connection to " + label + " lost: " + sshProblem;
                            return;
                        }
                        remote.submitLine(line.text());
                    }
                    case Interrupt ignored -> remote.interrupt();
                    case Scratch scratch -> listener.scratchResult(deviceId, scratch.player(), scratch.runId(), false,
                            "The scratchpad runs on this computer, which is in an ssh session. Type exit in the terminal first.\n");
                    case RunFile ignored -> {
                        // The editor's files are this computer's; the session is the other one's. Nothing to run there.
                    }
                }
            }
        } finally {
            sshRemote = null;
            viewChanged = true;
            terminal.write(ending + "\n");
        }
    }

    private void shell(Computer computer) throws InterruptedException {
        while (!shutDown) {
            atShellPrompt = true;
            Job job;
            try {
                job = nextJob(computer, cwd + " $ ");
            } finally {
                atShellPrompt = false;
            }
            switch (job) {
                case Line line -> {
                    terminal.write(line.text() + "\n");
                    command(computer, line.text());
                }
                case RunFile file -> {
                    terminal.write("run " + file.path() + "\n");
                    runFile(computer, file.path());
                }
                case Interrupt ignored -> terminal.write("^C\n");
                case Scratch ignored -> throw new IllegalStateException();
            }
        }
    }

    /** The Python prompt, entered with the "python" command and left with exit(). */
    private void repl(Computer computer) throws InterruptedException {
        terminal.write("MicroPython. Type exit() to go back to the shell.\n");
        while (!shutDown) {
            Job job = nextJob(computer, ">>> ");
            if (job instanceof RunFile file) {
                terminal.write("\nrun " + file.path() + "\n");
                runFile(computer, file.path());
                continue;
            }
            if (!(job instanceof Line first)) {
                terminal.write("\nKeyboardInterrupt\n");
                continue;
            }
            terminal.write(first.text() + "\n");
            String entry = first.text();
            String trimmed = entry.strip();
            if (trimmed.equals("exit()") || trimmed.equals("exit") || trimmed.equals("quit()") || trimmed.equals("quit")) {
                return;
            }
            // Keep reading lines while the entry is incomplete, as the Python prompt does.
            boolean cancelled = false;
            while (computer.replNeedsMore(entry)) {
                Job more = nextJob(computer, "... ");
                if (!(more instanceof Line next)) {
                    terminal.write("\nKeyboardInterrupt\n");
                    cancelled = true;
                    break;
                }
                terminal.write(next.text() + "\n");
                entry = entry + "\n" + next.text();
            }
            if (!cancelled && !entry.isBlank()) {
                String source = entry;
                runGuarded(computer, () -> computer.execRepl(source));
            }
        }
    }

    /** Python's input(): the next line typed at the terminal, or null for Ctrl+C. */
    private String readInputLine() throws InterruptedException {
        boolean alreadyStopped;
        synchronized (inputLock) {
            waitingForInput = true;
            // Ctrl+C was pressed a moment before the program began to wait: it counts here.
            Computer current = vm;
            alreadyStopped = current != null && current.stop == Computer.StopMode.POLITE;
            if (alreadyStopped) {
                current.stop = Computer.StopMode.NONE;
            }
        }
        try {
            if (alreadyStopped) {
                terminal.write("\n");
                return null;
            }
            while (true) {
                switch (jobs.take()) {
                    case Line line -> {
                        terminal.write(line.text() + "\n");
                        return line.text();
                    }
                    case Interrupt ignored -> {
                        terminal.write("\n");
                        return null;
                    }
                    case Scratch scratch -> listener.scratchResult(deviceId, scratch.player(), scratch.runId(), false,
                            "The program in the terminal is waiting for you to type something there.\n");
                    case RunFile ignored -> terminal.write("\n(a program is already running and waiting for input)\n");
                }
            }
        } finally {
            waitingForInput = false;
        }
    }

    private void runGuarded(Computer computer, Runnable body) {
        running = true;
        try {
            body.run();
        } finally {
            running = false;
            computer.stop = shutDown ? Computer.StopMode.HARD : Computer.StopMode.NONE;
            stopEvents(); // a program that has ended listens for nothing
        }
    }

    private void runPython(Computer computer, String source) {
        runGuarded(computer, () -> computer.exec(source));
    }

    private void runFile(Computer computer, String path) {
        String file = Paths.resolve(cwd, path);
        try {
            String kind = new String(drive.call(HostFileSystem.STAT, file, new byte[0]), StandardCharsets.UTF_8);
            if (kind.startsWith("d")) {
                terminal.write(file + " is a folder, not a program.\n");
                return;
            }
        } catch (FsException e) {
            terminal.write(describe(e, file) + "\n");
            return;
        }
        // Programs see the shell's folder as their current folder, and start with no event
        // handlers left over from an earlier program (the events module is loaded afresh).
        runPython(computer, "import os, sys\nos.chdir(" + pyString(cwd) + ")\nsys.modules.pop('events', None)\nexecfile(" + pyString(file) + ")");
    }

    private void runScratch(Computer computer, Scratch scratch) {
        StringBuilder output = new StringBuilder();
        boolean[] ok = {false};
        computer.lineReader = null; // the scratchpad has no terminal to ask
        computer.outputSink = text -> {
            if (output.length() < MAX_SCRATCH_OUTPUT) {
                output.append(text, 0, Math.min(text.length(), MAX_SCRATCH_OUTPUT - output.length()));
            }
        };
        try {
            runGuarded(computer, () -> ok[0] = computer.exec(scratch.code()));
        } finally {
            computer.outputSink = terminal::write;
            computer.lineReader = this::readInputLine;
        }
        listener.scratchResult(deviceId, scratch.player(), scratch.runId(), ok[0], output.toString());
    }

    // ---- shell commands ------------------------------------------------------------

    private static final String HELP = String.join("\n",
            "Files:   ls [folder]   cd <folder>   pwd   cat <file>",
            "         mkdir <folder>   rm [-r] <path>   mv <from> <to>   cp <from> <to>",
            "Python:  run <file.py>      run a program (python <file.py> also works)",
            "         python             the Python prompt; exit() comes back here",
            "Editor:  edit <file>        open a file in the editor",
            "         settings           this computer's name, remote login and antenna",
            "Network: devices            list the blocks cabled to this computer",
            "         devices <address>  list the blocks behind another antenna (100.64.0.12)",
            "         ssh <address>      use another computer's terminal from here; exit returns",
            "Other:   clear   help       Ctrl+C stops a running program",
            "         shutdown           switch this computer off; it stays off until opened again",
            "");

    /** The shell's commands, longest first, for spotting one typed without its space. */
    private static final List<String> COMMANDS = List.of("settings", "shutdown", "devices", "ifconfig", "python", "mkdir", "clear", "help",
            "edit", "pwd", "cat", "run", "ssh", "ls", "cd", "rm", "mv", "cp", "py");

    /**
     * A hint for a line that isn't a command: the space a command lost ("run9_world.py" is
     * "run 9_world.py"), or a program's name typed on its own. Otherwise, where to look.
     */
    private String suggestion(String typed) {
        for (String command : COMMANDS) {
            if (typed.length() > command.length() && typed.startsWith(command)) {
                return "Did you mean: " + command + " " + typed.substring(command.length()) + " ?";
            }
        }
        if (typed.endsWith(".py")) {
            return "To run a program, type: run " + typed;
        }
        return "Type help for a list.";
    }

    private void command(Computer computer, String line) throws InterruptedException {
        List<String> args = splitArguments(line);
        if (args.isEmpty()) {
            return;
        }
        String name = args.removeFirst();
        try {
            switch (name) {
                case "help" -> terminal.write(HELP);
                case "clear" -> terminal.clear();
                case "pwd" -> terminal.write(cwd + "\n");
                case "ls" -> list(args.isEmpty() ? cwd : Paths.resolve(cwd, args.getFirst()));
                case "cd" -> changeFolder(args.isEmpty() ? "/" : Paths.resolve(cwd, args.getFirst()));
                case "cat" -> {
                    byte[] data = drive.call(HostFileSystem.READ, path(args, 0, name), new byte[0]);
                    String text = new String(data, 0, Math.min(data.length, MAX_CAT_BYTES), StandardCharsets.UTF_8);
                    terminal.write(text.endsWith("\n") || text.isEmpty() ? text : text + "\n");
                    if (data.length > MAX_CAT_BYTES) {
                        terminal.write("... (" + (data.length - MAX_CAT_BYTES) + " more bytes)\n");
                    }
                }
                case "mkdir" -> drive.call(HostFileSystem.MKDIR, path(args, 0, name), new byte[0]);
                case "rm" -> {
                    boolean recursive = args.remove("-r") | args.remove("-rf");
                    String target = path(args, 0, name);
                    if (recursive) {
                        deleteTree(drive, target);
                    } else {
                        drive.call(HostFileSystem.REMOVE, target, new byte[0]);
                    }
                }
                case "mv" -> drive.call(HostFileSystem.RENAME, path(args, 0, name),
                        intoFolder(path(args, 1, name), args.get(0)).getBytes(StandardCharsets.UTF_8));
                case "cp" -> drive.call(HostFileSystem.WRITE, intoFolder(path(args, 1, name), args.get(0)),
                        drive.call(HostFileSystem.READ, path(args, 0, name), new byte[0]));
                case "edit" -> listener.openInEditor(deviceId, path(args, 0, name));
                case "settings" -> listener.openInEditor(deviceId, ComputerSettings.PATH);
                case "shutdown" -> {
                    terminal.write("Shutting down. Open this computer again to switch it on.\n");
                    listener.shutDownRequested(deviceId);
                }
                case "run" -> runFile(computer, path(args, 0, name));
                case "ssh" -> {
                    if (args.isEmpty()) {
                        throw new UsageException("Usage: ssh <name or address>   (for example: ssh smelter, or ssh 100.64.0.12:1005)");
                    }
                    ssh(args.getFirst());
                }
                case "devices", "ifconfig" -> runPython(computer,
                        "import logistics\nlogistics._print_devices(" + (args.isEmpty() ? "" : pyString(args.getFirst())) + ")");
                case "python", "py" -> {
                    if (args.isEmpty()) {
                        repl(computer);
                    } else {
                        runFile(computer, args.getFirst());
                    }
                }
                default -> terminal.write("Unknown command: " + name + ". " + suggestion(name) + "\n");
            }
        } catch (FsException e) {
            terminal.write(describe(e, args.isEmpty() ? "" : args.getFirst()) + "\n");
        } catch (UsageException e) {
            terminal.write(e.getMessage() + "\n");
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message, null, false, false);
        }
    }

    /** The absolute path given as argument number index, or a usage message if it is missing. */
    private String path(List<String> args, int index, String command) {
        if (args.size() <= index) {
            throw new UsageException("Usage: " + switch (command) {
                case "mv", "cp" -> command + " <from> <to>";
                case "mkdir" -> "mkdir <folder>";
                case "rm" -> "rm [-r] <path>";
                default -> command + " <file>";
            });
        }
        return Paths.resolve(cwd, args.get(index));
    }

    /** "mv a.py lib" means "into lib" when lib is a folder. */
    private String intoFolder(String target, String sourceArgument) {
        return isFolder(target) ? Paths.join(target, Paths.name(Paths.resolve(cwd, sourceArgument))) : target;
    }

    private boolean exists(String path) {
        try {
            drive.call(HostFileSystem.STAT, path, new byte[0]);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    private boolean isFolder(String path) {
        try {
            return new String(drive.call(HostFileSystem.STAT, path, new byte[0]), StandardCharsets.UTF_8).startsWith("d");
        } catch (FsException e) {
            return false;
        }
    }

    private void changeFolder(String folder) throws FsException {
        if (!isFolder(folder)) {
            throw new FsException(exists(folder) ? HostFileSystem.ENOTDIR : HostFileSystem.ENOENT);
        }
        cwd = folder;
    }

    private void list(String folder) throws FsException {
        String listing = new String(drive.call(HostFileSystem.LIST, folder, new byte[0]), StandardCharsets.UTF_8);
        if (listing.isEmpty()) {
            terminal.write("(empty)\n");
            return;
        }
        StringBuilder folders = new StringBuilder();
        StringBuilder files = new StringBuilder();
        for (String entry : listing.split("\n")) {
            String[] fields = entry.split("\t");
            if (fields[0].equals("d")) {
                folders.append(fields[2]).append("/\n");
            } else {
                files.append(String.format("%-30s %7s bytes%n", fields[2], fields[1]).replace("\r", ""));
            }
        }
        terminal.write(folders.toString() + files);
    }

    /** Deletes a file, or a folder with everything in it. */
    public static void deleteTree(HostFileSystem drive, String path) throws FsException {
        String kind = new String(drive.call(HostFileSystem.STAT, path, new byte[0]), StandardCharsets.UTF_8);
        if (kind.startsWith("d")) {
            String listing = new String(drive.call(HostFileSystem.LIST, path, new byte[0]), StandardCharsets.UTF_8);
            for (String entry : listing.split("\n")) {
                if (!entry.isEmpty()) {
                    deleteTree(drive, Paths.join(path, entry.split("\t")[2]));
                }
            }
        }
        drive.call(HostFileSystem.REMOVE, path, new byte[0]);
    }

    /** A file error in plain words. */
    public static String describe(FsException e, String what) {
        String subject = what.isEmpty() ? "" : what + ": ";
        return subject + switch (e.errno) {
            case HostFileSystem.ENOENT -> "no such file or folder";
            case HostFileSystem.EEXIST -> "that name is already taken";
            case HostFileSystem.ENOTDIR -> "not a folder";
            case HostFileSystem.EISDIR -> "that is a folder";
            case HostFileSystem.EINVAL -> "that name isn't allowed";
            case HostFileSystem.ENOSPC -> "the drive is full";
            case HostFileSystem.ENOTEMPTY -> "the folder isn't empty (use rm -r to remove it and everything in it)";
            case HostFileSystem.EACCES -> "not allowed";
            case HostFileSystem.EFBIG -> "the file is too large";
            default -> "failed (error " + e.errno + ")";
        };
    }

    /** Splits a command line on spaces; "double quotes" keep spaces inside one argument. */
    static List<String> splitArguments(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == ' ' && !quoted) {
                if (any) {
                    args.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            args.add(current.toString());
        }
        return args;
    }

    /** A Python string literal holding this text. */
    private static String pyString(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
