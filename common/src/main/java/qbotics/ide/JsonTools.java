package qbotics.ide;

/** Checks and tidies JSON for the editor. A small parser of its own, so it can say where a mistake is. */
public final class JsonTools {
    private static final int MAX_DEPTH = 100;

    /**
     * @param formatted the tidied text, or null if the JSON is not valid
     * @param error     what is wrong, or null; line and column count from 1
     */
    public record Result(String formatted, String error, int line, int column) {
        public boolean ok() {
            return error == null;
        }
    }

    private static final class Problem extends RuntimeException {
        final int at;

        Problem(String message, int at) {
            super(message, null, false, false);
            this.at = at;
        }
    }

    private final String text;
    private final StringBuilder out = new StringBuilder();
    private final int indentWidth;
    private int pos;

    private JsonTools(String text, int indentWidth) {
        this.text = text;
        this.indentWidth = indentWidth;
    }

    /** Re-indents JSON, one item per line. Keys stay in the order they were written. */
    public static Result format(String text, int indentWidth) {
        JsonTools parser = new JsonTools(text, indentWidth);
        try {
            parser.skipSpace();
            if (parser.pos == text.length()) {
                throw new Problem("The file is empty", 0);
            }
            parser.value(0);
            parser.skipSpace();
            if (parser.pos < text.length()) {
                throw new Problem("Unexpected text after the end of the JSON", parser.pos);
            }
            return new Result(parser.out.append('\n').toString(), null, 0, 0);
        } catch (Problem problem) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(problem.at, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new Result(null, problem.getMessage(), line, column);
        }
    }

    /** Null if the text is valid JSON, otherwise the problem. */
    public static Result check(String text) {
        Result result = format(text, 2);
        return result.ok() ? null : result;
    }

    private void skipSpace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : 0;
    }

    private void newLine(int depth) {
        out.append('\n').append(" ".repeat(depth * indentWidth));
    }

    private void value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new Problem("Nested too deeply", pos);
        }
        char c = peek();
        if (c == '{') {
            members(depth, '}', true);
        } else if (c == '[') {
            members(depth, ']', false);
        } else if (c == '"') {
            string();
        } else if (c == '-' || Character.isDigit(c)) {
            number();
        } else if (text.startsWith("true", pos) || text.startsWith("null", pos)) {
            out.append(text, pos, pos + 4);
            pos += 4;
        } else if (text.startsWith("false", pos)) {
            out.append("false");
            pos += 5;
        } else if (pos >= text.length()) {
            throw new Problem("The JSON ends too early", pos);
        } else {
            throw new Problem("Expected a value (text needs \"double quotes\")", pos);
        }
    }

    /** The inside of an object or array. */
    private void members(int depth, char close, boolean isObject) {
        out.append(text.charAt(pos++));
        skipSpace();
        if (peek() == close) {
            out.append(close);
            pos++;
            return;
        }
        while (true) {
            newLine(depth + 1);
            skipSpace();
            if (isObject) {
                if (peek() != '"') {
                    throw new Problem(peek() == close ? "Remove the comma before " + close : "Expected a \"key\" in double quotes", pos);
                }
                string();
                skipSpace();
                if (peek() != ':') {
                    throw new Problem("Expected : after the key", pos);
                }
                pos++;
                out.append(": ");
                skipSpace();
            } else if (peek() == close) {
                throw new Problem("Remove the comma before " + close, pos);
            }
            value(depth + 1);
            skipSpace();
            if (peek() == ',') {
                out.append(',');
                pos++;
            } else if (peek() == close) {
                newLine(depth);
                out.append(close);
                pos++;
                return;
            } else if (pos >= text.length()) {
                throw new Problem("Missing " + close + " at the end", pos);
            } else {
                throw new Problem("Expected , or " + close, pos);
            }
        }
    }

    private void string() {
        int start = pos++;
        while (true) {
            if (pos >= text.length() || text.charAt(pos) == '\n') {
                throw new Problem("This text is missing its closing \"", start);
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escape = peek();
                if ("\"\\/bfnrtu".indexOf(escape) < 0 || escape == 0) {
                    throw new Problem("Unknown escape \\" + escape, pos - 1);
                }
                pos++;
            }
        }
        out.append(text, start, pos);
    }

    private void number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        int digits = pos;
        while (Character.isDigit(peek())) {
            pos++;
        }
        if (pos == digits) {
            throw new Problem("Expected a digit", pos);
        }
        if (peek() == '.') {
            pos++;
            int fraction = pos;
            while (Character.isDigit(peek())) {
                pos++;
            }
            if (pos == fraction) {
                throw new Problem("Expected a digit after the decimal point", pos);
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            int exponent = pos;
            while (Character.isDigit(peek())) {
                pos++;
            }
            if (pos == exponent) {
                throw new Problem("Expected a digit in the exponent", pos);
            }
        }
        out.append(text, start, pos);
    }
}
