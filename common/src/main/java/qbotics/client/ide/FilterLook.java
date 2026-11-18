package qbotics.client.ide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * An item name or filter in a string of the editor's code ("coal | charcoal", "#c:ores"), shown
 * over the editor when it is Ctrl+clicked (design doc §17.1): the items it matches, and where on
 * the network those are, so a filter can be tried without running anything. A click on a place
 * shows that device; Esc comes back here. As it was when clicked.
 */
final class FilterLook implements Look {
    private static final int PANEL = 0xF0101418;
    private static final int ROW_HOVER = 0xFF223040;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int DIM = 0xFF9AA4AE;
    private static final int HEAD = 0xFFFFD27A;
    private static final int CELL_BACK = 0xFF2A3440;
    private static final int ROW_H = 13;
    private static final int ICON = 18;
    private static final int MATCHES_PER_ROW = 16;
    private static final int MOST_MATCHES = 48;
    private static final int CELL_W = 46;
    private static final int HELD_PER_ROW = 8;
    private static final int MOST_HELD = 24;

    private record Place(String label, String target, String kind, String address, long count) {
    }

    private record Row(int x, int y, int width, Place place) {
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    private final String filter;
    private final int total;
    private final List<ItemStack> matches = new ArrayList<>();
    private final List<Place> places = new ArrayList<>();
    private final List<ItemStack> held = new ArrayList<>();
    private final List<Long> heldCounts = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int width;
    private int height;

    FilterLook(JsonObject look, int width, int height) {
        filter = look.get("filter").getAsString();
        total = look.has("total") ? look.get("total").getAsInt() : 0;
        if (look.has("matches")) {
            for (JsonElement e : look.getAsJsonArray("matches")) {
                if (matches.size() < MOST_MATCHES) {
                    stack(e.getAsString()).ifPresent(matches::add);
                }
            }
        }
        for (JsonElement e : look.getAsJsonArray("where")) {
            JsonObject entry = e.getAsJsonObject();
            JsonObject device = entry.getAsJsonObject("device");
            String name = device.has("name") ? device.get("name").getAsString() : "";
            places.add(new Place(name.isEmpty() ? device.get("ip").getAsString() : name,
                    device.has("target") ? device.get("target").getAsString().replace("minecraft:", "") : device.get("kind").getAsString(),
                    device.get("kind").getAsString(), device.get("mac").getAsString(), entry.get("count").getAsLong()));
        }
        for (JsonElement e : look.getAsJsonArray("items")) {
            if (held.size() < MOST_HELD) {
                JsonObject item = e.getAsJsonObject();
                var stack = stack(item.get("item").getAsString());
                if (stack.isPresent()) {
                    held.add(stack.get());
                    heldCounts.add(item.get("count").getAsLong());
                }
            }
        }
        resize(width, height);
    }

    private static java.util.Optional<ItemStack> stack(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).map(ItemStack::new);
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    private long heldInAll() {
        return places.stream().mapToLong(Place::count).sum();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Font font = minecraft.font;
        graphics.fill(0, 0, width, height, 0x80000000);
        int nameWidth = 0;
        int targetWidth = 0;
        for (Place p : places) {
            nameWidth = Math.max(nameWidth, font.width(p.label()));
            targetWidth = Math.max(targetWidth, font.width(p.target()));
        }
        int rowWidth = nameWidth + 4 + font.width("-> ") + targetWidth + 12 + font.width("99,999");
        int matchRows = (matches.size() + MATCHES_PER_ROW - 1) / MATCHES_PER_ROW;
        int heldRows = (held.size() + HELD_PER_ROW - 1) / HELD_PER_ROW;
        String head = "\"" + filter + "\"";
        String matched = total == 0 ? "matches no item." : "matches " + total + (total == 1 ? " item" : " items")
                + (total > matches.size() ? " (the first " + matches.size() + " below):" : ":");
        String onNetwork = places.isEmpty() ? "None of them are on this network now."
                : String.format("On this network: %,d in all, in %d %s (click one to see it):", heldInAll(), places.size(),
                places.size() == 1 ? "place" : "places");
        int innerWidth = Math.max(Math.max(rowWidth, MATCHES_PER_ROW * ICON), Math.max(HELD_PER_ROW * CELL_W, font.width(onNetwork)));
        innerWidth = Math.min(Math.max(innerWidth, Math.max(font.width(head), font.width(matched))), width - 24);
        int innerHeight = 12 + 12 + matchRows * ICON + 8 + 12 + places.size() * ROW_H + 6 + heldRows * ICON + 8 + 10;
        int x = (width - innerWidth) / 2;
        int y = Math.max(8, (height - innerHeight) / 2);
        graphics.fill(x - 8, y - 8, x + innerWidth + 8, y + innerHeight + 4, PANEL);
        graphics.text(font, head, x, y, HEAD, false);
        y += 12;
        graphics.text(font, matched, x, y, TEXT, false);
        y += 12;
        ItemStack hovered = null;
        for (int i = 0; i < matches.size(); i++) {
            int ix = x + (i % MATCHES_PER_ROW) * ICON;
            int iy = y + (i / MATCHES_PER_ROW) * ICON;
            graphics.fill(ix, iy, ix + 17, iy + 17, CELL_BACK);
            graphics.item(matches.get(i), ix, iy);
            if (mouseX >= ix && mouseX < ix + 17 && mouseY >= iy && mouseY < iy + 17) {
                hovered = matches.get(i);
            }
        }
        y += matchRows * ICON + 8;
        graphics.text(font, onNetwork, x, y, places.isEmpty() ? DIM : TEXT, false);
        y += 12;
        rows.clear();
        for (Place p : places) {
            if (mouseX >= x - 3 && mouseX < x + innerWidth + 3 && mouseY >= y - 2 && mouseY < y + ROW_H - 2) {
                graphics.fill(x - 3, y - 2, x + innerWidth + 3, y + ROW_H - 2, ROW_HOVER);
            }
            rows.add(new Row(x - 3, y - 2, innerWidth + 6, p));
            int cx = x;
            graphics.text(font, p.label(), cx, y, TEXT, false);
            cx += nameWidth + 4;
            graphics.text(font, "->", cx, y, DIM, false);
            cx += font.width("-> ");
            graphics.text(font, p.target(), cx, y, DIM, false);
            cx += targetWidth + 12;
            graphics.text(font, String.format("%,d", p.count()), cx, y, TEXT, false);
            y += ROW_H;
        }
        y += 6;
        for (int i = 0; i < held.size(); i++) {
            int ix = x + (i % HELD_PER_ROW) * CELL_W;
            int iy = y + (i / HELD_PER_ROW) * ICON;
            graphics.fill(ix, iy, ix + 17, iy + 17, CELL_BACK);
            graphics.item(held.get(i), ix, iy);
            long n = heldCounts.get(i);
            graphics.text(font, n < 10_000 ? Long.toString(n) : (n / 1000) + "k", ix + 20, iy + 5, TEXT, false);
            if (mouseX >= ix && mouseX < ix + 17 && mouseY >= iy && mouseY < iy + 17) {
                hovered = held.get(i);
            }
        }
        y += heldRows * ICON + 8;
        graphics.text(font, "As it was when clicked. Esc or a click elsewhere closes it.", x, y, DIM, false);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
    }

    @Override
    public String @Nullable [] clicked(double mouseX, double mouseY) {
        for (Row row : rows) {
            if (mouseX >= row.x() && mouseX < row.x() + row.width() && mouseY >= row.y() && mouseY < row.y() + ROW_H) {
                return new String[] {row.place().kind(), row.place().address()};
            }
        }
        return null;
    }

    @Override
    public List<String> text() {
        List<String> lines = new ArrayList<>();
        lines.add("\"" + filter + "\" matches " + total);
        for (Place p : places) {
            lines.add(p.label() + " -> " + p.target() + " " + p.count());
        }
        return lines;
    }
}
