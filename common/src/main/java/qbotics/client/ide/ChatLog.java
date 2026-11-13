package qbotics.client.ide;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The game's chat, kept for the editor's Chat panel: what arrives (players, the server, shared
 * AI answers) and what this game adds itself (the AI agent's lines). Each line is pieces of
 * text with their colours, so the panel can draw them with the editor's own fonts.
 */
public final class ChatLog {
    private ChatLog() {
    }

    /** A piece of a line in one colour (0xAARRGGBB). */
    public record Piece(String text, int color) {
    }

    private static final int MAX_LINES = 300;
    private static final List<List<Piece>> LINES = new ArrayList<>();
    private static long version;

    /** A message shown in chat. Called on the game's thread. */
    public static synchronized void add(Component message) {
        List<Piece> pieces = new ArrayList<>();
        message.visit((style, text) -> {
            if (!text.isEmpty()) {
                pieces.add(new Piece(text, colorOf(style)));
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        // A message with line breaks is several lines.
        List<Piece> line = new ArrayList<>();
        for (Piece piece : pieces) {
            String[] parts = piece.text().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    LINES.add(line);
                    line = new ArrayList<>();
                }
                if (!parts[i].isEmpty()) {
                    line.add(new Piece(parts[i], piece.color()));
                }
            }
        }
        LINES.add(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        version++;
    }

    public static synchronized List<List<Piece>> lines() {
        return new ArrayList<>(LINES);
    }

    /** Goes up with every new line, so the panel can follow the newest. */
    public static synchronized long version() {
        return version;
    }

    private static int colorOf(Style style) {
        TextColor color = style.getColor();
        return color == null ? Ui.TEXT : 0xFF000000 | color.getValue();
    }
}
