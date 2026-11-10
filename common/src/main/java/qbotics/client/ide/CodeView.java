package qbotics.client.ide;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
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
    final TextBuffer buffer;
    final Language language;
    private final boolean lineNumbers;

    private int scrollLine;
    private int scrollColumn;
    /** Where the view was last drawn; needed to turn a mouse position into a text position. */
    private Ui.Rect area = new Ui.Rect(0, 0, 0, 0);
    private int textLeft;
    private boolean dragging;
    /** After an edit or cursor move, scroll so the cursor is on screen. */
    private boolean revealCursor = true;

    // Things worked out from the text, redone only when the text changes.
    private long analysedVersion = -1;
    private int[] lineStartState = new int[0];
    private JsonTools.Result jsonProblem;

    CodeView(TextBuffer buffer, Language language, boolean lineNumbers) {
        this.buffer = buffer;
        this.language = language;
        this.lineNumbers = lineNumbers;
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
        if (language == Language.PYTHON) {
            int state = Highlighter.NORMAL;
            for (int i = 0; i < buffer.lineCount(); i++) {
                lineStartState[i] = state;
                state = Highlighter.highlight(language, buffer.line(i), state).endState();
            }
        }
        jsonProblem = language == Language.JSON && !buffer.text().isBlank() ? JsonTools.check(buffer.text()) : null;
    }

    private int visibleLines() {
        return Math.max(1, area.height() / Ui.CELL_H);
    }

    private int visibleColumns() {
        return Math.max(1, (area.right() - textLeft) / Ui.CELL_W - 1);
    }

    // ---- drawing -----------------------------------------------------------------

    void render(Ui ui, Ui.Rect rect, boolean focused, boolean cursorOn) {
        analyse();
        area = rect;
        int gutter = lineNumbers ? (String.valueOf(buffer.lineCount()).length() + 2) * Ui.CELL_W + 4 : 4;
        textLeft = rect.x() + gutter;

        if (revealCursor) {
            revealCursor = false;
            scrollLine = Math.clamp(scrollLine, buffer.cursorLine() - visibleLines() + 1, buffer.cursorLine());
            scrollColumn = Math.clamp(scrollColumn, buffer.cursorColumn() - visibleColumns() + 1, buffer.cursorColumn());
        }
        scrollLine = Math.clamp(scrollLine, 0, Math.max(0, buffer.lineCount() - 1));
        scrollColumn = Math.max(0, scrollColumn);

        ui.fill(rect.x(), rect.y(), rect.width(), rect.height(), Ui.EDITOR_BG);
        ui.clip(rect.x(), rect.y(), rect.width(), rect.height());

        int lastLine = Math.min(buffer.lineCount(), scrollLine + visibleLines() + 1);
        boolean selection = buffer.hasSelection();
        for (int line = scrollLine; line < lastLine; line++) {
            int y = rect.y() + (line - scrollLine) * Ui.CELL_H;
            String text = buffer.line(line);

            if (focused && line == buffer.cursorLine() && !selection) {
                ui.fill(rect.x(), y, rect.width(), Ui.CELL_H, Ui.LINE_HIGHLIGHT);
            }
            if (selection && line >= buffer.selectionStartLine() && line <= buffer.selectionEndLine()) {
                int from = line == buffer.selectionStartLine() ? buffer.selectionStartColumn() : 0;
                // A selected line break shows as one extra selected cell.
                int to = line == buffer.selectionEndLine() ? buffer.selectionEndColumn() : text.length() + 1;
                int x0 = Math.max(textLeft, textLeft + (from - scrollColumn) * Ui.CELL_W);
                int x1 = textLeft + (to - scrollColumn) * Ui.CELL_W;
                ui.fill(x0, y, x1 - x0, Ui.CELL_H, Ui.SELECTION);
            }
            if (lineNumbers) {
                String number = String.valueOf(line + 1);
                boolean bad = jsonProblem != null && jsonProblem.line() == line + 1;
                ui.mono(number, textLeft - 4 - (number.length() + 1) * Ui.CELL_W, y,
                        bad ? Ui.ERROR : line == buffer.cursorLine() ? Ui.TEXT : Ui.TEXT_DIM);
            }
            drawLine(ui, text, line, y);
        }

        if (focused && cursorOn) {
            int x = textLeft + (buffer.cursorColumn() - scrollColumn) * Ui.CELL_W;
            int y = rect.y() + (buffer.cursorLine() - scrollLine) * Ui.CELL_H;
            if (x >= textLeft) {
                ui.fill(x - 1, y, 1, Ui.CELL_H, Ui.TEXT_BRIGHT);
            }
        }
        ui.unclip();

        // A thin scroll bar when the text is longer than the view.
        if (buffer.lineCount() > visibleLines()) {
            int track = rect.height();
            int thumb = Math.max(8, track * visibleLines() / buffer.lineCount());
            int top = (track - thumb) * scrollLine / Math.max(1, buffer.lineCount() - 1);
            ui.fill(rect.right() - 3, rect.y() + top, 3, thumb, 0x60FFFFFF);
        }
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

    // ---- mouse -------------------------------------------------------------------

    private int lineAt(double y) {
        return scrollLine + (int) Math.floor((y - area.y()) / Ui.CELL_H);
    }

    private int columnAt(double x) {
        return scrollColumn + (int) Math.round((x - textLeft) / Ui.CELL_W);
    }

    void mouseDown(double x, double y, boolean shift, boolean doubleClick) {
        if (doubleClick) {
            buffer.selectWordAt(lineAt(y), columnAt(x));
        } else {
            buffer.setCursor(lineAt(y), columnAt(x), shift);
            dragging = true;
        }
    }

    void mouseDrag(double x, double y) {
        if (dragging) {
            buffer.setCursor(lineAt(y), columnAt(x), true);
            revealCursor = true;
        }
    }

    void mouseUp() {
        dragging = false;
    }

    void scroll(double amount) {
        scrollLine = Math.clamp(scrollLine - (int) Math.round(amount * 3), 0, Math.max(0, buffer.lineCount() - 1));
    }

    // ---- keyboard ----------------------------------------------------------------

    void charTyped(int codepoint) {
        if (codepoint >= ' ' && codepoint != 127 && Character.isBmpCodePoint(codepoint)) {
            buffer.type((char) codepoint);
            revealCursor = true;
        }
    }

    /** Handles an editing key. Returns false if the key means nothing to a code view. */
    boolean keyPressed(KeyEvent event, IdeView.Host host) {
        boolean shift = event.hasShiftDown();
        boolean control = event.hasControlDown();
        revealCursor = true;

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
        } else if (control && event.key() == InputConstants.KEY_Z) {
            if (shift) {
                buffer.redo();
            } else {
                buffer.undo();
            }
        } else if (control && event.key() == InputConstants.KEY_Y) {
            buffer.redo();
        } else if (control && event.key() == InputConstants.KEY_SLASH && language.commentMarker != null) {
            buffer.toggleComment(language.commentMarker);
        } else {
            switch (event.key()) {
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
                case InputConstants.KEY_BACKSPACE -> buffer.backspace();
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
