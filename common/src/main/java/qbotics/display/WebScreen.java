package qbotics.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * What a Web Panel shows (design doc §10.3): a page of HTML and CSS, and its state, a set of
 * values the program sets (panel.state in Python). The server only keeps and sends them; each
 * player's game lays the page out with the state filled in ({{name}} in the page) and draws it.
 */
public final class WebScreen extends ScreenContent {
    /** CSS pixels across one block of the panel. */
    public static final int PIXELS_PER_BLOCK = 128;
    /** The largest page (HTML with its CSS) and state a program may give, in bytes. */
    public static final int MAX_PAGE_BYTES = 128 * 1024;
    public static final int MAX_STATE_BYTES = 32 * 1024;

    private String html = "";
    private JsonObject state = new JsonObject();
    /** The version at which the page itself last changed (not only its state). */
    private long pageVersion = 1;
    /** Pictures the page shows as image:name (Qbot snapshots), and when they last changed. */
    private final java.util.LinkedHashMap<String, qbotics.camera.Picture> images = new java.util.LinkedHashMap<>();
    private long imagesVersion = 1;
    public static final int MAX_IMAGES = 8;

    public synchronized boolean imagesChangedSince(long version) {
        return imagesVersion > version;
    }

    public synchronized java.util.Map<String, qbotics.camera.Picture> images() {
        return new java.util.LinkedHashMap<>(images);
    }

    @Override
    protected void relayout() {
        // The page lays itself out for the new size in each player's game.
    }

    public synchronized String html() {
        return html;
    }

    public synchronized String stateJson() {
        return state.toString();
    }

    /** True if the page itself has changed since a player was sent this version. */
    public synchronized boolean pageChangedSince(long version) {
        return pageVersion > version;
    }

    public synchronized int pixelWidth() {
        return widthBlocks * PIXELS_PER_BLOCK;
    }

    public synchronized int pixelHeight() {
        return heightBlocks * PIXELS_PER_BLOCK;
    }

    @Override
    public synchronized JsonElement op(String method, JsonArray args) {
        switch (method) {
            case "size" -> {
                JsonArray size = new JsonArray();
                size.add(pixelWidth());
                size.add(pixelHeight());
                return size;
            }
            case "page" -> {
                String page = stringArg(args, 0);
                if (page.getBytes(StandardCharsets.UTF_8).length > MAX_PAGE_BYTES) {
                    throw new IllegalArgumentException("That page is too large: a web panel takes up to " + MAX_PAGE_BYTES / 1024 + " KB.");
                }
                html = page;
                version++;
                pageVersion = version;
                return JsonNull.INSTANCE;
            }
            case "state" -> {
                // ["state", {name: value, ...}]: these values change; a value of None removes one.
                JsonElement changes = arg(args, 0);
                if (changes == null || !changes.isJsonObject()) {
                    throw new IllegalArgumentException("The state is a dict of names and values.");
                }
                JsonObject next = state.deepCopy();
                for (Map.Entry<String, JsonElement> change : changes.getAsJsonObject().entrySet()) {
                    if (change.getValue().isJsonNull()) {
                        next.remove(change.getKey());
                    } else {
                        next.add(change.getKey(), change.getValue());
                    }
                }
                if (next.toString().getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
                    throw new IllegalArgumentException("The panel's state is too large: it holds up to " + MAX_STATE_BYTES / 1024 + " KB.");
                }
                if (!next.equals(state)) {
                    state = next;
                    version++;
                }
                return JsonNull.INSTANCE;
            }
            case "image" -> {
                // ["image", name, picture number or None]
                String name = stringArg(args, 0);
                if (name.isEmpty() || name.length() > 32) {
                    throw new IllegalArgumentException("A picture's name is 1 to 32 characters.");
                }
                JsonElement number = arg(args, 1);
                if (number == null || number.isJsonNull()) {
                    images.remove(name);
                } else {
                    qbotics.camera.Picture picture = qbotics.camera.Camera.get(intArg(args, 1, "The picture"));
                    if (picture == null) {
                        throw new IllegalArgumentException("That picture is no longer kept: take it again.");
                    }
                    if (!images.containsKey(name) && images.size() >= MAX_IMAGES) {
                        throw new IllegalArgumentException("A web panel holds up to " + MAX_IMAGES + " pictures; take one off with image(name, None).");
                    }
                    images.put(name, picture);
                }
                version++;
                imagesVersion = version;
                return JsonNull.INSTANCE;
            }
            case "get_state" -> {
                return state.deepCopy();
            }
            case "clear" -> {
                html = "";
                state = new JsonObject();
                images.clear();
                version++;
                pageVersion = version;
                imagesVersion = version;
                return JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("A web panel can't " + method + ".");
        }
    }

    @Override
    public synchronized JsonObject save() {
        JsonObject saved = new JsonObject();
        saved.add("html", new JsonPrimitive(html));
        saved.add("state", state.deepCopy());
        return saved;
    }

    @Override
    public synchronized void load(JsonObject saved) {
        html = saved.has("html") ? saved.get("html").getAsString() : "";
        state = saved.has("state") && saved.get("state").isJsonObject() ? saved.getAsJsonObject("state") : new JsonObject();
        version++;
        pageVersion = version;
    }

    /** For tests: the state as JSON text read back. */
    public synchronized JsonObject state() {
        return JsonParser.parseString(state.toString()).getAsJsonObject();
    }
}
