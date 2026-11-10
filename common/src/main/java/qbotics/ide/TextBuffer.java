package qbotics.ide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The text of one open file, with a cursor, a selection and undo. This is the editor's
 * logic only: it has no drawing and no Minecraft in it, so it can be tested on its own.
 *
 * <p>Positions are (line, column), both from zero. The selection runs from the anchor to
 * the cursor; when they are the same place nothing is selected.
 */
public final class TextBuffer {
    public static final int INDENT = 4;
    private static final int MAX_UNDO = 200;

    private final List<String> lines = new ArrayList<>();
    private int line;
    private int column;
    private int anchorLine;
    private int anchorColumn;
    /** The column the cursor "wants" when moving up and down through shorter lines. */
    private int wantedColumn;

    private record Snapshot(List<String> lines, int line, int column) {
    }

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    /** True while a run of plain typing is being collected into one undo step. */
    private boolean typingRun;
    private long version;
    private long savedVersion;

    public TextBuffer(String text) {
        setLines(text);
    }

    private void setLines(String text) {
        lines.clear();
        // Tabs become spaces: the editor draws on a fixed character grid.
        String clean = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", " ".repeat(INDENT));
        int start = 0;
        for (int i = 0; i <= clean.length(); i++) {
            if (i == clean.length() || clean.charAt(i) == '\n') {
                lines.add(clean.substring(start, i));
                start = i + 1;
            }
        }
    }

    // ---- reading ---------------------------------------------------------------

    public String text() {
        return String.join("\n", lines);
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int index) {
        return lines.get(index);
    }

    public int cursorLine() {
        return line;
    }

    public int cursorColumn() {
        return column;
    }

    public boolean hasSelection() {
        return line != anchorLine || column != anchorColumn;
    }

    private boolean anchorFirst() {
        return anchorLine < line || (anchorLine == line && anchorColumn <= column);
    }

    public int selectionStartLine() {
        return anchorFirst() ? anchorLine : line;
    }

    public int selectionStartColumn() {
        return anchorFirst() ? anchorColumn : column;
    }

    public int selectionEndLine() {
        return anchorFirst() ? line : anchorLine;
    }

    public int selectionEndColumn() {
        return anchorFirst() ? column : anchorColumn;
    }

    public String selectedText() {
        if (!hasSelection()) {
            return "";
        }
        int sl = selectionStartLine();
        int el = selectionEndLine();
        if (sl == el) {
            return lines.get(sl).substring(selectionStartColumn(), selectionEndColumn());
        }
        StringBuilder out = new StringBuilder(lines.get(sl).substring(selectionStartColumn()));
        for (int i = sl + 1; i < el; i++) {
            out.append('\n').append(lines.get(i));
        }
        return out.append('\n').append(lines.get(el), 0, selectionEndColumn()).toString();
    }

    /** Changes every time the text changes; lets callers cache things computed from the text. */
    public long version() {
        return version;
    }

    /** True if the text differs from when it was last loaded or saved. */
    public boolean isDirty() {
        return version != savedVersion;
    }

    /** Records that the text as it was at this version() is what is saved. */
    public void markSaved(long atVersion) {
        savedVersion = atVersion;
    }

    public void markSaved() {
        savedVersion = version;
    }

    // ---- moving ----------------------------------------------------------------

    /** Puts the cursor somewhere (clamped to the text). With extend, the selection grows to it. */
    public void setCursor(int newLine, int newColumn, boolean extend) {
        line = Math.clamp(newLine, 0, lines.size() - 1);
        column = Math.clamp(newColumn, 0, lines.get(line).length());
        if (!extend) {
            anchorLine = line;
            anchorColumn = column;
        }
        wantedColumn = column;
        typingRun = false;
    }

    public void moveLeft(boolean extend) {
        if (!extend && hasSelection()) {
            setCursor(selectionStartLine(), selectionStartColumn(), false);
        } else if (column > 0) {
            setCursor(line, column - 1, extend);
        } else if (line > 0) {
            setCursor(line - 1, lines.get(line - 1).length(), extend);
        }
    }

    public void moveRight(boolean extend) {
        if (!extend && hasSelection()) {
            setCursor(selectionEndLine(), selectionEndColumn(), false);
        } else if (column < lines.get(line).length()) {
            setCursor(line, column + 1, extend);
        } else if (line < lines.size() - 1) {
            setCursor(line + 1, 0, extend);
        }
    }

    /** Moves up (negative) or down by whole lines, keeping the column where possible. */
    public void moveLines(int delta, boolean extend) {
        int wanted = wantedColumn;
        int target = line + delta;
        if (target < 0) {
            setCursor(0, 0, extend);
        } else if (target >= lines.size()) {
            setCursor(lines.size() - 1, lines.getLast().length(), extend);
        } else {
            setCursor(target, wanted, extend);
        }
        wantedColumn = wanted;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public void moveWordLeft(boolean extend) {
        if (column == 0) {
            moveLeft(extend);
            return;
        }
        String text = lines.get(line);
        int c = column;
        while (c > 0 && !isWordChar(text.charAt(c - 1))) {
            c--;
        }
        while (c > 0 && isWordChar(text.charAt(c - 1))) {
            c--;
        }
        setCursor(line, c, extend);
    }

    public void moveWordRight(boolean extend) {
        String text = lines.get(line);
        if (column == text.length()) {
            moveRight(extend);
            return;
        }
        int c = column;
        while (c < text.length() && !isWordChar(text.charAt(c))) {
            c++;
        }
        while (c < text.length() && isWordChar(text.charAt(c))) {
            c++;
        }
        setCursor(line, c, extend);
    }

    /** Home: to the first non-space character, or to column 0 if already there. */
    public void moveHome(boolean extend) {
        int indent = indentOf(lines.get(line));
        setCursor(line, column == indent ? 0 : indent, extend);
    }

    public void moveEnd(boolean extend) {
        setCursor(line, lines.get(line).length(), extend);
    }

    public void moveDocumentStart(boolean extend) {
        setCursor(0, 0, extend);
    }

    public void moveDocumentEnd(boolean extend) {
        setCursor(lines.size() - 1, lines.getLast().length(), extend);
    }

    public void selectAll() {
        anchorLine = 0;
        anchorColumn = 0;
        line = lines.size() - 1;
        column = lines.getLast().length();
        typingRun = false;
    }

    /** Selects the word at a position (for a double click). */
    public void selectWordAt(int atLine, int atColumn) {
        setCursor(atLine, atColumn, false);
        String text = lines.get(line);
        int start = column;
        int end = column;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        anchorLine = line;
        anchorColumn = start;
        column = end;
    }

    // ---- editing ---------------------------------------------------------------

    private void beginEdit(boolean typing) {
        if (!(typing && typingRun)) {
            undo.push(new Snapshot(new ArrayList<>(lines), line, column));
            if (undo.size() > MAX_UNDO) {
                undo.removeLast();
            }
        }
        redo.clear();
        typingRun = typing;
        version++;
    }

    private void removeSelection() {
        if (!hasSelection()) {
            return;
        }
        int sl = selectionStartLine();
        int sc = selectionStartColumn();
        int el = selectionEndLine();
        int ec = selectionEndColumn();
        String joined = lines.get(sl).substring(0, sc) + lines.get(el).substring(ec);
        for (int i = el; i > sl; i--) {
            lines.remove(i);
        }
        lines.set(sl, joined);
        line = anchorLine = sl;
        column = anchorColumn = sc;
    }

    private void insertRaw(String text) {
        String clean = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", " ".repeat(INDENT));
        String current = lines.get(line);
        String before = current.substring(0, column);
        String after = current.substring(column);
        String[] parts = clean.split("\n", -1);
        if (parts.length == 1) {
            lines.set(line, before + parts[0] + after);
            column += parts[0].length();
        } else {
            lines.set(line, before + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                lines.add(line + i, parts[i]);
            }
            String last = parts[parts.length - 1];
            lines.add(line + parts.length - 1, last + after);
            line += parts.length - 1;
            column = last.length();
        }
        anchorLine = line;
        anchorColumn = column;
        wantedColumn = column;
    }

    /** Inserts text at the cursor, replacing the selection. Used for paste. */
    public void insert(String text) {
        if (text.isEmpty() && !hasSelection()) {
            return;
        }
        beginEdit(false);
        removeSelection();
        insertRaw(text);
    }

    /** One typed character. A run of typing is undone as a single step. */
    public void type(char c) {
        boolean plain = !hasSelection() && c != ' ';
        beginEdit(plain);
        removeSelection();
        insertRaw(String.valueOf(c));
    }

    /** Enter: a new line indented like this one, and one level deeper after ":" or an open bracket. */
    public void newLine() {
        beginEdit(false);
        removeSelection();
        String before = lines.get(line).substring(0, column);
        int indent = indentOf(before.isBlank() ? before + "x" : before);
        String trimmed = before.stripTrailing();
        if (trimmed.endsWith(":") || trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(")) {
            indent += INDENT;
        }
        insertRaw("\n" + " ".repeat(indent));
    }

    public void backspace() {
        if (hasSelection()) {
            beginEdit(false);
            removeSelection();
        } else if (column > 0) {
            beginEdit(false);
            String text = lines.get(line);
            // In the indentation, backspace removes back to the previous indent stop.
            int remove = 1;
            if (text.substring(0, column).isBlank()) {
                remove = (column - 1) % INDENT + 1;
            }
            lines.set(line, text.substring(0, column - remove) + text.substring(column));
            setCursorQuiet(line, column - remove);
        } else if (line > 0) {
            beginEdit(false);
            int joinAt = lines.get(line - 1).length();
            lines.set(line - 1, lines.get(line - 1) + lines.remove(line));
            setCursorQuiet(line - 1, joinAt);
        }
    }

    public void deleteForward() {
        if (hasSelection()) {
            beginEdit(false);
            removeSelection();
        } else if (column < lines.get(line).length()) {
            beginEdit(false);
            String text = lines.get(line);
            lines.set(line, text.substring(0, column) + text.substring(column + 1));
        } else if (line < lines.size() - 1) {
            beginEdit(false);
            lines.set(line, lines.get(line) + lines.remove(line + 1));
        }
    }

    private void setCursorQuiet(int newLine, int newColumn) {
        line = anchorLine = newLine;
        column = anchorColumn = newColumn;
        wantedColumn = column;
    }

    /** Tab: indent the selected lines, or move to the next indent stop. */
    public void indent() {
        if (selectionStartLine() != selectionEndLine()) {
            shiftLines(INDENT);
        } else {
            beginEdit(false);
            removeSelection();
            insertRaw(" ".repeat(INDENT - column % INDENT));
        }
    }

    /** Shift+Tab: remove one level of indentation from the selected lines. */
    public void outdent() {
        shiftLines(-INDENT);
    }

    private void shiftLines(int by) {
        beginEdit(false);
        int first = selectionStartLine();
        int last = lastSelectedLine();
        for (int i = first; i <= last; i++) {
            String text = lines.get(i);
            int delta;
            if (by > 0) {
                delta = text.isBlank() ? 0 : by;
                lines.set(i, " ".repeat(delta) + text);
            } else {
                delta = -Math.min(-by, indentOf(text.isBlank() ? text + "x" : text));
                lines.set(i, text.substring(-delta));
            }
            if (i == line) {
                column = Math.max(0, column + delta);
            }
            if (i == anchorLine) {
                anchorColumn = Math.max(0, anchorColumn + delta);
            }
        }
        wantedColumn = column;
    }

    /** A selection that ends at column 0 of a line doesn't really include that line. */
    private int lastSelectedLine() {
        int last = selectionEndLine();
        return last > selectionStartLine() && selectionEndColumn() == 0 ? last - 1 : last;
    }

    /** Ctrl+/: comment out the selected lines, or uncomment them if they all are comments. */
    public void toggleComment(String marker) {
        beginEdit(false);
        int first = selectionStartLine();
        int last = lastSelectedLine();
        boolean allCommented = true;
        int minIndent = Integer.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            String text = lines.get(i);
            if (!text.isBlank()) {
                allCommented &= text.stripLeading().startsWith(marker);
                minIndent = Math.min(minIndent, indentOf(text));
            }
        }
        if (minIndent == Integer.MAX_VALUE) {
            return;
        }
        for (int i = first; i <= last; i++) {
            String text = lines.get(i);
            if (text.isBlank()) {
                continue;
            }
            int delta;
            if (allCommented) {
                int at = indentOf(text);
                int length = text.startsWith(marker + " ", at) ? marker.length() + 1 : marker.length();
                lines.set(i, text.substring(0, at) + text.substring(at + length));
                delta = -length;
            } else {
                lines.set(i, text.substring(0, minIndent) + marker + " " + text.substring(minIndent));
                delta = marker.length() + 1;
            }
            if (i == line) {
                column = Math.clamp(column + delta, 0, lines.get(i).length());
            }
            if (i == anchorLine) {
                anchorColumn = Math.clamp(anchorColumn + delta, 0, lines.get(i).length());
            }
        }
        wantedColumn = column;
    }

    /** Replaces the whole text as one undoable step (used by "format document"). */
    public void replaceAll(String text) {
        beginEdit(false);
        int keepLine = line;
        setLines(text);
        setCursorQuiet(Math.min(keepLine, lines.size() - 1), 0);
    }

    public void undo() {
        swap(undo, redo);
    }

    public void redo() {
        swap(redo, undo);
    }

    private void swap(Deque<Snapshot> from, Deque<Snapshot> to) {
        if (from.isEmpty()) {
            return;
        }
        to.push(new Snapshot(new ArrayList<>(lines), line, column));
        Snapshot snapshot = from.pop();
        lines.clear();
        lines.addAll(snapshot.lines());
        setCursorQuiet(snapshot.line(), snapshot.column());
        typingRun = false;
        version++;
    }

    private static int indentOf(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
