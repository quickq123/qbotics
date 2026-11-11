package qbotics.client.display;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import qbotics.display.TextScreen;
import qbotics.net.DisplayPayloads;

/** What each screen nearby shows, as this player's game last heard from the server. */
public final class ClientDisplays {
    private ClientDisplays() {
    }

    /** A Text Monitor's screen. */
    public static final class TextView {
        public int cols;
        public int rows;
        public float scale;
        public char[][] text;
        public byte[][] colors;

        TextView(int cols, int rows, float scale) {
            this.cols = cols;
            this.rows = rows;
            this.scale = scale;
            text = new char[rows][cols];
            colors = new byte[rows][cols];
            for (int y = 0; y < rows; y++) {
                Arrays.fill(text[y], ' ');
                Arrays.fill(colors[y], (byte) 0x0F);
            }
        }
    }

    /** One widget on a Control Panel, as the server described it. */
    public record Widget(int id, String type, String label, int x, int y, int w, int h, int color,
            double min, double max, double value, String unit, int presses, double[] history) {
    }

    /** A Control Panel's screen. */
    public static final class PanelView {
        public int cols;
        public int rows;
        public List<Widget> widgets = List.of();
        /** When each button was last pressed (by its count of presses), so it can flash. */
        final Map<Integer, long[]> flashes = new HashMap<>();

        /** True for a short while after a button was pressed. */
        public boolean flashing(Widget widget) {
            long[] seen = flashes.get(widget.id());
            return seen != null && System.currentTimeMillis() - seen[1] < 250;
        }
    }

    private static final Map<BlockPos, Object> VIEWS = new HashMap<>();
    private static @Nullable Level viewsLevel;

    private static void sameLevel() {
        Level now = Minecraft.getInstance().level;
        if (now != viewsLevel) {
            VIEWS.clear();
            viewsLevel = now;
        }
    }

    public static @Nullable Object view(BlockPos origin) {
        sameLevel();
        return VIEWS.get(origin);
    }

    public static void onText(DisplayPayloads.Text payload) {
        sameLevel();
        BlockPos origin = payload.origin().immutable();
        TextView view = VIEWS.get(origin) instanceof TextView had && !payload.full()
                && had.cols == payload.cols() && had.rows == payload.rows() ? had : null;
        if (view == null) {
            view = new TextView(payload.cols(), payload.rows(), payload.scale());
            VIEWS.put(origin, view);
        }
        view.scale = payload.scale();
        for (TextScreen.Row row : payload.lines()) {
            if (row.index() < 0 || row.index() >= view.rows) {
                continue;
            }
            String text = row.text();
            for (int x = 0; x < view.cols; x++) {
                view.text[row.index()][x] = x < text.length() ? text.charAt(x) : ' ';
                view.colors[row.index()][x] = x < row.colors().length ? row.colors()[x] : 0x0F;
            }
        }
    }

    public static void onPanel(DisplayPayloads.Panel payload) {
        sameLevel();
        BlockPos origin = payload.origin().immutable();
        PanelView view = VIEWS.get(origin) instanceof PanelView had ? had : new PanelView();
        VIEWS.put(origin, view);
        try {
            JsonObject state = JsonParser.parseString(payload.json()).getAsJsonObject();
            view.cols = state.get("cols").getAsInt();
            view.rows = state.get("rows").getAsInt();
            List<Widget> widgets = new ArrayList<>();
            for (JsonElement element : state.getAsJsonArray("widgets")) {
                JsonObject w = element.getAsJsonObject();
                double[] history = new double[0];
                if (w.has("history")) {
                    var points = w.getAsJsonArray("history");
                    history = new double[points.size()];
                    for (int i = 0; i < history.length; i++) {
                        history[i] = points.get(i).getAsDouble();
                    }
                }
                Widget widget = new Widget(w.get("id").getAsInt(), w.get("type").getAsString(), w.get("label").getAsString(),
                        w.get("x").getAsInt(), w.get("y").getAsInt(), w.get("w").getAsInt(), w.get("h").getAsInt(), w.get("color").getAsInt(),
                        w.get("min").getAsDouble(), w.get("max").getAsDouble(), w.get("value").getAsDouble(), w.get("unit").getAsString(),
                        w.get("presses").getAsInt(), history);
                long[] seen = view.flashes.get(widget.id());
                if (seen == null) {
                    view.flashes.put(widget.id(), new long[] {widget.presses(), 0});
                } else if (seen[0] != widget.presses()) {
                    seen[0] = widget.presses();
                    seen[1] = System.currentTimeMillis();
                }
                widgets.add(widget);
            }
            view.widgets = widgets;
        } catch (RuntimeException e) {
            qbotics.Qbotics.LOGGER.warn("A control panel's state couldn't be read", e);
        }
    }

    public static void onOpenCloseUp(DisplayPayloads.OpenCloseUp payload) {
        Minecraft.getInstance().setScreen(new CloseUpScreen(payload.origin().immutable()));
    }
}
