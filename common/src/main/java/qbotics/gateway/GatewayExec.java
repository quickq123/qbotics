package qbotics.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.jspecify.annotations.Nullable;
import qbotics.computer.ServerComputer;

/**
 * One command, not a session: {@code ssh name@server -p 2222 "run test.py"} runs it on the
 * computer as if typed at its prompt (ServerComputer.exec), prints what it printed, and ends
 * with its status, 0 if it worked. This is how scripts and AI tools on the player's PC ask
 * the game things ({@code devices --json}) and test programs.
 */
final class GatewayExec implements Command {
    /** A program still running after this long is stopped, as Ctrl+C would. */
    static final int TIMEOUT_SECONDS = 120;

    private final MinecraftServer mc;
    private final String line;
    private @Nullable OutputStream out;
    private @Nullable OutputStream err;
    private @Nullable ExitCallback exit;

    GatewayExec(MinecraftServer mc, String line) {
        this.mc = mc;
        this.line = line;
    }

    @Override
    public void setInputStream(InputStream in) {
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exit = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException {
        SshGateway.Target target = SshGateway.target(mc, channel.getServerSession());
        ServerComputer computer = target.computer();
        Thread.ofVirtual().name("qbotics-ssh-exec").start(() -> {
            int status;
            try {
                if (computer == null) {
                    write(err, (target.laptop() ? "Your laptop is off: open it in game once, and it runs while you carry it."
                            : target.label() + " isn't running.") + "\n");
                    status = 1;
                } else {
                    ServerComputer.ExecResult result = computer.exec(line, TIMEOUT_SECONDS).get(TIMEOUT_SECONDS + 30, TimeUnit.SECONDS);
                    write(out, result.output());
                    status = result.status();
                }
            } catch (Exception e) {
                write(err, "The command didn't finish: " + e.getMessage() + "\n");
                status = 1;
            }
            if (exit != null) {
                exit.onExit(status);
            }
        });
    }

    private static void write(@Nullable OutputStream stream, String text) {
        if (stream == null) {
            return;
        }
        try {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (IOException e) {
            // The client has gone; nothing to tell it.
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
    }
}
