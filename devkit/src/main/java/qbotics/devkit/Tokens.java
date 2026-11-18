package qbotics.devkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Python split into words, enough to read which devices and modules a program uses: names,
 * numbers, text in quotes (with its value), operators and the ends of logical lines. Comments
 * are dropped; a line inside brackets runs on. Not a full tokenizer: the game's checker and
 * compiler decide whether the file is good Python.
 */
final class Tokens {
    enum Type { NAME, NUMBER, STRING, OP, NEWLINE }

    /**
     * One word: its line (0-based) and column. For text in quotes, {@code value} is what it
     * holds, or null when it is worked out as the program runs (an f-string).
     */
    record Token(Type type, String text, int line, int col, String value) {
        boolean is(String s) {
            return (type == Type.OP || type == Type.NAME) && text.equals(s);
        }
    }

    private Tokens() {
    }

    static List<Token> read(List<String> lines) {
        List<Token> out = new ArrayList<>();
        int depth = 0;
        int line = 0;
        int col = 0;
        while (line < lines.size()) {
            String text = lines.get(line);
            if (col >= text.length()) {
                boolean continued = text.endsWith("\\");
                if (depth == 0 && !continued && !out.isEmpty() && out.getLast().type() != Type.NEWLINE) {
                    out.add(new Token(Type.NEWLINE, "", line, col, null));
                }
                line++;
                col = 0;
                continue;
            }
            char c = text.charAt(col);
            if (c == '#') {
                col = text.length();
            } else if (Character.isWhitespace(c) || c == '\\') {
                col++;
            } else if (Character.isLetter(c) || c == '_') {
                int end = col;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(col, end);
                if (end < text.length() && (text.charAt(end) == '"' || text.charAt(end) == '\'') && word.length() <= 2
                        && word.toLowerCase().matches("[rbfu]+")) {
                    int[] at = {line, end};
                    String value = string(lines, at, word.toLowerCase().contains("r"));
                    out.add(new Token(Type.STRING, word, line, col, word.toLowerCase().contains("f") ? null : value));
                    line = at[0];
                    col = at[1];
                } else {
                    out.add(new Token(Type.NAME, word, line, col, null));
                    col = end;
                }
            } else if (Character.isDigit(c)) {
                int end = col;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.' || text.charAt(end) == '_')) {
                    end++;
                }
                out.add(new Token(Type.NUMBER, text.substring(col, end), line, col, null));
                col = end;
            } else if (c == '"' || c == '\'') {
                int[] at = {line, col};
                String value = string(lines, at, false);
                out.add(new Token(Type.STRING, "", line, col, value));
                line = at[0];
                col = at[1];
            } else {
                String op = String.valueOf(c);
                for (String two : new String[] {"**", "//", "==", "!=", "<=", ">=", "->", ":=", "+=", "-=", "*=", "/=", "%=", "<<", ">>"}) {
                    if (text.startsWith(two, col)) {
                        op = two;
                        break;
                    }
                }
                if ("([{".contains(op)) {
                    depth++;
                } else if (")]}".contains(op)) {
                    depth = Math.max(0, depth - 1);
                }
                out.add(new Token(Type.OP, op, line, col, null));
                col += op.length();
            }
        }
        if (!out.isEmpty() && out.getLast().type() != Type.NEWLINE) {
            out.add(new Token(Type.NEWLINE, "", lines.size(), 0, null));
        }
        return out;
    }

    /** Reads text in quotes starting at {@code at} (the opening quote); leaves {@code at} just past it. */
    private static String string(List<String> lines, int[] at, boolean raw) {
        String text = lines.get(at[0]);
        char quote = text.charAt(at[1]);
        boolean triple = text.startsWith(String.valueOf(quote).repeat(3), at[1]);
        String close = triple ? String.valueOf(quote).repeat(3) : String.valueOf(quote);
        at[1] += close.length();
        StringBuilder value = new StringBuilder();
        while (at[0] < lines.size()) {
            text = lines.get(at[0]);
            while (at[1] < text.length()) {
                char c = text.charAt(at[1]);
                if (c == '\\' && at[1] + 1 < text.length()) {
                    char next = text.charAt(at[1] + 1);
                    if (raw) {
                        value.append(c).append(next);
                    } else {
                        value.append(switch (next) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> next;
                        });
                    }
                    at[1] += 2;
                } else if (text.startsWith(close, at[1])) {
                    at[1] += close.length();
                    return value.toString();
                } else {
                    value.append(c);
                    at[1]++;
                }
            }
            if (!triple) {
                return value.toString(); // left open: the checker says so
            }
            value.append('\n');
            at[0]++;
            at[1] = 0;
        }
        return value.toString();
    }
}
