package qbotics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.computer.Terminal;
import qbotics.net.TerminalInputPayload;
import qbotics.net.TerminalStatePayload;

/**
 * A computer's terminal. The server owns the screen contents; this draws them and edits
 * the line being typed locally, sending it when Enter is pressed.
 */
public class TerminalScreen extends Screen {
    private static final int CELL_WIDTH = 6;
    private static final int CELL_HEIGHT = 9;
    private static final int PADDING = 6;
    private static final int BEZEL = 4;

    private static final int COLOR_BEZEL = 0xFF3A3F47;
    private static final int COLOR_BEZEL_EDGE = 0xFF1E2126;
    private static final int COLOR_BACKGROUND = 0xFF0C1014;
    private static final int COLOR_TEXT = 0xFFD8E0E8;
    private static final int COLOR_INPUT = 0xFFFFFFFF;
    private static final int COLOR_CURSOR = 0xFF7CE07C;

    private static final int KEY_ENTER = 257;
    private static final int KEY_NUMPAD_ENTER = 335;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_UP = 265;
    private static final int KEY_DOWN = 264;
    private static final int KEY_C = 67;

    private final UUID deviceId;
    private List<String> lines;
    private int cursorX;
    private int cursorY;

    private final StringBuilder input = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private int blink;

    public TerminalScreen(TerminalStatePayload state) {
        super(Component.translatable("screen.qbotics.terminal"));
        this.deviceId = state.deviceId();
        update(state);
    }

    /** Called when the server sends this computer's screen. */
    public static void onState(TerminalStatePayload state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TerminalScreen open && open.deviceId.equals(state.deviceId())) {
            open.update(state);
        } else if (state.open()) {
            minecraft.setScreen(new TerminalScreen(state));
        }
    }

    private void update(TerminalStatePayload state) {
        this.lines = state.lines();
        this.cursorX = state.cursorX();
        this.cursorY = state.cursorY();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        blink++;
    }

    @Override
    public void removed() {
        send(TerminalInputPayload.CLOSE, "");
        super.removed();
    }

    private void send(byte action, String line) {
        Qbotics.platform().sendToServer(new TerminalInputPayload(deviceId, action, line));
    }

    // ---- drawing -------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int gridWidth = Terminal.COLS * CELL_WIDTH;
        int gridHeight = Terminal.ROWS * CELL_HEIGHT;
        int left = (this.width - gridWidth) / 2;
        int top = (this.height - gridHeight) / 2;

        int outer = PADDING + BEZEL;
        graphics.fill(left - outer - 1, top - outer - 1, left + gridWidth + outer + 1, top + gridHeight + outer + 1, COLOR_BEZEL_EDGE);
        graphics.fill(left - outer, top - outer, left + gridWidth + outer, top + gridHeight + outer, COLOR_BEZEL);
        graphics.fill(left - PADDING, top - PADDING, left + gridWidth + PADDING, top + gridHeight + PADDING, COLOR_BACKGROUND);

        for (int row = 0; row < lines.size() && row < Terminal.ROWS; row++) {
            drawText(graphics, lines.get(row), left, top, 0, row, COLOR_TEXT);
        }

        // The line being typed starts at the server's cursor (just after the prompt).
        // If it is too long for the row, show its end.
        int room = Terminal.COLS - cursorX - 1;
        String shown = input.length() <= room ? input.toString() : input.substring(input.length() - room);
        drawText(graphics, shown, left, top, cursorX, cursorY, COLOR_INPUT);

        if (blink / 6 % 2 == 0) {
            int x = left + (cursorX + shown.length()) * CELL_WIDTH;
            int y = top + cursorY * CELL_HEIGHT;
            graphics.fill(x, y + CELL_HEIGHT - 2, x + CELL_WIDTH - 1, y + CELL_HEIGHT - 1, COLOR_CURSOR);
        }
    }

    /** Minecraft's font is proportional; centring each character in a fixed cell makes a grid. */
    private void drawText(GuiGraphicsExtractor graphics, String text, int left, int top, int column, int row, int color) {
        int y = top + row * CELL_HEIGHT;
        for (int i = 0; i < text.length() && column + i < Terminal.COLS; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                continue;
            }
            String glyph = String.valueOf(c);
            int x = left + (column + i) * CELL_WIDTH + (CELL_WIDTH - this.font.width(glyph)) / 2;
            graphics.text(this.font, glyph, x, y, color, false);
        }
    }

    // ---- typing --------------------------------------------------------------

    @Override
    public boolean charTyped(CharacterEvent event) {
        int codepoint = event.codepoint();
        if (codepoint >= ' ' && codepoint != 127 && Character.isBmpCodePoint(codepoint)) {
            append(String.valueOf((char) codepoint));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == KEY_C) {
            input.setLength(0);
            send(TerminalInputPayload.INTERRUPT, "");
            return true;
        }
        if (event.isPaste()) {
            paste(this.minecraft.keyboardHandler.getClipboard());
            return true;
        }
        switch (event.key()) {
            case KEY_ENTER, KEY_NUMPAD_ENTER -> enter();
            case KEY_BACKSPACE -> {
                if (!input.isEmpty()) {
                    input.setLength(input.length() - 1);
                }
            }
            case KEY_UP -> recall(-1);
            case KEY_DOWN -> recall(1);
            default -> {
                // Escape closes the screen; everything else is typing, not a game key.
                return super.keyPressed(event);
            }
        }
        return true;
    }

    private void append(String text) {
        int room = TerminalInputPayload.MAX_LINE_LENGTH - input.length();
        input.append(text, 0, Math.min(room, text.length()));
    }

    private void enter() {
        String line = input.toString();
        input.setLength(0);
        if (!line.isBlank() && (history.isEmpty() || !history.getLast().equals(line))) {
            history.add(line);
        }
        historyIndex = history.size();
        send(TerminalInputPayload.LINE, line);
    }

    private void recall(int direction) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.clamp(historyIndex + direction, 0, history.size());
        input.setLength(0);
        if (historyIndex < history.size()) {
            input.append(history.get(historyIndex));
        }
    }

    /** Pasted text may hold several lines: each complete line is entered. */
    private void paste(String clipboard) {
        String[] parts = clipboard.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            append(parts[i].replace("\t", "    "));
            if (i < parts.length - 1) {
                enter();
            }
        }
    }
}
