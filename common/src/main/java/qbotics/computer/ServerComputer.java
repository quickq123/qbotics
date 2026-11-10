package qbotics.computer;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import qbotics.Qbotics;
import qbotics.engine.Computer;
import qbotics.engine.Engine;

/**
 * One running computer on the server: its Python instance, its screen, and the thread that
 * runs its REPL. It is independent of the block's chunk being loaded (design doc §6.2).
 */
public final class ServerComputer {
    /** Basic tier heap (design doc §3.4). */
    private static final int HEAP_BYTES = 256 * 1024;
    private static final int MAX_QUEUED_LINES = 256;

    private final UUID deviceId;
    private final Terminal terminal = new Terminal();
    private final BlockingQueue<String> inputLines = new LinkedBlockingQueue<>(MAX_QUEUED_LINES);
    private final Semaphore cpu;
    private final Thread thread;

    private volatile Computer vm;
    private volatile boolean running;
    private volatile boolean shutDown;
    /** Set by Ctrl+C while the prompt is waiting for more lines of a multi-line entry. */
    private volatile boolean discardEntry;

    ServerComputer(UUID deviceId, Semaphore cpu) {
        this.deviceId = deviceId;
        this.cpu = cpu;
        this.thread = Computer.newThread(this::run, "qbotics-computer-" + deviceId.toString().substring(0, 8));
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public UUID deviceId() {
        return deviceId;
    }

    public Terminal terminal() {
        return terminal;
    }

    /** A line the player typed and entered. Dropped if the computer is too far behind. */
    public void submitLine(String line) {
        inputLines.offer(line);
    }

    /** Ctrl+C: stop the running program, or abandon a half-typed multi-line entry. */
    public void interrupt() {
        Computer current = vm;
        if (running && current != null) {
            current.stop = Computer.StopMode.POLITE;
        } else {
            discardEntry = true;
            inputLines.offer("");
        }
    }

    /** The block was removed or the server is stopping. The thread ends and the instance is dropped. */
    void shutDown() {
        shutDown = true;
        Computer current = vm;
        if (current != null) {
            current.stop = Computer.StopMode.HARD;
        }
        thread.interrupt();
    }

    private void run() {
        try {
            terminal.write("Qbotics computer starting...\n");
            Computer computer = new Computer(ComputerManager.engine(), HEAP_BYTES,
                    call -> CompletableFuture.completedFuture("error:no world calls yet"));
            computer.cpu = cpu;
            computer.outputSink = terminal::write;
            vm = computer;
            terminal.clear();
            terminal.write("MicroPython on Qbotics. Ctrl+C stops a program.\n");
            repl(computer);
        } catch (InterruptedException e) {
            // Shut down while waiting for input.
        } catch (Throwable e) {
            if (!shutDown) {
                Qbotics.LOGGER.error("Computer {} crashed", deviceId, e);
                terminal.write("\nThis computer crashed and needs to be re-placed.\n");
            }
        } finally {
            vm = null;
        }
    }

    private void repl(Computer computer) throws InterruptedException {
        while (!shutDown) {
            terminal.write(">>> ");
            String entry = readLine();
            // Keep reading lines while the entry is incomplete, as the Python prompt does.
            while (!discardEntry && computer.replNeedsMore(entry)) {
                terminal.write("... ");
                entry = entry + "\n" + readLine();
            }
            if (discardEntry) {
                discardEntry = false;
                terminal.write("KeyboardInterrupt\n");
                continue;
            }
            if (entry.isBlank()) {
                continue;
            }
            running = true;
            try {
                computer.execRepl(entry);
            } finally {
                running = false;
                computer.stop = shutDown ? Computer.StopMode.HARD : Computer.StopMode.NONE;
            }
        }
    }

    private String readLine() throws InterruptedException {
        String line = inputLines.take();
        if (!discardEntry) {
            terminal.write(line + "\n");
        }
        return line;
    }
}
