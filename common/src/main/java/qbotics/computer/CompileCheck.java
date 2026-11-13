package qbotics.computer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import qbotics.engine.Computer;

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
            python.takeOutput();
            boolean ran = python.exec(String.join("\n",
                    "import sys, io",
                    "try:",
                    "    compile(" + literal(new String(source, StandardCharsets.UTF_8)) + ", " + literal(path) + ", 'exec')",
                    "except SyntaxError as e:",
                    "    s = io.StringIO()",
                    "    sys.print_exception(e, s)",
                    "    print(s.getvalue())",
                    "    del s",
                    "e = None",
                    ""));
            String output = python.takeOutput();
            if (!ran) {
                python = null; // out of memory, say: start afresh next time
                return "";
            }
            // ... File "/a.py", line 3 ... SyntaxError: invalid syntax
            java.util.regex.Matcher line = java.util.regex.Pattern.compile("File \"[^\"]*\", line (\\d+)[^\\n]*\\n(?:.*\\n)*?SyntaxError: (.*)")
                    .matcher(output.substring(Math.max(0, output.indexOf("File \"" + path))));
            return line.find() ? line.group(1) + "\t" + line.group(2).strip() : "";
        } catch (RuntimeException e) {
            python = null;
            return "";
        }
    }

    /** A Python string literal holding the text exactly. */
    private static String literal(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16).append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('\'').toString();
    }
}
