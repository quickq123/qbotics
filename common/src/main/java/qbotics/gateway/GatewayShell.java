package qbotics.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.jspecify.annotations.Nullable;
import qbotics.computer.ServerComputer;
import qbotics.computer.Terminal;

/**
 * An SSH shell that is an in-game computer's terminal. What the computer writes goes to the
 * SSH client as it is written; what the client types is edited here, a line at a time (shown
 * as it is typed), and handed to the computer on Enter, as the game's own terminal does.
 * Ctrl+C stops a program and Ctrl+Z pauses it, as in game; Ctrl+D on an empty line leaves.
 */
final class GatewayShell implements Command {
    private final MinecraftServer mc;
    private @Nullable InputStream in;
    private @Nullable OutputStream out;
    private @Nullable ExitCallback exit;
    private volatile boolean closed;
    private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();
    private @Nullable Terminal terminal;
    private final Consumer<String> listener = text -> outgoing.add(text.replace("\n", "\r\n"));

    GatewayShell(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        SshGateway.Target target = SshGateway.target(mc, channel.getServerSession());
        ServerComputer computer = target.computer();
        Thread writer = Thread.ofVirtual().name("qbotics-ssh-out").start(this::writeLoop);
        if (computer == null) {
            outgoing.add((target.laptop() ? "Your laptop is off: open it in game once, and it runs while you carry it."
                    : target.label() + " isn't running.") + "\r\n");
            finish(1);
            return;
        }
        Terminal screen = computer.terminal();
        terminal = screen;
        // What is on its screen now, so the prompt is there; then everything it writes from here on.
        StringBuilder now = new StringBuilder();
        now.append("Connected to ").append(target.label())
                .append(". Ctrl+C stops a program, Ctrl+Z pauses it, Ctrl+D (on an empty line) leaves.\r\n");
        if (computer.held()) {
            now.append("[This laptop is held while you are offline or carry no laptop in game: it carries on when you do.]\r\n");
        }
        List<String> lines = screen.full().lines();
        int last = lines.size() - 1;
        while (last > 0 && lines.get(last).isBlank()) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            now.append(lines.get(i).stripTrailing());
            if (i < last) {
                now.append("\r\n");
            }
        }
        // The prompt's trailing space was stripped; give it back, so typing doesn't run into it.
        if (now.toString().endsWith("$") || now.toString().endsWith(">>>") || now.toString().endsWith("...")) {
            now.append(' ');
        }
        outgoing.add(now.toString());
        screen.addListener(listener);
        Thread.ofVirtual().name("qbotics-ssh-in").start(() -> readLoop(computer));
        writer.getName(); // started above
    }

    /** Sends what is queued to the client, in order. */
    private void writeLoop() {
        try {
            while (!closed || !outgoing.isEmpty()) {
                String text = outgoing.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (text != null && out != null) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException | InterruptedException e) {
            closed = true;
        }
    }

    /** Reads what the client types: a line edited here, handed over on Enter. */
    private void readLoop(ServerComputer computer) {
        StringBuilder line = new StringBuilder();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int previous = 0;
            int c;
            while (!closed && (c = reader.read()) >= 0) {
                switch (c) {
                    case '\r', '\n' -> {
                        if (c == '\n' && previous == '\r') {
                            break; // \r\n is one Enter
                        }
                        // The computer shows the line itself as it takes it: take back what was shown while typing.
                        if (!line.isEmpty()) {
                            outgoing.add("\b".repeat(line.length()) + "\u001b[K");
                        }
                        computer.submitLine(line.toString());
                        line.setLength(0);
                    }
                    case 0x7f, 0x08 -> {
                        if (!line.isEmpty()) {
                            line.setLength(line.length() - 1);
                            outgoing.add("\b \b");
                        }
                    }
                    case 0x03 -> {
                        if (!line.isEmpty()) {
                            outgoing.add("\b".repeat(line.length()) + "\u001b[K");
                            line.setLength(0);
                        }
                        computer.interrupt(); // Ctrl+C
                    }
                    case 0x1a -> computer.togglePause(); // Ctrl+Z
                    case 0x04 -> {
                        if (line.isEmpty()) {
                            outgoing.add("\r\nDisconnected.\r\n");
                            finish(0);
                            return;
                        }
                    }
                    case 0x1b -> skipEscape(reader); // arrow keys and the like: not used
                    default -> {
                        if (c >= 32 && line.length() < qbotics.net.TerminalInputPayload.MAX_LINE_LENGTH) {
                            line.append((char) c);
                            outgoing.add(String.valueOf((char) c));
                        }
                    }
                }
                previous = c;
            }
        } catch (IOException e) {
            // the client went away
        }
        finish(0);
    }

    /** Skips the rest of an escape sequence, such as ESC [ A for an arrow key. */
    private static void skipEscape(Reader reader) throws IOException {
        int next = reader.read();
        if (next == '[' || next == 'O') {
            int c;
            do {
                c = reader.read();
            } while (c >= 0 && (c < 0x40 || c > 0x7e));
        }
    }

    private synchronized void finish(int code) {
        if (terminal != null) {
            terminal.removeListener(listener);
            terminal = null;
        }
        if (!closed) {
            closed = true;
            // Let what is queued go out first.
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (exit != null) {
                exit.onExit(code);
            }
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        if (terminal != null) {
            terminal.removeListener(listener);
            terminal = null;
        }
        closed = true;
    }
}
