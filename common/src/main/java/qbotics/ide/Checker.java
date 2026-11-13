package qbotics.ide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds mistakes in Python as it is typed, for the editor to underline: errors that stop a
 * program from starting (an unclosed bracket, a missing colon, indentation that doesn't line
 * up), and names that are never defined, which would stop it when that line runs.
 *
 * <p>It reads the whole file, in plain Java, in a few milliseconds, and says what is wrong in
 * words a beginner can act on, pointing at the characters concerned. It is not a full Python
 * parser: what it doesn't catch, the engine's own compiler does (the editor asks the server to
 * compile the file too, see IdePayloads.FileRequest.CHECK).
 */
public final class Checker {
    private Checker() {
    }

    public enum Severity { ERROR, WARNING }

    /** Something wrong: on one line (0-based), characters start to end (exclusive). */
    public record Problem(int line, int start, int end, Severity severity, String message) {
    }

    private enum Type { NAME, NUMBER, STRING, OP }

    private record Token(Type type, String text, int line, int start, int end) {
        boolean is(String s) {
            return text.equals(s) && (type == Type.OP || type == Type.NAME);
        }
    }

    /** A logical line: its tokens (it may run over several lines inside brackets), and its indent. */
    private record Logical(List<Token> tokens, int indent, int line) {
    }

    private static final Set<String> KEYWORDS = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
            "not", "or", "pass", "raise", "return", "try", "while", "with", "yield");
    /** Lines that start a block, and so end with a colon. */
    private static final Set<String> HEADERS = Set.of(
            "if", "elif", "else", "for", "while", "def", "class", "try", "except", "finally", "with");
    /** What the computers' Python has built in (MicroPython's builtins, as the engine lists them). */
    public static final Set<String> BUILTINS = Set.of(
            "__build_class__", "__import__", "__name__", "__file__", "__dict__", "__repl_print__",
            "abs", "all", "any", "bin", "bool", "bytearray", "bytes", "callable", "chr", "classmethod", "compile", "complex",
            "delattr", "dict", "dir", "divmod", "enumerate", "eval", "exec", "execfile", "filter", "float", "frozenset",
            "getattr", "globals", "hasattr", "hash", "help", "hex", "id", "input", "int", "isinstance", "issubclass", "iter",
            "len", "list", "locals", "map", "max", "memoryview", "min", "next", "object", "oct", "open", "ord", "pow", "print",
            "property", "range", "repr", "reversed", "round", "set", "setattr", "slice", "sorted", "staticmethod", "str",
            "sum", "super", "tuple", "type", "zip", "NotImplemented", "Ellipsis",
            "ArithmeticError", "AssertionError", "AttributeError", "BaseException", "EOFError", "Exception",
            "GeneratorExit", "ImportError", "IndentationError", "IndexError", "KeyError", "KeyboardInterrupt",
            "LookupError", "MemoryError", "NameError", "NotImplementedError", "OSError", "OverflowError", "RuntimeError",
            "StopAsyncIteration", "StopIteration", "SyntaxError", "SystemExit", "TypeError", "UnicodeError", "ValueError",
            "ZeroDivisionError");
    /** CPython names the computers' Python doesn't have, and what to use instead. */
    private static final Map<String, String> NOT_HERE = Map.of(
            "FileNotFoundError", "OSError (its errno is 2 when the file isn't there)",
            "FileExistsError", "OSError",
            "PermissionError", "OSError",
            "TimeoutError", "OSError",
            "ConnectionError", "OSError",
            "ModuleNotFoundError", "ImportError",
            "RecursionError", "RuntimeError");
    private static final String[] OPERATORS = {
            "**=", "//=", ">>=", "<<=", "...", "==", "!=", "<=", ">=", "->", ":=", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
            "^=", "@=", "**", "//", "<<", ">>"};
    private static final String SINGLE_OPERATORS = "+-*/%@&|^~<>=.,:;";

    /**
     * Checks a Python file.
     *
     * @param modules the names each of the mod's modules has (logistics: move, Face, ...), to
     *                catch a misspelt import; empty to skip that
     */
    public static List<Problem> check(List<String> lines, Map<String, Set<String>> modules) {
        Checker.Run run = new Checker.Run(lines, modules);
        run.tokenize();
        run.structure();
        if (!run.garbled) {
            run.names(); // unless a bracket or text in quotes left open has thrown the rest out of step
        }
        run.problems.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Integer.compare(a.start, b.start));
        return run.problems;
    }

    /**
     * The names a module offers, from its source: what it defines, assigns and imports at the top
     * level. Names starting with _ are left out.
     */
    public static Set<String> exports(List<String> source) {
        Set<String> names = new HashSet<>();
        for (String line : source) {
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String[] words = line.split("[^A-Za-z0-9_]+");
            if ((line.startsWith("def ") || line.startsWith("class ")) && words.length > 1) {
                names.add(words[1]);
            } else if (line.startsWith("import ")) {
                for (String part : line.substring(7).split(",")) {
                    String[] asWords = part.trim().split("\\s+");
                    names.add(asWords[asWords.length - 1].split("\\.")[0]);
                }
            } else if (line.startsWith("from ") && line.contains(" import ")) {
                for (String part : line.substring(line.indexOf(" import ") + 8).replace("(", "").replace(")", "").split(",")) {
                    String[] asWords = part.trim().split("\\s+");
                    if (asWords.length > 0 && !asWords[asWords.length - 1].isEmpty()) {
                        names.add(asWords[asWords.length - 1]);
                    }
                }
            } else if (line.matches("^[A-Za-z_][A-Za-z0-9_]*(\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*\\s*=[^=].*")) {
                for (String target : line.substring(0, line.indexOf('=')).split(",")) {
                    names.add(target.trim());
                }
            }
        }
        names.removeIf(name -> name.startsWith("_") || name.isEmpty());
        return names;
    }

    /** The closest of some names to a misspelt one, or null if none is close. */
    static String closest(String wrong, Iterable<String> names) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String lower = wrong.toLowerCase(Locale.ROOT);
        for (String name : names) {
            // Letters count the same in either case; a difference in case only breaks a tie.
            int distance = distance(lower, name.toLowerCase(Locale.ROOT)) * 100 + distance(wrong, name);
            if (distance < bestDistance || (distance == bestDistance && best != null && name.compareTo(best) < 0)) {
                best = name;
                bestDistance = distance;
            }
        }
        int allowed = wrong.length() <= 3 ? 1 : wrong.length() <= 6 ? 2 : 3;
        return bestDistance / 100 <= allowed ? best : null;
    }

    /** Edits (insert, delete, change, swap two neighbours) from one word to another. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** One check of one file. */
    private static final class Run {
        final List<String> lines;
        final Map<String, Set<String>> modules;
        final List<Problem> problems = new ArrayList<>();
        final List<Logical> logical = new ArrayList<>();
        /** A bracket, or text in quotes, is left open: what follows can't be read for names. */
        boolean garbled;

        Run(List<String> lines, Map<String, Set<String>> modules) {
            this.lines = lines;
            this.modules = modules;
        }

        void error(int line, int start, int end, String message) {
            problems.add(new Problem(line, start, Math.max(end, start + 1), Severity.ERROR, message));
        }

        void error(Token at, String message) {
            error(at.line, at.start, at.end, message);
        }

        void warning(Token at, String message) {
            problems.add(new Problem(at.line, at.start, at.end, Severity.WARNING, message));
        }

        boolean hasErrors() {
            for (Problem problem : problems) {
                if (problem.severity == Severity.ERROR) {
                    return true;
                }
            }
            return false;
        }

        // ---- reading the file into tokens and logical lines ----------------------------

        void tokenize() {
            List<Token> current = new ArrayList<>();
            List<Token> brackets = new ArrayList<>();   // the open ones
            int indent = 0;
            int logicalLine = 0;
            boolean continued = false;                    // the line before ended with a backslash
            String tripleQuote = null;                    // set while inside a triple-quoted string
            Token tripleStart = null;
            for (int n = 0; n < lines.size(); n++) {
                String text = lines.get(n);
                int i = 0;
                if (tripleQuote != null) {
                    int close = text.indexOf(tripleQuote);
                    if (close < 0) {
                        continue;
                    }
                    i = close + 3;
                    tripleQuote = null;
                } else if (brackets.isEmpty() && !continued) {
                    // A new logical line, unless this one is blank or only a comment.
                    String stripped = text.strip();
                    if (stripped.isEmpty() || stripped.startsWith("#")) {
                        continue;
                    }
                    int width = 0;
                    boolean tabs = false;
                    boolean spaces = false;
                    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                        if (text.charAt(i) == '\t') {
                            tabs = true;
                            width = (width / 8 + 1) * 8;
                        } else {
                            spaces = true;
                            width++;
                        }
                        i++;
                    }
                    if (tabs && spaces) {
                        error(n, 0, i, "This line is indented with both tabs and spaces. Use spaces only, so the lines line up as they look.");
                    }
                    indent = width;
                    logicalLine = n;
                }
                continued = false;
                while (i < text.length()) {
                    char c = text.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\f') {
                        i++;
                    } else if (c == '#') {
                        break;
                    } else if (c == '\\' && i == text.length() - 1) {
                        continued = true;
                        i++;
                    } else if (c == '"' || c == '\'' || (isStringPrefix(text, i))) {
                        int quoteAt = i;
                        while (text.charAt(quoteAt) != '"' && text.charAt(quoteAt) != '\'') {
                            quoteAt++;
                        }
                        char quote = text.charAt(quoteAt);
                        if (text.startsWith(String.valueOf(quote).repeat(3), quoteAt)) {
                            String triple = String.valueOf(quote).repeat(3);
                            int close = text.indexOf(triple, quoteAt + 3);
                            if (close < 0) {
                                tripleQuote = triple;
                                tripleStart = new Token(Type.STRING, triple, n, i, quoteAt + 3);
                                current.add(tripleStart);
                                i = text.length();
                            } else {
                                current.add(new Token(Type.STRING, text.substring(i, close + 3), n, i, close + 3));
                                i = close + 3;
                            }
                        } else {
                            int j = quoteAt + 1;
                            while (j < text.length() && text.charAt(j) != quote) {
                                j += text.charAt(j) == '\\' ? 2 : 1;
                            }
                            if (j >= text.length()) {
                                if (text.endsWith("\\")) {
                                    continued = true; // a string carried on to the next line
                                } else {
                                    error(n, i, text.length(), "This text has no closing " + quote + ". Text in quotes must end on the same line it starts on.");
                                }
                                current.add(new Token(Type.STRING, text.substring(i), n, i, text.length()));
                                i = text.length();
                            } else {
                                current.add(new Token(Type.STRING, text.substring(i, j + 1), n, i, j + 1));
                                i = j + 1;
                            }
                        }
                    } else if (Character.isDigit(c) || (c == '.' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)))) {
                        int j = i;
                        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.' || text.charAt(j) == '_'
                                || ((text.charAt(j) == '+' || text.charAt(j) == '-') && (text.charAt(j - 1) == 'e' || text.charAt(j - 1) == 'E')
                                        && !text.startsWith("0x", i) && !text.startsWith("0X", i)))) {
                            j++;
                        }
                        current.add(new Token(Type.NUMBER, text.substring(i, j), n, i, j));
                        i = j;
                    } else if (Character.isLetter(c) || c == '_') {
                        int j = i;
                        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                            j++;
                        }
                        current.add(new Token(Type.NAME, text.substring(i, j), n, i, j));
                        i = j;
                    } else if (c == '(' || c == '[' || c == '{') {
                        Token open = new Token(Type.OP, String.valueOf(c), n, i, i + 1);
                        brackets.add(open);
                        current.add(open);
                        i++;
                    } else if (c == ')' || c == ']' || c == '}') {
                        Token close = new Token(Type.OP, String.valueOf(c), n, i, i + 1);
                        if (brackets.isEmpty()) {
                            error(close, "This " + c + " has nothing to close: there is no " + opener(c) + " before it.");
                        } else {
                            Token open = brackets.removeLast();
                            if (closer(open.text.charAt(0)) != c) {
                                error(close, "Expected " + closer(open.text.charAt(0)) + " to close the " + open.text
                                        + (open.line == n ? " at column " + (open.start + 1) : " on line " + (open.line + 1)) + ", but found " + c + ".");
                            }
                        }
                        current.add(close);
                        i++;
                    } else {
                        String op = null;
                        for (String candidate : OPERATORS) {
                            if (text.startsWith(candidate, i)) {
                                op = candidate;
                                break;
                            }
                        }
                        if (op == null && SINGLE_OPERATORS.indexOf(c) >= 0) {
                            op = String.valueOf(c);
                        }
                        if (op == null) {
                            if (c == '!') {
                                error(n, i, i + 1, "Python writes \"not\" for !, and != for \"is not equal to\".");
                            } else if (c == '$' || c == '?' || c == '`') {
                                error(n, i, i + 1, "Python doesn't use " + c + " outside text in quotes.");
                            } else {
                                error(n, i, i + 1, "Python doesn't use the character " + c + " here (only inside text in quotes).");
                            }
                            i++;
                            continue;
                        }
                        current.add(new Token(Type.OP, op, n, i, i + op.length()));
                        i += op.length();
                    }
                }
                if (brackets.isEmpty() && !continued && tripleQuote == null && !current.isEmpty()) {
                    logical.add(new Logical(current, indent, logicalLine));
                    current = new ArrayList<>();
                }
            }
            garbled = tripleQuote != null || !brackets.isEmpty() || hasErrors();
            if (tripleQuote != null) {
                error(tripleStart, "This text in " + tripleQuote + " is never closed: add " + tripleQuote + " where it should end.");
            }
            for (Token open : brackets) {
                error(open, "This " + open.text + " is never closed: add " + closer(open.text.charAt(0)) + " where it should end.");
            }
            if (!current.isEmpty()) {
                logical.add(new Logical(current, indent, logicalLine));
            }
        }

        private static boolean isStringPrefix(String text, int at) {
            if (at > 0 && (Character.isLetterOrDigit(text.charAt(at - 1)) || text.charAt(at - 1) == '_')) {
                return false;
            }
            int j = at;
            while (j < text.length() && j - at < 2 && "rRbBfFuU".indexOf(text.charAt(j)) >= 0) {
                j++;
            }
            return j > at && j < text.length() && (text.charAt(j) == '"' || text.charAt(j) == '\'');
        }

        private static char closer(char open) {
            return open == '(' ? ')' : open == '[' ? ']' : '}';
        }

        private static char opener(char close) {
            return close == ')' ? '(' : close == ']' ? '[' : '{';
        }

        // ---- blocks: colons and indentation ----------------------------------------------

        void structure() {
            List<Integer> indents = new ArrayList<>(List.of(0));
            Token opener = null;           // the header whose block should come next
            for (Logical line : logical) {
                List<Token> tokens = line.tokens;
                Token first = tokens.getFirst();
                if (opener != null) {
                    if (line.indent > indents.getLast()) {
                        indents.add(line.indent);
                    } else {
                        error(first, "Expected an indented line here: the " + opener.text + " on line " + (opener.line + 1)
                                + " ends with : so the lines that belong to it go below it, indented. (Use pass for an empty one.)");
                    }
                    opener = null;
                } else if (line.indent > indents.getLast()) {
                    error(line.line, 0, first.start, "This line is indented, but the line before doesn't start a block "
                            + "(it doesn't end with :). Line it up with the line before.");
                    indents.add(line.indent);
                } else if (line.indent < indents.getLast()) {
                    while (indents.size() > 1 && indents.getLast() > line.indent) {
                        indents.removeLast();
                    }
                    if (indents.getLast() != line.indent) {
                        error(line.line, 0, first.start, "This line's indent doesn't line up with any line above it.");
                        indents.add(line.indent);
                    }
                }
                Token header = first;
                if (first.is("async") && tokens.size() > 1) {
                    header = tokens.get(1);
                }
                if (first.is("@")) {
                    continue;
                }
                if (first.is("print") && tokens.size() > 1 && (tokens.get(1).type == Type.STRING || tokens.get(1).type == Type.NUMBER
                        || (tokens.get(1).type == Type.NAME && !KEYWORDS.contains(tokens.get(1).text)))) {
                    error(first.line, first.start, tokens.getLast().end, "print needs brackets around what it prints: print(...)");
                    continue;
                }
                if (header.type == Type.NAME && HEADERS.contains(header.text)) {
                    if (checkHeader(tokens, tokens.indexOf(header))) {
                        opener = header;
                    }
                }
            }
            if (opener != null) {
                error(opener, "The " + opener.text + " on line " + (opener.line + 1) + " ends with : but nothing indented follows it. "
                        + "(Use pass for an empty block.)");
            }
        }

        /** Checks a line that starts a block. Returns true if its block is on the lines below. */
        private boolean checkHeader(List<Token> tokens, int at) {
            Token keyword = tokens.get(at);
            int colon = -1;
            int depth = 0;
            int lambdas = 0;
            for (int i = at + 1; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.is("(") || token.is("[") || token.is("{")) {
                    depth++;
                } else if (token.is(")") || token.is("]") || token.is("}")) {
                    depth--;
                } else if (depth == 0 && token.is("lambda")) {
                    lambdas++;
                } else if (depth == 0 && token.is(":")) {
                    if (lambdas > 0) {
                        lambdas--;
                    } else {
                        colon = i;
                        break;
                    }
                }
            }
            String word = keyword.text;
            if (word.equals("else") && at + 1 < tokens.size() && tokens.get(at + 1).is("if")) {
                error(keyword.line, keyword.start, tokens.get(at + 1).end, "Python writes \"else if\" as elif.");
                return colon == tokens.size() - 1;
            }
            if (word.equals("def")) {
                if (at + 1 >= tokens.size() || tokens.get(at + 1).type != Type.NAME) {
                    error(keyword, "def needs the function's name next: def name():");
                    return colon == tokens.size() - 1;
                }
                Token name = tokens.get(at + 1);
                if (at + 2 >= tokens.size() || !tokens.get(at + 2).is("(")) {
                    error(name, "A def needs brackets after the function's name, even with nothing in them: def " + name.text + "():");
                    return colon >= 0 && colon == tokens.size() - 1;
                }
            }
            if (colon < 0) {
                Token last = tokens.getLast();
                error(last.line, last.end, last.end + 1, "Add a : at the end of this line. A line starting with " + word
                        + " ends with a colon, and the lines that belong to it go below, indented.");
                return true;
            }
            if ((word.equals("else") || word.equals("try") || word.equals("finally")) && colon != at + 1) {
                error(tokens.get(at + 1), word + " takes nothing before its colon: " + word + ":");
            }
            if (word.equals("if") || word.equals("elif") || word.equals("while")) {
                int level = 0;
                for (int i = at + 1; i < colon; i++) {
                    Token token = tokens.get(i);
                    if (token.is("(") || token.is("[") || token.is("{")) {
                        level++;
                    } else if (token.is(")") || token.is("]") || token.is("}")) {
                        level--;
                    } else if (level == 0 && token.is("=")) {
                        error(token, "Use == to compare. A single = puts a value in a variable.");
                    }
                }
            }
            return colon == tokens.size() - 1;
        }

        // ---- names: defined, used, and the mod's modules ------------------------------------

        void names() {
            Set<String> defined = new HashSet<>(BUILTINS);
            Map<String, String> moduleAliases = new HashMap<>();   // a name bound to one of the mod's modules
            boolean starImport = false;
            List<Token> uses = new ArrayList<>();
            for (Logical line : logical) {
                List<Token> tokens = line.tokens;
                Token first = tokens.getFirst();
                if (first.is("import")) {
                    // import a.b, c as d
                    for (int i = 1; i < tokens.size(); i++) {
                        Token token = tokens.get(i);
                        if (token.type == Type.NAME && !token.is("as") && (i == 1 || tokens.get(i - 1).is(","))) {
                            String bound = token.text;
                            int j = i;
                            while (j + 2 < tokens.size() && tokens.get(j + 1).is(".")) {
                                j += 2;
                            }
                            if (j + 2 < tokens.size() && tokens.get(j + 1).is("as")) {
                                bound = tokens.get(j + 2).text;
                            }
                            defined.add(bound);
                            if (modules.containsKey(token.text) && j == i) {
                                moduleAliases.put(bound, token.text);
                            }
                        }
                    }
                    continue;
                }
                if (first.is("from")) {
                    int importAt = -1;
                    for (int i = 1; i < tokens.size(); i++) {
                        if (tokens.get(i).is("import")) {
                            importAt = i;
                            break;
                        }
                    }
                    String module = importAt == 2 ? tokens.get(1).text : null;
                    for (int i = importAt + 1; importAt > 0 && i < tokens.size(); i++) {
                        Token token = tokens.get(i);
                        if (token.is("*")) {
                            starImport = true;
                        } else if (token.type == Type.NAME && !token.is("as")) {
                            boolean renamed = i + 2 < tokens.size() && tokens.get(i + 1).is("as");
                            if (!(i > 0 && tokens.get(i - 1).is("as"))) {
                                checkModuleName(module, token);
                            }
                            if (!renamed) {
                                defined.add(token.text);
                            }
                        }
                    }
                    continue;
                }
                bind(tokens, defined, uses);
            }
            for (Token use : uses) {
                if (starImport || defined.contains(use.text)) {
                    continue;
                }
                String instead = NOT_HERE.get(use.text);
                if (instead != null) {
                    warning(use, use.text + " doesn't exist on the computers' Python: use " + instead + ".");
                    continue;
                }
                String near = closest(use.text, defined);
                warning(use, use.text + " isn't defined anywhere in this file" + (near != null ? ". Did you mean " + near + "?" : "."));
            }
            for (Logical line : logical) {
                List<Token> tokens = line.tokens;
                for (int i = 0; i + 2 < tokens.size(); i++) {
                    Token token = tokens.get(i);
                    if (token.type == Type.NAME && moduleAliases.containsKey(token.text) && tokens.get(i + 1).is(".")
                            && (i == 0 || !tokens.get(i - 1).is(".")) && tokens.get(i + 2).type == Type.NAME) {
                        checkModuleName(moduleAliases.get(token.text), tokens.get(i + 2));
                    }
                }
            }
        }

        private void checkModuleName(String module, Token name) {
            Set<String> offered = module == null ? null : modules.get(module);
            if (offered == null || offered.isEmpty() || name.text.startsWith("_") || offered.contains(name.text)) {
                return;
            }
            String near = closest(name.text, offered);
            error(name, module + " has no " + name.text + (near != null ? ". Did you mean " + near + "?" : "."));
        }

        /** Notes the names a statement defines, and collects the ones it uses. */
        private void bind(List<Token> tokens, Set<String> defined, List<Token> uses) {
            Token first = tokens.getFirst();
            if (first.is("global") || first.is("nonlocal")) {
                for (Token token : tokens) {
                    if (token.type == Type.NAME) {
                        defined.add(token.text);
                    }
                }
                return;
            }
            // Where a single = at depth 0 is, the names before it are being assigned. (x += 1 isn't:
            // it uses x, which must already exist.)
            int lastAssign = -1;
            int depth = 0;
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.is("(") || token.is("[") || token.is("{")) {
                    depth++;
                } else if (token.is(")") || token.is("]") || token.is("}")) {
                    depth--;
                } else if (depth == 0 && token.is("=")) {
                    lastAssign = i;
                }
            }
            // x: int = 5
            if (tokens.size() > 1 && first.type == Type.NAME && tokens.get(1).is(":") && !HEADERS.contains(first.text)
                    && !KEYWORDS.contains(first.text)) {
                defined.add(first.text);
            }
            depth = 0;
            // For each open bracket: true if it only groups (a, (b, c) = ...), false for a call or
            // an index (f(x), d[i]), whose names are used rather than assigned.
            List<Boolean> grouping = new ArrayList<>();
            boolean inParams = false;      // inside a def's brackets
            int paramsDepth = 0;
            boolean afterDefault = false;  // in a parameter's default value or annotation
            int lambdaParams = 0;          // > 0 while reading a lambda's parameters
            boolean forTarget = false;     // between "for" and "in"
            int forDepth = 0;
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                Token before = i > 0 ? tokens.get(i - 1) : null;
                Token after = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                if (token.is("(") || token.is("[") || token.is("{")) {
                    depth++;
                    grouping.add(!token.is("{") && (before == null || (before.type == Type.OP
                            && !before.is(")") && !before.is("]") && !before.is("}")) || (before.type == Type.NAME && KEYWORDS.contains(before.text))));
                    continue;
                }
                if (token.is(")") || token.is("]") || token.is("}")) {
                    depth--;
                    if (!grouping.isEmpty()) {
                        grouping.removeLast();
                    }
                    if (inParams && depth < paramsDepth) {
                        inParams = false;
                    }
                    continue;
                }
                if (inParams && depth == paramsDepth) {
                    if (token.is(",")) {
                        afterDefault = false;
                    } else if (token.is("=") || token.is(":")) {
                        afterDefault = true;
                    }
                }
                if (lambdaParams > 0) {
                    if (token.is(":") && depth == forDepth) {
                        lambdaParams = 0;
                    } else if (token.type == Type.NAME && (before.is("lambda") || before.is(",") || before.is("*") || before.is("**"))) {
                        defined.add(token.text);
                        continue;
                    }
                }
                if (token.type != Type.NAME) {
                    continue;
                }
                String word = token.text;
                if (word.equals("lambda")) {
                    lambdaParams = 1;
                    forDepth = depth;
                    continue;
                }
                if (word.equals("for")) {
                    forTarget = true;
                    forDepth = depth;
                    continue;
                }
                if (word.equals("in") && forTarget && depth == forDepth) {
                    forTarget = false;
                    continue;
                }
                if (KEYWORDS.contains(word)) {
                    continue;
                }
                if (before != null && before.is(".")) {
                    continue; // an attribute
                }
                if (before != null && (before.is("def") || before.is("class"))) {
                    defined.add(word);
                    if (before.is("def") && after != null && after.is("(")) {
                        inParams = true;
                        paramsDepth = depth + 1;
                        afterDefault = false;
                    }
                    continue;
                }
                if (inParams && depth == paramsDepth && !afterDefault) {
                    defined.add(word); // a parameter
                    continue;
                }
                if (before != null && before.is("as")) {
                    defined.add(word);
                    continue;
                }
                if (forTarget) {
                    defined.add(word);
                    continue;
                }
                if (after != null && after.is(":=")) {
                    defined.add(word);
                    continue;
                }
                if (depth > 0 && after != null && after.is("=") && before != null && (before.is("(") || before.is(","))) {
                    continue; // a keyword argument: f(name=...)
                }
                if (i < lastAssign && !grouping.contains(false) && (after == null || !(after.is(".") || after.is("[") || after.is("(")))) {
                    defined.add(word); // assigned: x = ..., a, b = ...
                    continue;
                }
                uses.add(token);
            }
        }
    }
}
