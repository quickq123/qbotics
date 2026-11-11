package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a display shows (design doc §10): a Text Monitor's character grid or a Control Panel's
 * widgets. It belongs to the top-left block of its screen, and moves with the screen's identity
 * when the screen grows or shrinks.
 *
 * <p>Programs change it from their own thread (see {@link Displays#drawFast}), and the server
 * thread reads it to send to players, so every method that touches the content is synchronized.
 */
public abstract class ScreenContent {
    /** The 16 colours, by name, in the order of Minecraft's dyes. */
    public static final String[] COLOR_NAMES = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    };
    /** The same colours as the screen shows them (0xRRGGBB). */
    public static final int[] COLOR_RGB = {
        0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
        0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111,
    };

    /** Computers whose programs want this display's clicks pushed to them: computer -> event session. */
    final Map<UUID, Integer> listeners = new ConcurrentHashMap<>();
    /** Set when the screen was broken or merged into another: programs must look it up again. */
    volatile boolean gone;
    /** Goes up with every change, so the server knows what to send. */
    protected long version = 1;
    /** Goes up when the size changes: players then get everything again. */
    protected long shapeVersion = 1;
    protected int widthBlocks = 1;
    protected int heightBlocks = 1;

    /** The screen was broken or merged into another: programs holding it must look it up again. */
    public void markGone() {
        gone = true;
    }

    /** The computers whose programs are listening for this display's touches. */
    public Map<UUID, Integer> listeners() {
        return listeners;
    }

    public boolean isGone() {
        return gone;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized long shapeVersion() {
        return shapeVersion;
    }

    /** The screen is now this many blocks wide and high. */
    public synchronized void resize(int width, int height) {
        if (width != widthBlocks || height != heightBlocks) {
            widthBlocks = width;
            heightBlocks = height;
            relayout();
            shapeVersion++;
            version++;
        }
    }

    /** Fits the content to a new size. Called with the lock held. */
    protected abstract void relayout();

    /**
     * Carries out one drawing call from a program: the method name and its arguments as the
     * program sent them. Returns the answer; throws IllegalArgumentException with a message for
     * the program when the call is wrong.
     */
    public abstract JsonElement op(String method, JsonArray args);

    /** Everything it shows, for saving with the world. */
    public abstract JsonObject save();

    public abstract void load(JsonObject saved);

    /** The colour a program named: a name ("red"), a number 0-15, or null for "leave it". */
    static int color(JsonElement value, int fallback) {
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            int number = value.getAsInt();
            if (number < 0 || number > 15) {
                throw new IllegalArgumentException("Colours are numbered 0 to 15; there is no colour " + number + ".");
            }
            return number;
        }
        String name = value.getAsString().toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace("grey", "gray");
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("There is no colour called " + value.getAsString() + ". The colours are: "
                + String.join(", ", COLOR_NAMES) + ".");
    }

    static JsonElement arg(JsonArray args, int index) {
        return index < args.size() ? args.get(index) : null;
    }

    static int intArg(JsonArray args, int index, String what) {
        JsonElement value = arg(args, index);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(what + " must be a whole number.");
        }
        return value.getAsInt();
    }

    static String stringArg(JsonArray args, int index) {
        JsonElement value = arg(args, index);
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /** Text a screen can show: control characters become spaces. */
    static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c < 32 && c != '\n' || c == 127 || Character.isSurrogate(c) ? ' ' : c);
        }
        return out.toString();
    }
}
