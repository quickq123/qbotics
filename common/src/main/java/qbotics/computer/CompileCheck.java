package qbotics.computer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import qbotics.engine.Computer;
import qbotics.engine.SyntaxCheck;

/**
 * Compiles a file the editor has open, without running it, to find what the engine itself
 * can't read (IdePayloads.FileRequest.CHECK). The editor's own checker (qbotics.ide.Checker)
 * explains the common mistakes; this catches the rest, in the engine's words.
 *
 * <p>One small Python instance does it for everyone, never a computer's own, so a running
 * program is never disturbed. Called on the file worker thread.
 */
public final class CompileCheck {
    private static final int HEAP_BYTES = 1024 * 1024;
    private static Computer python;

    private CompileCheck() {
    }

    /** "" if the file compiles; otherwise its line (1-based), a tab, and what the engine says. */
    public static synchronized String check(String path, byte[] source) {
        try {
            if (python == null) {
                python = new Computer(ComputerManager.engine(), HEAP_BYTES,
                        call -> CompletableFuture.failedFuture(new IllegalStateException("no world here")));
            }
            String answer = SyntaxCheck.check(python, path, new String(source, StandardCharsets.UTF_8));
            if (answer == null) {
                python = null; // out of memory, say: start afresh next time
                return "";
            }
            return answer;
        } catch (RuntimeException e) {
            python = null;
            return "";
        }
    }
}
