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
    /** A Computer's (and Qbot's) memory, the basic tier (design doc §3.4). Laptops and Servers have more. */
    public static final int BASIC_HEAP_BYTES = 256 * 1024;
    /** Design doc §15: files.drive_quota_kb. Will come from config. */
    /** How much a computer's own drive holds. */
    public static final long DRIVE_QUOTA_BYTES = 1024 * 1024;
    private static final int MAX_QUEUED_JOBS = 256;
    private static final int MAX_CAT_BYTES = 16 * 1024;
    public static final int MAX_SCRATCH_OUTPUT = 8000;
    private static final String STARTUP_FILE = "/startup.py";
    /** A shell script run when the computer starts, before startup.py. */
    private static final String STARTUP_SCRIPT = "/startup.sh";

    // ---- a Qbot's program: Run, Pause and Stop (design doc §8) ------------------------------

    /** What a Qbot's program is doing, for its screen. */
    public enum ProgramState { STOPPED, RUNNING, PAUSED }

    private final boolean qbot;
    private final boolean runOnBoot;
    /** True from Run until the program ends. */
    private volatile boolean programActive;
    /** Set when Stop ended it, so it is told apart from an error. */
    private volatile boolean stoppedByButton;
    /** How it last ended, in a few words: "", "Finished", "Stopped", "Error: ...", or why it couldn't start. */
    private volatile String programStatus = "";

    public ProgramState programState() {
        Computer current = vm;
        return !programActive ? ProgramState.STOPPED : current != null && current.paused ? ProgramState.PAUSED : ProgramState.RUNNING;
    }

    public String programStatus() {
        return programStatus;
    }

    /** Run (or, while paused, Resume). Does nothing if a program is already running. */
    public void runProgram() {
        Computer current = vm;
        if (programActive) {
            if (current != null) {
                current.paused = false;
            }
            return;
        }
        if (running) {
            programStatus = "Busy: something else is running on it.";
            return;
        }
        programActive = true; // at once, so the screen shows it; the job sets the rest
        if (!jobs.offer(new RunFile(STARTUP_FILE, true))) {
            programActive = false;
        }
    }

    /**
     * Ctrl+Z: pauses what is running, where it is, or lets a paused program carry on. During
     * an ssh session it goes to the computer logged in to, as Ctrl+C does.
     */
    public void togglePause() {
        ServerComputer remote = sshRemote;
        if (remote != null) {
            remote.togglePause();
            return;
        }
        Computer current = vm;
        if (!running || current == null) {
            return; // nothing running: nothing to pause
        }
        if (current.paused) {
            current.stepping = false;
            current.paused = false;
            terminal.write("[carrying on]\n");
        } else {
            current.paused = true; // it says where, as it stops (see newVm)
        }
    }

    /**
     * F8: runs the program to the next line of the player's own code and holds it there.
     * Pressed while it runs, it stops at the next line. Ctrl+Z then carries on as normal.
     */
    public void step() {
        ServerComputer remote = sshRemote;
        if (remote != null) {
            remote.step();
            return;
        }
        Computer current = vm;
        if (!running || current == null) {
            return;
        }
        current.stepping = true;
        current.paused = false;
    }

    /** Someone has this computer's editor open: its program notes which line it is on. */
    private volatile boolean watched;

    public void setWatched(boolean watch) {
        watched = watch;
        Computer current = vm;
        if (current != null) {
            current.watched = watch;
        }
    }

    /** Where its program is, for the editor: a line of the player's code (or none), and whether it is paused there. */
    public record Trace(String file, int line, boolean paused) {
    }

    public Trace trace() {
        Computer current = vm;
        if (!running || current == null) {
            return new Trace("", 0, false);
        }
        Computer.Location at = current.location;
        return new Trace(at == null ? "" : at.file(), at == null ? 0 : at.line(), current.paused);
    }

    /** Pause: the program waits where it is until Run (Resume) or Stop. */
    public void pauseProgram() {
        Computer current = vm;
        if (programActive && current != null) {
            current.paused = true;
        }
    }

    /** Stop: ends the program at once. Its Python is restarted; files are untouched. */
    public void stopProgram() {
        Computer current = vm;
        if (programActive && current != null) {
            stoppedByButton = true;
            current.paused = false;
            current.stop = Computer.StopMode.HARD;
        }
    }

    /** Runs a Qbot's program, startup.py, keeping its state and how it ended. On the computer's thread. */
    private void runProgramNow(Computer computer) throws InterruptedException {
        programActive = true;
        stoppedByButton = false;
        if (!exists(STARTUP_FILE)) {
            programActive = false;
            programStatus = "No program: install one with a Programmer.";
            return;
        }
        listener.programRunning(deviceId, true);
        programStatus = "";
        boolean ended = false;
        boolean ok = false;
        try {
            ok = runFile(computer, STARTUP_FILE);
            ended = true;
        } finally {
            programActive = false;
            computer.paused = false;
            programStatus = !ended || stoppedByButton ? "Stopped" : ok ? "Finished" : "Error: " + lastLine();
            if (!shutDown) {
                listener.programRunning(deviceId, false);
            }
        }
    }

    /** The last line with anything on it in the terminal: what an error said. */
    private String lastLine() {
        java.util.List<String> lines = terminal.full().lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !line.endsWith("$")) {
                return line;
            }
        }
        return "";
    }

    /** Things a computer tells the players looking at it. Called on the computer's thread. */
    public interface Listener {
        void scratchResult(UUID deviceId, UUID player, int runId, boolean ok, String output);

        void openInEditor(UUID deviceId, String path);

        /** Another computer that is switched on, or null. Used by ssh. */
        ServerComputer running(UUID deviceId);

        /** This computer's program typed shutdown: switch it off until someone opens it again. */
        void shutDownRequested(UUID deviceId);

        /** A Qbot's program started or ended, so it can be started again after a world reload. */
        default void programRunning(UUID deviceId, boolean running) {
        }
    }

    private sealed interface Job {
    }

    private record Line(String text) implements Job {
    }

    /** A file to run; program marks a Qbot's Run button (its startup.py, with its state kept). */
    private record RunFile(String path, boolean program) implements Job {
        RunFile(String path) {
            this(path, false);
        }

    }

    /**
     * A scratchpad run: the editor's (its result to that player) or the AI helper's (its
     * result to done, look-only unless it may change things, stopped after timeoutSeconds).
     */
    private record Scratch(UUID player, int runId, String code, boolean lookOnly, @org.jspecify.annotations.Nullable CompletableFuture<ExecResult> done,
            int timeoutSeconds) implements Job {
        Scratch(UUID player, int runId, String code) {
            this(player, runId, code, false, null, 0);
        }
    }

    /** Where a scratchpad run's outcome goes: the editor's player, or the AI helper. */
    private void scratchAnswer(Scratch scratch, boolean ok, String output) {
        if (scratch.done() != null) {
            scratch.done().complete(new ExecResult(ok ? 0 : 1, output));
        } else {
            listener.scratchResult(deviceId, scratch.player(), scratch.runId(), ok, output);
        }
    }

    private record Interrupt() implements Job {
    }

    /** A command from outside the terminal (ssh "command", the AI agent), whose output and outcome are wanted. */
    private record Exec(String line, int timeoutSeconds, CompletableFuture<ExecResult> done) implements Job {
    }

    /** What a command run with {@link #exec} printed, and its status: 0 if it worked. */
    public record ExecResult(int status, String output) {
    }

    private static final int MAX_EXEC_OUTPUT = 64 * 1024;
    /** Commands that need someone at the keyboard, so can't be run from outside. */
    private static final java.util.Set<String> INTERACTIVE = java.util.Set.of("ssh", "edit", "settings", "clear");
    private static final java.util.concurrent.ScheduledExecutorService EXEC_TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("qbotics-exec-timer").factory());

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
        this(deviceId, driveId, driveFolder, cpu, listener, worldCalls, false, false);
    }

    ServerComputer(UUID deviceId, UUID driveId, Path driveFolder, Semaphore cpu, Listener listener,
            Function<String, CompletableFuture<String>> worldCalls, boolean qbot, boolean runOnBoot) {
        this(deviceId, driveId, driveFolder, cpu, listener, worldCalls, qbot, runOnBoot, BASIC_HEAP_BYTES, DRIVE_QUOTA_BYTES);
    }

    /**
     * @param qbot a Qbot: its program is started by its Run button (or a Programmer), not on
     *             its own when it boots, unless {@code runOnBoot}
     * @param runOnBoot for a Qbot, start its program as it boots: it was running when the world
     *             was saved, or a Programmer has just installed one
     */
    ServerComputer(UUID deviceId, UUID driveId, Path driveFolder, Semaphore cpu, Listener listener,
            Function<String, CompletableFuture<String>> worldCalls, boolean qbot, boolean runOnBoot, int heapBytes, long quotaBytes) {
        this(deviceId, driveId, new qbotics.engine.MountedFileSystem(new DriveFileSystem(driveFolder, quotaBytes)), cpu, listener, worldCalls,
                qbot, runOnBoot, heapBytes, quotaBytes, null, null);
    }

    /**
     * A Server's service (see {@link Services}): a program of its own, run once in the
     * background in its own Python, on the Server's drive, with no shell.
     */
    static ServerComputer service(UUID id, ServerComputer server, Semaphore cpu, Listener listener,
            Function<String, CompletableFuture<String>> worldCalls, String name, String file) {
        return new ServerComputer(id, server.driveId, server.drive, cpu, listener, worldCalls, false, false, Services.HEAP_BYTES,
                server.quotaBytes, name, file);
    }

    private ServerComputer(UUID deviceId, UUID driveId, qbotics.engine.MountedFileSystem drive, Semaphore cpu, Listener listener,
            Function<String, CompletableFuture<String>> worldCalls, boolean qbot, boolean runOnBoot, int heapBytes, long quotaBytes,
            String serviceName, String serviceFile) {
        this.heapBytes = heapBytes;
        this.quotaBytes = quotaBytes;
        this.qbot = qbot;
        this.runOnBoot = runOnBoot;
        this.worldCalls = worldCalls;
        this.deviceId = deviceId;
        this.driveId = driveId;
        this.drive = drive;
        this.serviceName = serviceName;
        this.serviceFile = serviceFile;
        this.cpu = cpu;
        this.listener = listener;
        this.thread = Computer.newThread(this::run, "qbotics-computer-" + deviceId.toString().substring(0, 8));
        this.thread.setDaemon(true);
        this.thread.start();
    }

    // ---- services (a Server's background programs) ---------------------------------------

    /** For a service: its name and the program it runs. Null for a computer. */
    private final String serviceName;
    private final String serviceFile;

    /** What a service is doing, as service list shows it. */
    public enum ServiceState {
        STARTING("starting"), RUNNING("running"), DONE("done"), FAILED("failed"), STOPPED("stopped");

        public final String word;

        ServiceState(String word) {
            this.word = word;
        }
    }

    private volatile ServiceState serviceState = ServiceState.STARTING;

    public ServiceState serviceState() {
        return serviceState;
    }

    public boolean isService() {
        return serviceFile != null;
    }

    /** A Server's services; null for any other computer. Set as the Server starts. */
    volatile Services services;

    public Services services() {
        return services;
    }

    /** A service's whole life: run its program once, and say how it ended in its log. */
    private void runService() {
        String started = java.time.LocalTime.now().withNano(0).toString();
        terminal.write("[" + started + "] " + serviceName + ": running " + serviceFile + "\n");
        Computer computer = newVm();
        computer.lineReader = null; // nobody types to a service: input() gets nothing
        serviceState = ServiceState.RUNNING;
        boolean ok;
        try {
            String folder = serviceFile.substring(0, Math.max(1, serviceFile.lastIndexOf('/')));
            ok = runPython(computer, startProgram(folder, serviceFile) + "execfile(" + pyString(serviceFile) + ")");
        } catch (Restart restart) {
            ok = false;
        }
        String ended = java.time.LocalTime.now().withNano(0).toString();
        if (shutDown) {
            serviceState = ServiceState.STOPPED;
            terminal.write("[" + ended + "] stopped\n");
        } else {
            serviceState = ok ? ServiceState.DONE : ServiceState.FAILED;
            terminal.write("[" + ended + "] " + (ok ? "finished" : "failed") + "\n");
        }
    }

    /** True if its program waits for messages (network.receive, or events.on_message). */
    public boolean listensForMessages() {
        synchronized (inbox) {
            return messagesAsEvents || waitingReceive != null || receivedBefore;
        }
    }

    /** True once its program has asked for a message. */
    private boolean receivedBefore;

    /** How much memory its programs have. */
    private final int heapBytes;
    /** How much its own drive holds. */
    private final long quotaBytes;

    public int heapBytes() {
        return heapBytes;
    }

    public long quotaBytes() {
        return quotaBytes;
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

    /** Goes up whenever the computer's files may have changed (see MountedFileSystem.changes). */
    public long fileChanges() {
        return drive.changes();
    }

    // ---- requests from players (any thread) --------------------------------------

    /** A line the player typed and entered. Dropped if the computer is too far behind. */
    public void submitLine(String line) {
        jobs.offer(new Line(line));
    }

    /**
     * Runs one shell command as if typed at the prompt (it shows in the terminal), for ssh
     * "command" and the AI agent, and answers with what it printed and whether it worked. A
     * program still going after timeoutSeconds is stopped, as Ctrl+C would. If the computer
     * isn't at its prompt (a program is running, or it is in an ssh session) it says so at once.
     */
    public CompletableFuture<ExecResult> exec(String line, int timeoutSeconds) {
        CompletableFuture<ExecResult> done = new CompletableFuture<>();
        List<String> words = splitArgumentsOrEmpty(line);
        if (!words.isEmpty() && (INTERACTIVE.contains(words.getFirst())
                || ((words.getFirst().equals("python") || words.getFirst().equals("py")) && words.size() == 1))) {
            done.complete(new ExecResult(2, words.getFirst() + " needs someone at the computer's keyboard, so it can't be run this way.\n"));
        } else if (!atShellPrompt || sshRemote != null || !jobs.offer(new Exec(line, Math.max(1, timeoutSeconds), done))) {
            done.complete(busy());
        }
        return done;
    }

    private static ExecResult busy() {
        return new ExecResult(3, "The computer is busy: a program is running, or it is in an ssh session or waiting for input. "
                + "Stop it first (Ctrl+C in its terminal).\n");
    }

    private static List<String> splitArgumentsOrEmpty(String line) {
        try {
            return splitArguments(line);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** At the prompt: a command from outside, its output collected as it is written. */
    private void runExec(Computer computer, Exec exec) throws InterruptedException {
        StringBuilder out = new StringBuilder();
        java.util.function.Consumer<String> collect = text -> {
            synchronized (out) {
                if (out.length() < MAX_EXEC_OUTPUT) {
                    out.append(text, 0, Math.min(text.length(), MAX_EXEC_OUTPUT - out.length()));
                }
            }
        };
        terminal.write(exec.line() + "\n");
        terminal.addListener(collect);
        java.util.concurrent.ScheduledFuture<?> stopper = EXEC_TIMER.schedule(() -> {
            collect.accept("\n[stopped after " + exec.timeoutSeconds() + " seconds]\n");
            interrupt();
        }, exec.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        boolean ok = false;
        try {
            ok = command(computer, exec.line());
        } finally {
            stopper.cancel(false);
            terminal.removeListener(collect);
            String output;
            synchronized (out) {
                output = out.toString();
            }
            exec.done().complete(new ExecResult(ok ? 0 : 1, output));
        }
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

    /**
     * The AI helper's scratchpad: runs Python on this computer without touching its files and
     * answers with what it printed. Look-only, it can only read (LookOnly); either way it is
     * stopped after timeoutSeconds. A computer running a program answers "busy" at once.
     */
    public CompletableFuture<ExecResult> scratchForAgent(String code, boolean lookOnly, int timeoutSeconds) {
        CompletableFuture<ExecResult> done = new CompletableFuture<>();
        if (!atShellPrompt || sshRemote != null || !jobs.offer(new Scratch(new UUID(0, 0), 0, code, lookOnly, done, Math.max(1, timeoutSeconds)))) {
            done.complete(busy());
        }
        return done;
    }

    /** Ctrl+C: stop the running program, or cancel what is being typed. */
    public void interrupt() {
        Computer current = vm;
        // Decided together with readInputLine(), so a Ctrl+C that arrives just as a program
        // starts waiting in input() is neither lost nor counted twice.
        synchronized (inputLock) {
            if (running && current != null && !waitingForInput) {
                // The first Ctrl+C raises KeyboardInterrupt. If the program caught that and
                // carried on, the next one stops it for certain (and restarts its Python).
                boolean ignored = current.stop == Computer.StopMode.POLITE || current.stopsDelivered > 0;
                current.stop = ignored ? Computer.StopMode.HARD : Computer.StopMode.POLITE;
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
            receivedBefore = true;
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
        if (serviceFile != null) {
            try {
                runService();
            } catch (Throwable e) {
                if (!shutDown) {
                    Qbotics.LOGGER.error("Service {} crashed", serviceName, e);
                    serviceState = ServiceState.FAILED;
                }
            } finally {
                vm = null;
            }
            return;
        }
        try {
            terminal.write("Qbotics computer starting...\n");
            boolean first = true;
            while (true) {
                Computer computer = newVm();
                try {
                    if (first) {
                        terminal.clear();
                        if (!qbot) {
                            terminal.write("Qbotics computer. Type help for a list of commands.\n"
                                    + (exists("/helpme") ? "" : "New here? Type  git clone helpme  for examples to learn from.\n"));
                        }
                        if (qbot) {
                            // A Qbot runs its program when told to (Run, a Programmer), or again after a reload.
                            if (runOnBoot) {
                                runProgramNow(computer);
                            }
                        } else {
                            if (exists(STARTUP_SCRIPT)) {
                                runScript(computer, STARTUP_SCRIPT);
                            }
                            if (exists(STARTUP_FILE)) {
                                runPython(computer, startProgram("/", STARTUP_FILE) + "execfile(" + pyString(STARTUP_FILE) + ")");
                            }
                        }
                    }
                    shell(computer);
                    return;
                } catch (Restart restart) {
                    // A program that ignored Ctrl+C was stopped mid-step: its Python can't be
                    // trusted afterwards, so the computer starts a fresh one. Files are untouched.
                    terminal.write(stoppedByButton ? "\nStopped by its Stop button.\n" : "\nStopped. Python was restarted, since the program ignored Ctrl+C.\n");
                    first = false;
                }
            }
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
            if (job instanceof Exec exec && !atShellPrompt) {
                exec.done().complete(busy()); // at the Python prompt, say
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
            if (typed instanceof Exec exec) {
                exec.done().complete(busy());
            }
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
                    case Scratch scratch -> scratchAnswer(scratch, false,
                            "The scratchpad runs on this computer, which is in an ssh session. Type exit in the terminal first.\n");
                    case RunFile ignored -> {
                        // The editor's files are this computer's; the session is the other one's. Nothing to run there.
                    }
                    case Exec exec -> exec.done().complete(busy());
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
                case Exec exec -> runExec(computer, exec);
                case RunFile file -> {
                    terminal.write("run " + file.path() + "\n");
                    if (file.program()) {
                        runProgramNow(computer);
                    } else {
                        runFile(computer, file.path());
                    }
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
                    case Scratch scratch -> scratchAnswer(scratch, false,
                            "The program in the terminal is waiting for you to type something there.\n");
                    case RunFile ignored -> terminal.write("\n(a program is already running and waiting for input)\n");
                    case Exec exec -> exec.done().complete(busy());
                }
            }
        } finally {
            waitingForInput = false;
        }
    }

    /** Thrown out of the shell when a program had to be hard-stopped, so run() restarts Python. */
    private static final class Restart extends RuntimeException {
        Restart() {
            super(null, null, false, false);
        }
    }

    /** For a laptop: held while its owner is offline or carries no laptop (see Laptops). */
    private volatile boolean held;

    /** Holds or lets go of whatever this computer is running; kept for a Python started afresh, too. */
    public void setHeld(boolean hold) {
        held = hold;
        Computer current = vm;
        if (current != null) {
            current.held = hold;
        }
    }

    public boolean held() {
        return held;
    }

    private Computer newVm() {
        Computer computer = new Computer(ComputerManager.engine(), heapBytes,
                call -> call.equals(RECEIVE_CALL) ? receive() : call.equals(EVENT_CALL) ? nextEvent() : worldCalls.apply(call), drive);
        computer.cpu = cpu;
        computer.outputSink = terminal::write;
        computer.lineReader = this::readInputLine;
        computer.lastsAcrossRuns = RECEIVE_CALL::equals;
        computer.held = held;
        computer.watched = watched;
        // A pause says where it stopped (a step's pause is shown by the editor instead).
        computer.onPause = at -> terminal.write(at == null
                ? "\n[paused - F8 steps, Ctrl+Z carries on, Ctrl+C stops]\n"
                : "\n[paused at line " + at.line() + " of " + at.file() + " - F8 steps, Ctrl+Z carries on, Ctrl+C stops]\n");
        vm = computer;
        return computer;
    }

    private static boolean hardStopped(Throwable e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof Computer.HardStop) {
                return true;
            }
        }
        return false;
    }

    private void runGuarded(Computer computer, Runnable body) {
        running = true;
        computer.beginRun();
        try {
            body.run();
        } catch (RuntimeException e) {
            if (!shutDown && hardStopped(e)) {
                throw new Restart();
            }
            throw e;
        } finally {
            running = false;
            computer.forgetCalls();
            computer.stop = shutDown ? Computer.StopMode.HARD : Computer.StopMode.NONE;
            stopEvents(); // a program that has ended listens for nothing
        }
    }

    private boolean runPython(Computer computer, String source) {
        boolean[] ok = {false};
        runGuarded(computer, () -> ok[0] = computer.exec(source));
        return ok[0];
    }

    private boolean runFile(Computer computer, String path) throws InterruptedException {
        if (path.endsWith(".sh")) {
            return runScript(computer, path);
        }
        String file = Paths.resolve(cwd, path);
        try {
            String kind = new String(drive.call(HostFileSystem.STAT, file, new byte[0]), StandardCharsets.UTF_8);
            if (kind.startsWith("d")) {
                terminal.write(file + " is a folder, not a program.\n");
                return false;
            }
        } catch (FsException e) {
            terminal.write(describe(e, file) + "\n");
            return false;
        }
        // Programs see the shell's folder as their current folder, and start with no event
        // handlers left over from an earlier program (the events module is loaded afresh).
        return runPython(computer, startProgram(cwd, file) + "execfile(" + pyString(file) + ")");
    }

    /**
     * What runs before a program: its current folder, and a fresh start (rom/_boot.py's
     * _start): files it imports are read again, and import looks in the program's own folder.
     */
    private static String startProgram(String cwd, String file) {
        return "import os, _boot\nos.chdir(" + pyString(cwd) + ")\n_boot._start(" + pyString(Paths.parent(file)) + ")\n";
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
        java.util.concurrent.ScheduledFuture<?> stopper = scratch.timeoutSeconds() <= 0 ? null : EXEC_TIMER.schedule(() -> {
            synchronized (output) {
                output.append("\n[stopped after ").append(scratch.timeoutSeconds()).append(" seconds]\n");
            }
            interrupt();
        }, scratch.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        computer.lookOnly = scratch.lookOnly();
        try {
            runGuarded(computer, () -> ok[0] = computer.exec(scratch.code()));
        } catch (Restart restart) {
            scratchAnswer(scratch, false, output + "\nStopped: the program ignored Ctrl+C.\n");
            throw restart;
        } finally {
            computer.lookOnly = false;
            if (stopper != null) {
                stopper.cancel(false);
            }
            computer.outputSink = terminal::write;
            computer.lineReader = this::readInputLine;
        }
        scratchAnswer(scratch, ok[0], output.toString());
    }

    // ---- shell commands ------------------------------------------------------------

    private static final String HELP = String.join("\n",
            "Files:   ls [folder]   cd <folder>   pwd   cat <file>",
            "         mkdir [-p] <folder>   rm [-r] <path>   mv <from> <to>   cp <from> <to>",
            "Python:  run <file.py>      run a program (python <file.py> also works)",
            "Scripts: run <file.sh>      run a file of commands, one a line (sh <file> too); echo <text>; sleep <seconds>",
            "         startup.sh and startup.py, at the top of the drive, run when the computer starts",
            "         python             the Python prompt; exit() comes back here",
            "Editor:  edit <file>        open a file in the editor",
            "         settings           this computer's name, remote login and antenna",
            "Network: devices            list the blocks cabled to this computer",
            "         pip install -r requirements.txt   install libraries from a hard drive (pip help)",
            "         devices <address>  list the blocks behind another antenna (100.64.0.12)",
            "         ssh <address>      use another computer's terminal from here; exit returns",
            "         git clone <url>    copy a GitHub repository here (git help: pull, status, push)",
            "Game:    recipe <item>      the recipes that make an item, from every mod (--uses: that use it; --json)",
            "         item <item>        an item's names, tags, stack size, fuel and food values",
            "         items <word>       find items: a word, *pattern*, #tag or @mod",
            "Qbot:    craft <item> [count]   craft from what a Qbot carries (craft alone: its 3x3 grid)",
            "Learn:   git clone helpme   examples of everything, by topic, to read and run (no internet needed)",
            "Server:  service start <file.py> [name]   run a program in the background (service help)",
            "Other:   clear   help       Ctrl+C stops a running program",
            "         shutdown           switch this computer off; it stays off until opened again",
            "");

    /** The shell's commands, longest first, for spotting one typed without its space. */
    private static final List<String> COMMANDS = List.of("settings", "shutdown", "devices", "recipe", "craft", "items", "pip", "ifconfig", "python", "mkdir", "clear", "help",
            "service", "sleep", "echo", "edit", "item", "pwd", "cat", "run", "ssh", "git", "sh", "ls", "cd", "rm", "mv", "cp", "py");

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

    /** Carries out one command line. Returns false if it failed (a shell script stops there). */
    private boolean command(Computer computer, String line) throws InterruptedException {
        List<String> args = splitArguments(line);
        if (args.isEmpty()) {
            return true;
        }
        String name = args.removeFirst();
        boolean ok = true;
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
                case "mkdir" -> {
                    if (args.remove("-p")) {
                        // Every folder on the way, and no complaint if it is already there (for scripts run twice).
                        String target = path(args, 0, name);
                        StringBuilder at = new StringBuilder();
                        for (String part : target.split("/")) {
                            if (part.isEmpty()) {
                                continue;
                            }
                            at.append('/').append(part);
                            if (!exists(at.toString())) {
                                drive.call(HostFileSystem.MKDIR, at.toString(), new byte[0]);
                            } else if (!isFolder(at.toString())) {
                                throw new FsException(HostFileSystem.ENOTDIR);
                            }
                        }
                    } else {
                        drive.call(HostFileSystem.MKDIR, path(args, 0, name), new byte[0]);
                    }
                }
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
                case "run" -> ok = runFile(computer, path(args, 0, name));
                case "sh" -> ok = runScript(computer, path(args, 0, name));
                case "echo" -> terminal.write(String.join(" ", args) + "\n");
                case "sleep" -> {
                    if (args.isEmpty()) {
                        throw new UsageException("Usage: sleep <seconds>");
                    }
                    double seconds;
                    try {
                        seconds = Double.parseDouble(args.getFirst());
                    } catch (NumberFormatException e) {
                        throw new UsageException("sleep takes a number of seconds, such as sleep 2 or sleep 0.5");
                    }
                    ok = runPython(computer, "import time\ntime.sleep(" + Math.max(0, seconds) + ")"); // Ctrl+C stops it
                }
                case "ssh" -> {
                    if (args.isEmpty()) {
                        throw new UsageException("Usage: ssh <name or address>   (for example: ssh smelter, or ssh 100.64.0.12:1005)");
                    }
                    ssh(args.getFirst());
                }
                case "pip" -> {
                    // Installs libraries from a hard drive, here or on another network (pip.py in the ROM).
                    StringBuilder list = new StringBuilder("[");
                    for (String arg : args) {
                        list.append(pyString(arg)).append(',');
                    }
                    ok = runPython(computer, "import pip\npip.main(" + list.append(']') + ")");
                }
                case "service" -> service(args);
                case "git" -> {
                    // Clone, pull, status, push (git.py in the ROM; the game does the work).
                    StringBuilder list = new StringBuilder("[");
                    for (String arg : args) {
                        list.append(pyString(arg)).append(',');
                    }
                    ok = runPython(computer, "import os, git\nos.chdir(" + pyString(cwd) + ")\ngit._main(" + list.append(']') + ")");
                }
                case "recipe", "item", "items" -> {
                    // What the game knows about items and recipes (the game module): recipe [--uses] [--json] <item>.
                    StringBuilder list = new StringBuilder("[");
                    for (String arg : args) {
                        list.append(pyString(arg)).append(',');
                    }
                    ok = runPython(computer, "import game\ngame._shell(" + pyString(name) + ", " + list.append(']') + ")");
                }
                case "craft" -> {
                    // A Qbot's built-in crafting table (qbot.craft): craft <item> [count], or craft [count] for its grid.
                    if (!qbot) {
                        terminal.write("Only a Qbot can craft: it has a crafting table built in.\n");
                    } else {
                        StringBuilder list = new StringBuilder("[");
                        for (String arg : args) {
                            list.append(pyString(arg)).append(',');
                        }
                        ok = runPython(computer, "import qbot\nqbot._shell_craft(" + list.append(']') + ")");
                    }
                }
                case "devices", "ifconfig" -> {
                    // --json: the same as a list of objects, for programs and AI tools to read.
                    boolean json = args.remove("--json");
                    ok = runPython(computer, "import logistics\nlogistics._print_devices(" + (args.isEmpty() ? "None" : pyString(args.getFirst()))
                            + (json ? ", True" : "") + ")");
                }
                case "python", "py" -> {
                    if (args.isEmpty()) {
                        repl(computer);
                    } else {
                        ok = runFile(computer, args.getFirst());
                    }
                }
                default -> {
                    terminal.write("Unknown command: " + name + ". " + suggestion(name) + "\n");
                    ok = false;
                }
            }
        } catch (FsException e) {
            terminal.write(describe(e, args.isEmpty() ? "" : args.getFirst()) + "\n");
            ok = false;
        } catch (UsageException e) {
            terminal.write(e.getMessage() + "\n");
            ok = false;
        }
        return ok;
    }

    /** How deep scripts may run scripts (a script that runs itself stops here). */
    private static final int MAX_SCRIPT_DEPTH = 8;
    private int scriptDepth;

    /**
     * A shell script: a file of commands, one a line, run as if typed, from the script's own
     * folder. Blank lines and # comments are skipped. The first command that fails stops it.
     * Each command is shown as it runs, after a +. Afterwards the shell is back in its folder.
     */
    private boolean runScript(Computer computer, String path) throws InterruptedException {
        String file = Paths.resolve(cwd, path);
        String text;
        try {
            text = new String(drive.call(HostFileSystem.READ, file, new byte[0]), StandardCharsets.UTF_8);
        } catch (FsException e) {
            terminal.write(describe(e, file) + "\n");
            return false;
        }
        if (scriptDepth >= MAX_SCRIPT_DEPTH) {
            terminal.write("Scripts are running scripts " + MAX_SCRIPT_DEPTH + " deep: " + Paths.name(file) + " doesn't start.\n");
            return false;
        }
        String before = cwd;
        String folder = file.substring(0, Math.max(1, file.lastIndexOf('/')));
        cwd = folder;
        scriptDepth++;
        try {
            String[] lines = text.split("\n", -1);
            for (int number = 1; number <= lines.length; number++) {
                String line = lines[number - 1].strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (shutDown) {
                    return false;
                }
                terminal.write("+ " + line + "\n");
                if (!command(computer, line)) {
                    terminal.write(Paths.name(file) + " stopped at line " + number + ": " + line + "\n");
                    return false;
                }
            }
            return true;
        } finally {
            scriptDepth--;
            cwd = before;
        }
    }



    private static final String SERVICE_HELP = String.join("\n",
            "service start <file.py> [name]   run a program in the background, now and whenever the Server comes on",
            "service list                     the services, and how each is doing",
            "service log <name>               what a service has printed",
            "service restart <name>           run it again",
            "service stop <name>              stop it, and take it off the list",
            "");

    /** service start, stop, restart, list, log: a Server's background programs. */
    private void service(List<String> args) throws FsException {
        Services own = services;
        if (own == null) {
            terminal.write("Only a Server runs services. Here, run a program with: run <file.py>\n");
            return;
        }
        String what = args.isEmpty() ? "help" : args.getFirst();
        switch (what) {
            case "list" -> own.list().forEach(line -> terminal.write(line + "\n"));
            case "start" -> {
                if (args.size() < 2) {
                    throw new UsageException("Usage: service start <file.py> [name]");
                }
                String file = Paths.resolve(cwd, args.get(1));
                String kind = new String(drive.call(HostFileSystem.STAT, file, new byte[0]), StandardCharsets.UTF_8);
                if (kind.startsWith("d")) {
                    throw new UsageException(file + " is a folder, not a program.");
                }
                String name = args.size() > 2 ? args.get(2) : Paths.name(file).replaceFirst("\\.py$", "");
                terminal.write(own.start(file, name) + "\n");
            }
            case "stop", "restart", "log" -> {
                if (args.size() < 2) {
                    throw new UsageException("Usage: service " + what + " <name>");
                }
                terminal.write(what.equals("log") ? own.log(args.get(1)) : (what.equals("stop") ? own.stop(args.get(1)) : own.restart(args.get(1))) + "\n");
            }
            default -> terminal.write(SERVICE_HELP);
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
                case "mkdir" -> "mkdir [-p] <folder>";
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
