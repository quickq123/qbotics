package qbotics.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles Python without running it, in the engine's own compiler, and says where it can't
 * read the file. The editor's check (qbotics.computer.CompileCheck) and the offline devkit's
 * check command both use this, so a file passes offline exactly when it passes in the game.
 */
public final class SyntaxCheck {
    private static final Pattern ERROR = Pattern.compile("File \"[^\"]*\", line (\\d+)[^\\n]*\\n(?:.*\\n)*?SyntaxError: (.*)");

    private SyntaxCheck() {
    }

    /**
     * "" if the file compiles; otherwise its line (1-based), a tab, and what the engine says.
     * Null if the engine couldn't do it (out of memory, say): start a fresh one.
     */
    public static String check(Computer python, String path, String source) {
        python.takeOutput();
        boolean ran = python.exec(String.join("\n",
                "import sys, io",
                "try:",
                "    compile(" + literal(source) + ", " + literal(path) + ", 'exec')",
                "except SyntaxError as e:",
                "    s = io.StringIO()",
                "    sys.print_exception(e, s)",
                "    print(s.getvalue())",
                "    del s",
                "e = None",
                ""));
        String output = python.takeOutput();
        if (!ran) {
            return null;
        }
        // ... File "/a.py", line 3 ... SyntaxError: invalid syntax
        Matcher line = ERROR.matcher(output.substring(Math.max(0, output.indexOf("File \"" + path))));
        return line.find() ? line.group(1) + "\t" + line.group(2).strip() : "";
    }

    /** A Python string literal holding the text exactly. */
    public static String literal(String text) {
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
