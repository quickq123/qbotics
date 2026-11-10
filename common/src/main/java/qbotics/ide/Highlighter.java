package qbotics.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a line of code into coloured pieces. Works one line at a time; the only thing
 * carried from line to line is whether a Python triple-quoted string is still open.
 */
public final class Highlighter {
    public enum Language {
        PYTHON("Python", "#"),
        JSON("JSON", null),
        PLAIN("Plain Text", null);

        public final String label;
        /** What starts a line comment, or null if the language has none. */
        public final String commentMarker;

        Language(String label, String commentMarker) {
            this.label = label;
            this.commentMarker = commentMarker;
        }

        public static Language forFile(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".py")) {
                return PYTHON;
            }
            if (lower.endsWith(".json")) {
                return JSON;
            }
            return PLAIN;
        }
    }

    public enum Kind {
        PLAIN, KEYWORD, CONTROL, BUILTIN, FUNCTION, STRING, NUMBER, COMMENT, DECORATOR, SELF, KEY, LITERAL, ERROR
    }

    /** One coloured piece: characters start (inclusive) to end (exclusive) of the line. */
    public record Token(int start, int end, Kind kind) {
    }

    /** @param endState pass this as the state for the next line */
    public record Line(List<Token> tokens, int endState) {
    }

    public static final int NORMAL = 0;
    private static final int IN_TRIPLE_SINGLE = 1;
    private static final int IN_TRIPLE_DOUBLE = 2;

    private static final Set<String> CONTROL = Set.of(
            "if", "elif", "else", "for", "while", "break", "continue", "return", "yield", "try", "except",
            "finally", "raise", "with", "as", "import", "from", "pass", "assert", "del", "await", "async");
    private static final Set<String> KEYWORDS = Set.of(
            "def", "class", "lambda", "and", "or", "not", "in", "is", "True", "False", "None", "global", "nonlocal");
    private static final Set<String> BUILTINS = Set.of(
            "print", "len", "range", "int", "str", "float", "bool", "list", "dict", "set", "tuple", "bytes",
            "bytearray", "open", "input", "type", "isinstance", "enumerate", "zip", "map", "filter", "sorted",
            "reversed", "min", "max", "sum", "abs", "round", "any", "all", "repr", "hasattr", "getattr",
            "setattr", "super", "object", "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
            "OSError", "RuntimeError", "StopIteration", "execfile", "id", "hex", "bin", "ord", "chr", "divmod", "pow");

    private Highlighter() {
    }

    public static Line highlight(Language language, String text, int state) {
        return switch (language) {
            case PYTHON -> python(text, state);
            case JSON -> new Line(json(text), NORMAL);
            case PLAIN -> new Line(List.of(), NORMAL);
        };
    }

    // ---- Python ----------------------------------------------------------------

    private static Line python(String text, int state) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        String previousWord = "";

        if (state != NORMAL) {
            int close = text.indexOf(state == IN_TRIPLE_SINGLE ? "'''" : "\"\"\"");
            if (close < 0) {
                tokens.add(new Token(0, n, Kind.STRING));
                return new Line(tokens, state);
            }
            tokens.add(new Token(0, close + 3, Kind.STRING));
            i = close + 3;
        }

        while (i < n) {
            char c = text.charAt(i);
            if (c == '#') {
                tokens.add(new Token(i, n, Kind.COMMENT));
                break;
            }
            if (c == '"' || c == '\'' || isStringPrefix(text, i)) {
                int quoteAt = i;
                while (text.charAt(quoteAt) != '"' && text.charAt(quoteAt) != '\'') {
                    quoteAt++;
                }
                char quote = text.charAt(quoteAt);
                if (text.startsWith(String.valueOf(quote).repeat(3), quoteAt)) {
                    int close = text.indexOf(String.valueOf(quote).repeat(3), quoteAt + 3);
                    if (close < 0) {
                        tokens.add(new Token(i, n, Kind.STRING));
                        return new Line(tokens, quote == '\'' ? IN_TRIPLE_SINGLE : IN_TRIPLE_DOUBLE);
                    }
                    tokens.add(new Token(i, close + 3, Kind.STRING));
                    i = close + 3;
                } else {
                    int end = quoteAt + 1;
                    while (end < n && text.charAt(end) != quote) {
                        end += text.charAt(end) == '\\' ? 2 : 1;
                    }
                    end = Math.min(n, end + 1);
                    tokens.add(new Token(i, end, Kind.STRING));
                    i = end;
                }
                previousWord = "";
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.' || text.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new Token(i, end, Kind.NUMBER));
                i = end;
                previousWord = "";
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(i, end);
                Kind kind = Kind.PLAIN;
                if (previousWord.equals("def") || previousWord.equals("class")) {
                    kind = Kind.FUNCTION;
                } else if (CONTROL.contains(word)) {
                    kind = Kind.CONTROL;
                } else if (KEYWORDS.contains(word)) {
                    kind = Kind.KEYWORD;
                } else if (word.equals("self")) {
                    kind = Kind.SELF;
                } else if (BUILTINS.contains(word)) {
                    kind = Kind.BUILTIN;
                } else if (nextNonSpace(text, end) == '(') {
                    kind = Kind.FUNCTION;
                }
                if (kind != Kind.PLAIN) {
                    tokens.add(new Token(i, end, kind));
                }
                previousWord = word;
                i = end;
                continue;
            }
            if (c == '@' && text.substring(0, i).isBlank()) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_' || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(i, end, Kind.DECORATOR));
                i = end;
                continue;
            }
            if (c != ' ') {
                previousWord = "";
            }
            i++;
        }
        return new Line(tokens, NORMAL);
    }

    /** True for the letters before a string such as f"..." or rb'...'. */
    private static boolean isStringPrefix(String text, int at) {
        int i = at;
        while (i < text.length() && i - at < 2 && "rRbBfFuU".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        boolean startsWord = at == 0 || !(Character.isLetterOrDigit(text.charAt(at - 1)) || text.charAt(at - 1) == '_');
        return i > at && startsWord && i < text.length() && (text.charAt(i) == '"' || text.charAt(i) == '\'');
    }

    private static char nextNonSpace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) != ' ') {
                return text.charAt(i);
            }
        }
        return 0;
    }

    // ---- JSON ------------------------------------------------------------------

    private static List<Token> json(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < n && text.charAt(end) != '"') {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                boolean closed = end < n;
                end = Math.min(n, end + 1);
                Kind kind = !closed ? Kind.ERROR : nextNonSpace(text, end) == ':' ? Kind.KEY : Kind.STRING;
                tokens.add(new Token(i, end, kind));
                i = end;
            } else if (Character.isDigit(c) || c == '-') {
                int end = i + 1;
                while (end < n && "0123456789.eE+-".indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                tokens.add(new Token(i, end, Kind.NUMBER));
                i = end;
            } else if (Character.isLetter(c)) {
                int end = i + 1;
                while (end < n && Character.isLetter(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                boolean literal = word.equals("true") || word.equals("false") || word.equals("null");
                tokens.add(new Token(i, end, literal ? Kind.LITERAL : Kind.ERROR));
                i = end;
            } else {
                if (c != ' ' && "{}[]:,".indexOf(c) < 0) {
                    tokens.add(new Token(i, i + 1, Kind.ERROR));
                }
                i++;
            }
        }
        return tokens;
    }
}
