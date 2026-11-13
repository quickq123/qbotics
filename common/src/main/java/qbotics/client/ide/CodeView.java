package qbotics.client.ide;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
import qbotics.ide.Checker;
import qbotics.ide.Completer;
import qbotics.ide.Docs;
import qbotics.ide.Finder;
import qbotics.ide.Highlighter;
import qbotics.ide.Highlighter.Language;
import qbotics.ide.Highlighter.Token;
import qbotics.ide.JsonTools;
import qbotics.ide.TextBuffer;

/**
 * Shows a {@link TextBuffer} and turns keys and mouse clicks into edits. Used for each
 * editor tab and for the scratchpad's code box.
 */
final class CodeView {
    private static final int SCROLLBAR_W = 5;
    private static final int MAX_POPUP_ROWS = 8;
    private static final int KEY_SPACE = 32;

    final TextBuffer buffer;
    /** Decides the colouring and the suggestions; follows the file's name when it is renamed. */
    Language language;
    private final boolean lineNumbers;

    private int scrollLine;
    private int scrollColumn;
    /** Where the view was last drawn; needed to turn a mouse position into a text position. */
    private Ui.Rect area = new Ui.Rect(0, 0, 0, 0);
    private int textLeft;
    private boolean draggingText;
    private boolean draggingScrollbar;
    /** After an edit or cursor move, scroll so the cursor is on screen. */
    private boolean revealCursor = true;

    // Things worked out from the text, redone only when the text changes.
    private long analysedVersion = -1;
    private int[] lineStartState = new int[0];
    private JsonTools.Result jsonProblem;
    private int longestLine;

    // Mistakes, found a moment after typing stops (qbotics.ide.Checker), then by the engine.
    private static final long CHECK_AFTER_MS = 500;
    private static final int WARNING = 0xFFE0B040;
    private List<Checker.Problem> problems = List.of();
    private long checkedVersion = -1;
    private long seenVersion = -1;
    private long editedAt;
    /** Asks the server to compile the file (FileRequest.CHECK), or null: set for an editor tab. */
    IdeBackend compiler;
    /** The file's path, for the engine's messages. */
    String path = "/scratch.py";
    private long compiledVersion = -1;

    /** Places the find bar's text was found; drawn highlighted. Set by the editor screen. */
    List<Finder.Match> matches = List.of();
    int currentMatch = -1;

    // The autocomplete list, when it is showing.
    private Completer.Result completion;
    private int completionIndex;
    private int completionTop;
    /** True once the player has moved through the list; only then does Enter pick from it. */
    private boolean completionNavigated;

    CodeView(TextBuffer buffer, Language language, boolean lineNumbers) {
        this.buffer = buffer;
        this.language = language;
        this.lineNumbers = lineNumbers;
    }

    /** The file was renamed: colour it, and offer suggestions, by its new name. */
    void setLanguage(Language changed) {
        if (changed != language) {
            language = changed;
            analysedVersion = -1;
            dismissPopup();
        }
    }

    /** For JSON files: what is wrong with the text, or null. */
    JsonTools.Result jsonProblem() {
        analyse();
        return jsonProblem;
    }

    private void analyse() {
        if (analysedVersion == buffer.version()) {
            return;
        }
        analysedVersion = buffer.version();
        lineStartState = new int[buffer.lineCount()];
        longestLine = 0;
        int state = Highlighter.NORMAL;
        for (int i = 0; i < buffer.lineCount(); i++) {
            lineStartState[i] = state;
            longestLine = Math.max(longestLine, buffer.line(i).length());
            if (language == Language.PYTHON) {
                state = Highlighter.highlight(language, buffer.line(i), state).endState();
            }
        }
        jsonProblem = language == Language.JSON && !buffer.text().isBlank() ? JsonTools.check(buffer.text()) : null;
    }

    private int visibleLines() {
        return Math.max(1, area.height() / Ui.CELL_H);
    }

    private int visibleColumns() {
        return Math.max(1, (area.right() - SCROLLBAR_W - textLeft) / Ui.CELL_W - 1);
    }

    private int maxScrollLine() {
        return Math.max(0, buffer.lineCount() - 1);
    }

    /** Scroll so the cursor (or the selection's end) can be seen. */
    void reveal() {
        revealCursor = true;
    }

    // ---- drawing -----------------------------------------------------------------

    /** The line the program is on (0-based), marked green while it runs and amber while paused; -1 for none. */
    int runLine = -1;
    boolean runPaused;

    /** Scrolls so a line (0-based) is in view. */
    void reveal(int line) {
        if (line < scrollLine || line >= scrollLine + visibleLines()) {
            scrollLine = Math.max(0, line - visibleLines() / 3);
        }
    }

    /** The mistakes found in the text as it was a moment ago. */
    List<Checker.Problem> problems() {
        return problems;
    }

    /** The first mistake on a line, or null. */
    Checker.Problem problemOn(int line) {
        for (Checker.Problem problem : problems) {
            if (problem.line() == line) {
                return problem;
            }
        }
        return null;
    }

    /** Checks the text once typing has paused; then, if it found nothing, has the engine compile it. */
    private void checkProblems() {
        if (language != Language.PYTHON) {
            problems = List.of();
            return;
        }
        long version = buffer.version();
        long now = System.currentTimeMillis();
        if (version != seenVersion) {
            seenVersion = version;
            editedAt = now;
        }
        if (version == checkedVersion || now - editedAt < CHECK_AFTER_MS) {
            return;
        }
        checkedVersion = version;
        List<String> lines = new ArrayList<>(buffer.lineCount());
        for (int i = 0; i < buffer.lineCount(); i++) {
            lines.add(buffer.line(i));
        }
        problems = Checker.check(lines, Docs.moduleExports());
        boolean errors = problems.stream().anyMatch(p -> p.severity() == Checker.Severity.ERROR);
        if (!errors && compiler != null && compiledVersion != version) {
            compiledVersion = version;
            compiler.file(qbotics.net.IdePayloads.FileRequest.CHECK, path,
                    buffer.text().getBytes(java.nio.charset.StandardCharsets.UTF_8), (errno, data) -> {
                        String answer = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        int tab = answer.indexOf('\t');
                        if (errno != 0 || tab < 0 || buffer.version() != version) {
                            return;
                        }
                        try {
                            int line = Integer.parseInt(answer.substring(0, tab)) - 1;
                            if (line >= 0 && line < buffer.lineCount()) {
                                String text = buffer.line(line);
                                int start = text.length() - text.stripLeading().length();
                                List<Checker.Problem> more = new ArrayList<>(problems);
                                more.add(new Checker.Problem(line, start, Math.max(start + 1, text.stripTrailing().length()),
                                        Checker.Severity.ERROR, "Python can't read this line: " + answer.substring(tab + 1) + "."));
                                problems = more;
                            }
                        } catch (NumberFormatException e) {
                            // Not an answer we understand: nothing to show.
                        }
                    });
        }
    }

    void render(Ui ui, Ui.Rect rect, boolean focused, boolean cursorOn) {
        analyse();
        checkProblems();
        area = rect;
        int gutter = lineNumbers ? (String.valueOf(buffer.lineCount()).length() + 2) * Ui.CELL_W + 4 : 4;
        textLeft = rect.x() + gutter;

        if (revealCursor) {
            revealCursor = false;
            scrollLine = Math.clamp(scrollLine, buffer.cursorLine() - visibleLines() + 1, buffer.cursorLine());
            scrollColumn = Math.clamp(scrollColumn, buffer.cursorColumn() - visibleColumns() + 1, buffer.cursorColumn());
        }
        scrollLine = Math.clamp(scrollLine, 0, maxScrollLine());
        scrollColumn = Math.clamp(scrollColumn, 0, Math.max(0, longestLine - 1));

        ui.fill(rect.x(), rect.y(), rect.width(), rect.height(), Ui.EDITOR_BG);
        ui.clip(rect.x(), rect.y(), rect.width(), rect.height());

        int lastLine = Math.min(buffer.lineCount(), scrollLine + visibleLines() + 1);
        boolean selection = buffer.hasSelection();
        for (int line = scrollLine; line < lastLine; line++) {
            int y = rect.y() + (line - scrollLine) * Ui.CELL_H;
            String text = buffer.line(line);
            boolean currentLine = focused && line == buffer.cursorLine() && !selection;

            if (currentLine) {
                ui.fill(rect.x(), y, rect.width(), Ui.CELL_H, Ui.LINE_HIGHLIGHT);
            }
            if (line == runLine) {
                // The program is here: a band across the line, and a mark in the gutter.
                ui.fill(rect.x(), y, rect.width(), Ui.CELL_H, runPaused ? 0x50F0B030 : 0x3058E060);
                ui.fill(rect.x(), y, 3, Ui.CELL_H, runPaused ? 0xFFF0B030 : 0xFF58E060);
            }
            // Text scrolled sideways must not show through the line numbers.
            ui.clip(textLeft - 2, rect.y(), rect.right() - textLeft + 2, rect.height());
            for (int i = 0; i < matches.size(); i++) {
                Finder.Match match = matches.get(i);
                if (match.line() == line) {
                    highlight(ui, match.start(), match.end(), y, i == currentMatch ? Ui.MATCH_CURRENT : Ui.MATCH);
                }
            }
            if (selection && line >= buffer.selectionStartLine() && line <= buffer.selectionEndLine()) {
                int from = line == buffer.selectionStartLine() ? buffer.selectionStartColumn() : 0;
                // A selected line break shows as one extra selected cell.
                int to = line == buffer.selectionEndLine() ? buffer.selectionEndColumn() : text.length() + 1;
                highlight(ui, from, to, y, Ui.SELECTION);
            }
            drawLine(ui, text, line, y);
            for (Checker.Problem problem : problems) {
                if (problem.line() == line) {
                    squiggle(ui, problem.start(), problem.end(), y, problem.severity() == Checker.Severity.ERROR ? Ui.ERROR : WARNING);
                }
            }
            ui.unclip();
            if (lineNumbers) {
                String number = String.valueOf(line + 1);
                Checker.Problem onLine = problemOn(line);
                boolean bad = (jsonProblem != null && jsonProblem.line() == line + 1)
                        || (onLine != null && onLine.severity() == Checker.Severity.ERROR);
                ui.mono(number, textLeft - 4 - (number.length() + 1) * Ui.CELL_W, y,
                        bad ? Ui.ERROR : line == buffer.cursorLine() ? Ui.TEXT : Ui.TEXT_DIM);
            }
        }

        if (focused && cursorOn && buffer.cursorColumn() >= scrollColumn) {
            int x = textLeft + (buffer.cursorColumn() - scrollColumn) * Ui.CELL_W;
            int y = rect.y() + (buffer.cursorLine() - scrollLine) * Ui.CELL_H;
            ui.fill(x - 1, y, 1, Ui.CELL_H, Ui.TEXT_BRIGHT);
        }
        ui.unclip();

        // Scroll bars: shown only when there is more text than fits.
        if (buffer.lineCount() > visibleLines()) {
            Ui.Rect thumb = verticalThumb();
            ui.fill(rect.right() - SCROLLBAR_W, rect.y(), SCROLLBAR_W, rect.height(), 0x20FFFFFF);
            ui.fill(thumb.x(), thumb.y(), thumb.width(), thumb.height(), draggingScrollbar ? 0xA0FFFFFF : 0x60FFFFFF);
        }
        if (longestLine > visibleColumns()) {
            int track = rect.right() - SCROLLBAR_W - textLeft;
            int thumb = Math.max(12, track * visibleColumns() / longestLine);
            int left = (track - thumb) * scrollColumn / Math.max(1, longestLine - 1);
            ui.fill(textLeft + left, rect.bottom() - 3, thumb, 3, 0x60FFFFFF);
        }
    }

    private Ui.Rect verticalThumb() {
        int track = area.height();
        int thumb = Math.max(10, track * visibleLines() / (buffer.lineCount() + visibleLines() - 1));
        int top = (track - thumb) * scrollLine / Math.max(1, maxScrollLine());
        return new Ui.Rect(area.right() - SCROLLBAR_W, area.y() + top, SCROLLBAR_W, thumb);
    }

    /** A wavy line under columns from..to of the line drawn at y, as code editors mark mistakes. */
    private void squiggle(Ui ui, int from, int to, int y, int color) {
        int x0 = textLeft + (from - scrollColumn) * Ui.CELL_W;
        int x1 = textLeft + (to - scrollColumn) * Ui.CELL_W;
        int base = y + Ui.CELL_H - 2;
        for (int x = x0; x < x1; x++) {
            int phase = (x - x0) % 4;
            ui.fill(x, base + (phase == 1 ? -1 : phase == 3 ? 1 : 0), 1, 1, color);
        }
    }

    /** A coloured background behind columns from..to of the line drawn at y. */
    private void highlight(Ui ui, int from, int to, int y, int color) {
        int x0 = textLeft + (from - scrollColumn) * Ui.CELL_W;
        int x1 = textLeft + (to - scrollColumn) * Ui.CELL_W;
        ui.fill(x0, y, x1 - x0, Ui.CELL_H, color);
    }

    private void drawLine(Ui ui, String text, int line, int y) {
        int first = Math.min(text.length(), scrollColumn);
        int last = Math.min(text.length(), scrollColumn + visibleColumns() + 2);
        if (first >= last) {
            return;
        }
        List<Token> tokens = Highlighter.highlight(language, text, lineStartState[line]).tokens();
        int at = first;
        for (Token token : tokens) {
            if (token.end() <= at) {
                continue;
            }
            if (token.start() >= last) {
                break;
            }
            int start = Math.max(token.start(), at);
            if (start > at) {
                ui.mono(text, at, start, textLeft + (at - scrollColumn) * Ui.CELL_W, y, Ui.TEXT);
            }
            int end = Math.min(token.end(), last);
            ui.mono(text, start, end, textLeft + (start - scrollColumn) * Ui.CELL_W, y, color(token.kind()));
            at = end;
        }
        if (at < last) {
            ui.mono(text, at, last, textLeft + (at - scrollColumn) * Ui.CELL_W, y, Ui.TEXT);
        }
    }

    private static int color(Highlighter.Kind kind) {
        return switch (kind) {
            case KEYWORD, LITERAL, SELF -> Ui.SYN_KEYWORD;
            case CONTROL -> Ui.SYN_CONTROL;
            case BUILTIN -> Ui.SYN_BUILTIN;
            case FUNCTION, DECORATOR -> Ui.SYN_FUNCTION;
            case STRING -> Ui.SYN_STRING;
            case NUMBER -> Ui.SYN_NUMBER;
            case COMMENT -> Ui.SYN_COMMENT;
            case KEY -> Ui.SYN_KEY;
            case ERROR -> Ui.ERROR;
            case PLAIN -> Ui.TEXT;
        };
    }

    /**
     * Draws the autocomplete list, if it is showing. Called after everything else so the
     * list lies on top of neighbouring panels.
     */
    void renderPopup(Ui ui, int screenBottom) {
        if (completion == null) {
            return;
        }
        List<String> items = completion.items();
        int rows = Math.min(MAX_POPUP_ROWS, items.size());
        // Keep the chosen row in view.
        completionTop = Math.clamp(completionTop, Math.max(0, completionIndex - rows + 1), completionIndex);
        int longest = 0;
        for (String item : items) {
            longest = Math.max(longest, item.length());
        }
        int width = longest * Ui.CELL_W + 12;
        int height = rows * Ui.CELL_H + 4;
        int x = textLeft + (completion.start() - scrollColumn) * Ui.CELL_W - 5;
        int lineY = area.y() + (buffer.cursorLine() - scrollLine) * Ui.CELL_H;
        // Below the line being typed, or above it when there is no room below.
        int y = lineY + Ui.CELL_H + height <= screenBottom ? lineY + Ui.CELL_H : lineY - height;
        ui.fill(x + 2, y + 2, width, height, Ui.SHADOW);
        ui.fill(x, y, width, height, Ui.MENU_BG);
        ui.outline(x, y, width, height, Ui.BORDER);
        for (int row = 0; row < rows; row++) {
            int index = completionTop + row;
            int rowY = y + 2 + row * Ui.CELL_H;
            if (index == completionIndex) {
                ui.fill(x + 1, rowY, width - 2, Ui.CELL_H, Ui.ACTIVE_ROW);
            }
            String item = items.get(index);
            int typed = Math.min(completion.prefix().length(), item.length());
            ui.mono(item, 0, typed, x + 5, rowY, Ui.SYN_KEYWORD);
            ui.mono(item, typed, item.length(), x + 5 + typed * Ui.CELL_W, rowY, Ui.TEXT);
        }
    }

    // ---- hover hints ------------------------------------------------------------

    /** How long the mouse must rest on a name before its hint appears. The preview tool sets it to 0. */
    static long hoverDelayMs = 400;
    private static final int HOVER_COLUMNS = 62;

    /** Set by typing, clicking and scrolling: no hint until the mouse moves again. */
    private boolean hoverHidden;
    private double hoverMouseX = -1;
    private double hoverMouseY = -1;
    // The last place a hint was looked up for, so it isn't worked out again every frame.
    private int hoverLine = -1;
    private int hoverColumn = -1;
    private long hoverVersion = -1;
    private Docs.Hint hover;
    private long hoverSince;

    /**
     * Shows how to use the function the mouse is resting on, as code editors do. Called
     * after everything else is drawn, with the mouse over this view.
     */
    void renderHover(Ui ui, double mouseX, double mouseY, int screenRight, int screenBottom) {
        if (mouseX != hoverMouseX || mouseY != hoverMouseY) {
            hoverMouseX = mouseX;
            hoverMouseY = mouseY;
            hoverHidden = false;
        }
        boolean overText = mouseX >= textLeft && mouseX < area.right() - SCROLLBAR_W && area.contains(mouseX, mouseY);
        if (language != Language.PYTHON || hoverHidden || completion != null || draggingText || draggingScrollbar || !overText) {
            hoverLine = -1;
            hover = null;
            return;
        }
        int line = lineAt(mouseY);
        int column = scrollColumn + (int) Math.floor((mouseX - textLeft) / Ui.CELL_W);
        for (Checker.Problem problem : problems) {
            if (problem.line() == line && column >= problem.start() && column < problem.end()) {
                renderProblem(ui, problem, screenRight, screenBottom);
                return;
            }
        }
        boolean sameWord = hover != null && line == hoverLine && column >= hover.start() && column < hover.end();
        if (hoverVersion != buffer.version() || !(sameWord || (line == hoverLine && column == hoverColumn))) {
            Docs.Hint found = Docs.lookup(buffer, line, column);
            if (found == null || hover == null || line != hoverLine || found.start() != hover.start()) {
                hoverSince = System.currentTimeMillis();
            }
            hover = found;
            hoverLine = line;
            hoverColumn = column;
            hoverVersion = buffer.version();
        }
        if (hover == null || System.currentTimeMillis() - hoverSince < hoverDelayMs) {
            return;
        }

        // What to write: each meaning's "how to call it" line, then what it does.
        List<String> rows = new ArrayList<>();
        List<Boolean> isSignature = new ArrayList<>();
        for (Docs.Entry entry : hover.entries()) {
            if (!rows.isEmpty()) {
                rows.add("");
                isSignature.add(false);
            }
            for (String row : Docs.wrap(entry.signature(), HOVER_COLUMNS)) {
                rows.add(row);
                isSignature.add(true);
            }
            for (String row : Docs.wrap(entry.doc(), HOVER_COLUMNS)) {
                rows.add(row);
                isSignature.add(false);
            }
        }
        int longest = 0;
        for (String row : rows) {
            longest = Math.max(longest, row.length());
        }
        int width = longest * Ui.CELL_W + 12;
        int height = rows.size() * Ui.CELL_H + 6;
        int wordX = textLeft + (hover.start() - scrollColumn) * Ui.CELL_W - 5;
        int x = Math.max(2, Math.min(wordX, screenRight - width - 4));
        int lineY = area.y() + (hoverLine - scrollLine) * Ui.CELL_H;
        // Below the line, or above it when there is no room below.
        int y = lineY + Ui.CELL_H + 2 + height <= screenBottom ? lineY + Ui.CELL_H + 2 : Math.max(2, lineY - height - 2);
        ui.fill(x + 2, y + 2, width, height, Ui.SHADOW);
        ui.fill(x, y, width, height, Ui.MENU_BG);
        ui.outline(x, y, width, height, Ui.BORDER);
        for (int i = 0; i < rows.size(); i++) {
            ui.mono(rows.get(i), x + 6, y + 3 + i * Ui.CELL_H, isSignature.get(i) ? Ui.SYN_FUNCTION : Ui.TEXT);
        }
    }

    /** The box saying what is wrong, under the marked code. */
    private void renderProblem(Ui ui, Checker.Problem problem, int screenRight, int screenBottom) {
        boolean error = problem.severity() == Checker.Severity.ERROR;
        List<String> rows = new ArrayList<>(Docs.wrap(problem.message(), HOVER_COLUMNS));
        int longest = 0;
        for (String row : rows) {
            longest = Math.max(longest, row.length());
        }
        String title = error ? "Error" : "Warning";
        int width = Math.max(longest * Ui.CELL_W, ui.textWidth(title)) + 12;
        int height = (rows.size() + 1) * Ui.CELL_H + 8;
        int wordX = textLeft + (problem.start() - scrollColumn) * Ui.CELL_W - 5;
        int x = Math.max(2, Math.min(wordX, screenRight - width - 4));
        int lineY = area.y() + (problem.line() - scrollLine) * Ui.CELL_H;
        int y = lineY + Ui.CELL_H + 2 + height <= screenBottom ? lineY + Ui.CELL_H + 2 : Math.max(2, lineY - height - 2);
        ui.fill(x + 2, y + 2, width, height, Ui.SHADOW);
        ui.fill(x, y, width, height, Ui.MENU_BG);
        ui.outline(x, y, width, height, error ? Ui.ERROR : WARNING);
        ui.text(title, x + 6, y + 4, error ? Ui.ERROR : WARNING);
        for (int i = 0; i < rows.size(); i++) {
            ui.mono(rows.get(i), x + 6, y + 5 + (i + 1) * Ui.CELL_H, Ui.TEXT);
        }
    }

    // ---- autocomplete ------------------------------------------------------------

    private void updateCompletion(boolean asked) {
        completion = language == Language.PYTHON ? Completer.complete(buffer, asked) : null;
        completionIndex = 0;
        completionTop = 0;
        completionNavigated = false;
    }

    /** Hides the autocomplete list. Returns true if it was showing. */
    boolean dismissPopup() {
        boolean showing = completion != null;
        completion = null;
        return showing;
    }

    private void acceptCompletion() {
        String item = completion.items().get(completionIndex);
        buffer.select(buffer.cursorLine(), completion.start(), buffer.cursorColumn());
        buffer.insert(item);
        completion = null;
        revealCursor = true;
    }

    // ---- mouse -------------------------------------------------------------------

    private int lineAt(double y) {
        return scrollLine + (int) Math.floor((y - area.y()) / Ui.CELL_H);
    }

    private int columnAt(double x) {
        return scrollColumn + (int) Math.round((x - textLeft) / Ui.CELL_W);
    }

    private void scrollbarTo(double y) {
        Ui.Rect thumb = verticalThumb();
        double range = Math.max(1, area.height() - thumb.height());
        double position = (y - area.y() - thumb.height() / 2.0) / range;
        scrollLine = (int) Math.round(Math.clamp(position, 0.0, 1.0) * maxScrollLine());
    }

    void mouseDown(double x, double y, boolean shift, boolean doubleClick) {
        hoverHidden = true;
        completion = null;
        if (buffer.lineCount() > visibleLines() && x >= area.right() - SCROLLBAR_W - 1) {
            draggingScrollbar = true;
            scrollbarTo(y);
        } else if (doubleClick) {
            buffer.selectWordAt(lineAt(y), columnAt(x));
        } else {
            buffer.setCursor(lineAt(y), columnAt(x), shift);
            draggingText = true;
        }
    }

    void mouseDrag(double x, double y) {
        if (draggingScrollbar) {
            scrollbarTo(y);
        } else if (draggingText) {
            buffer.setCursor(lineAt(y), columnAt(x), true);
            revealCursor = true;
        }
    }

    void mouseUp() {
        draggingText = false;
        draggingScrollbar = false;
    }

    /** The mouse wheel: three lines (or six columns) a notch. The cursor stays where it is. */
    void scroll(double vertical, double horizontal) {
        hoverHidden = true;
        scrollLine = Math.clamp(scrollLine - (int) Math.round(vertical * 3), 0, maxScrollLine());
        scrollColumn = Math.clamp(scrollColumn - (int) Math.round(horizontal * 6), 0, Math.max(0, longestLine - 1));
    }

    // ---- keyboard ----------------------------------------------------------------

    void charTyped(int codepoint) {
        hoverHidden = true;
        if (codepoint >= ' ' && codepoint != 127 && Character.isBmpCodePoint(codepoint)) {
            buffer.type((char) codepoint);
            revealCursor = true;
            updateCompletion(false);
        }
    }

    /** Handles an editing key. Returns false if the key means nothing to a code view. */
    boolean keyPressed(KeyEvent event, IdeView.Host host) {
        hoverHidden = true;
        boolean shift = event.hasShiftDown();
        boolean control = event.hasControlDown();
        int key = event.key();

        if (completion != null) {
            if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
                int size = completion.items().size();
                completionIndex = Math.floorMod(completionIndex + (key == InputConstants.KEY_DOWN ? 1 : -1), size);
                completionNavigated = true;
                return true;
            }
            boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
            if (key == InputConstants.KEY_TAB || (enter && completionNavigated)) {
                acceptCompletion();
                return true;
            }
        }
        if (control && key == KEY_SPACE) {
            updateCompletion(true);
            return true;
        }
        completion = null;
        revealCursor = true;

        // Ctrl+Up and Ctrl+Down scroll without moving the cursor.
        if (control && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            revealCursor = false;
            scrollLine = Math.clamp(scrollLine + (key == InputConstants.KEY_UP ? -1 : 1), 0, maxScrollLine());
            return true;
        }

        if (event.isSelectAll()) {
            buffer.selectAll();
        } else if (event.isCopy() || event.isCut()) {
            if (buffer.hasSelection()) {
                host.setClipboard(buffer.selectedText());
                if (event.isCut()) {
                    buffer.insert("");
                }
            }
        } else if (event.isPaste()) {
            buffer.insert(host.clipboard());
        } else if (control && key == InputConstants.KEY_Z) {
            if (shift) {
                buffer.redo();
            } else {
                buffer.undo();
            }
        } else if (control && key == InputConstants.KEY_Y) {
            buffer.redo();
        } else if (control && key == InputConstants.KEY_SLASH && language.commentMarker != null) {
            buffer.toggleComment(language.commentMarker);
        } else {
            switch (key) {
                case InputConstants.KEY_LEFT -> {
                    if (control) {
                        buffer.moveWordLeft(shift);
                    } else {
                        buffer.moveLeft(shift);
                    }
                }
                case InputConstants.KEY_RIGHT -> {
                    if (control) {
                        buffer.moveWordRight(shift);
                    } else {
                        buffer.moveRight(shift);
                    }
                }
                case InputConstants.KEY_UP -> buffer.moveLines(-1, shift);
                case InputConstants.KEY_DOWN -> buffer.moveLines(1, shift);
                case InputConstants.KEY_PAGEUP -> buffer.moveLines(-visibleLines(), shift);
                case InputConstants.KEY_PAGEDOWN -> buffer.moveLines(visibleLines(), shift);
                case InputConstants.KEY_HOME -> {
                    if (control) {
                        buffer.moveDocumentStart(shift);
                    } else {
                        buffer.moveHome(shift);
                    }
                }
                case InputConstants.KEY_END -> {
                    if (control) {
                        buffer.moveDocumentEnd(shift);
                    } else {
                        buffer.moveEnd(shift);
                    }
                }
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> buffer.newLine();
                case InputConstants.KEY_BACKSPACE -> {
                    buffer.backspace();
                    // Keep suggesting while a word is being corrected.
                    int column = buffer.cursorColumn();
                    if (column > 0 && Character.isLetterOrDigit(buffer.line(buffer.cursorLine()).charAt(column - 1))) {
                        updateCompletion(false);
                    }
                }
                case InputConstants.KEY_DELETE -> buffer.deleteForward();
                case InputConstants.KEY_TAB -> {
                    if (shift) {
                        buffer.outdent();
                    } else {
                        buffer.indent();
                    }
                }
                default -> {
                    revealCursor = false;
                    return false;
                }
            }
        }
        return true;
    }
}
