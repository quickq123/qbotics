package qbotics;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import qbotics.engine.Computer;
import qbotics.engine.Engine;

/** Loader-independent entry point, called once by each loader's own entry class. */
public final class Qbotics {
    /** Permanent: this is in every block name and save file. */
    public static final String MOD_ID = "qbotics";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static QboticsPlatform platform;

    private Qbotics() {
    }

    public static void init(QboticsPlatform loaderPlatform) {
        platform = loaderPlatform;
        LOGGER.info("Qbotics loading");
        if (Boolean.getBoolean("qbotics.selfTest")) {
            selfTest();
        }
    }

    private static volatile net.minecraft.server.@org.jspecify.annotations.Nullable MinecraftServer server;

    /** The server running now (on this machine), or null between games. */
    public static net.minecraft.server.@org.jspecify.annotations.Nullable MinecraftServer server() {
        return server;
    }

    public static void setServer(net.minecraft.server.@org.jspecify.annotations.Nullable MinecraftServer running) {
        server = running;
    }

    public static QboticsPlatform platform() {
        return platform;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Runs a Python script on the engine and logs the result. Enabled with -Dqbotics.selfTest=true. */
    private static void selfTest() {
        String[] result = {"did not run"};
        Thread thread = Computer.newThread(() -> {
            try {
                long start = System.nanoTime();
                Engine engine = Engine.bundled();
                Computer computer = new Computer(engine, 256 * 1024,
                        call -> CompletableFuture.completedFuture("ok:" + call));
                boolean ok = computer.exec(String.join("\n",
                        "import _host",
                        "def fib(n):",
                        "    return n if n < 2 else fib(n - 1) + fib(n - 2)",
                        "print('fib(20) =', fib(20), '| world call ->', _host.wait(_host.submit('move.forward')))"));
                ok &= computer.execRepl("6 * 7");
                ok &= computer.replNeedsMore("if True:") && !computer.replNeedsMore("x = 1");
                result[0] = (ok ? "PASS: " : "FAIL: ") + computer.takeOutput().trim().replace("\n", " | REPL 6 * 7 -> ")
                        + " (" + (System.nanoTime() - start) / 1_000_000 + " ms including engine load)";
            } catch (Throwable e) {
                LOGGER.error("Qbotics engine self-test threw", e);
                result[0] = "FAIL: " + e;
            }
        }, "qbotics-selftest");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Qbotics engine self-test {}", result[0]);
    }
}
