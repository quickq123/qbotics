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
 * A storage the editor's code names, shown over the editor when it is Ctrl+clicked (design doc
 * §17.1): its containers in the order it fills them, each with how full it is, its bus's filter
 * and any strays, and what they hold between them, the most first. A click on a container shows
 * that one as a device; Esc comes back here. As it was when clicked.
 */
final class StorageLook implements Look {
    private static final int PANEL = 0xF0101418;
    private static final int ROW_HOVER = 0xFF223040;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int DIM = 0xFF9AA4AE;
    private static final int HEAD = 0xFFFFD27A;
    private static final int WARN = 0xFFFF8A65;
    private static final int BAR_BACK = 0xFF2A3440;
    private static final int BAR = 0xFF5FA8D3;
    private static final int BAR_FULL = 0xFFD36F5F;
    private static final int ROW_H = 13;
    private static final int ICON = 18;
    /** An item and its count beside it. */
    private static final int CELL_W = 46;
    private static final int ICONS_PER_ROW = 8;
    /** At most this many kinds of item are drawn; the rest are counted. */
    private static final int MOST_ICONS = 48;

    /** One container of the storage, as its row shows it. */
    private record Member(String label, String target, String kind, String address, int slots, int free, long count, @Nullable String filter,
            long strays) {
    }

    private record Row(int x, int y, int width, Member member) {
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    private final String title;
    private final List<Member> members = new ArrayList<>();
    private final List<ItemStack> held = new ArrayList<>();
    private final List<Long> heldCounts = new ArrayList<>();
    private int kinds;
    private final List<Row> rows = new ArrayList<>();
    private int width;
    private int height;

    StorageLook(JsonObject look, int width, int height) {
        List<String> names = new ArrayList<>();
        look.getAsJsonArray("group").forEach(e -> names.add(e.isJsonPrimitive() ? e.getAsString() : e.toString()));
        title = String.join(", ", names);
        for (JsonElement e : look.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            JsonObject device = m.getAsJsonObject("device");
            String name = device.has("name") ? device.get("name").getAsString() : "";
            String filter = null;
            if (m.has("filter")) {
                List<String> only = new ArrayList<>();
                m.getAsJsonObject("filter").getAsJsonArray("only").forEach(o -> only.add(o.getAsString()));
                boolean matching = m.getAsJsonObject("filter").has("matching") && m.getAsJsonObject("filter").get("matching").isJsonPrimitive()
                        && m.getAsJsonObject("filter").get("matching").getAsBoolean();
                filter = matching ? "only what it holds" : only.isEmpty() ? null : "only " + String.join(" | ", only);
            }
            members.add(new Member(name.isEmpty() ? device.get("ip").getAsString() : name,
                    device.has("target") ? device.get("target").getAsString().replace("minecraft:", "") : "",
                    device.get("kind").getAsString(), device.get("mac").getAsString(), m.get("slots").getAsInt(), m.get("free").getAsInt(),
                    m.get("count").getAsLong(), filter, m.get("strays").getAsLong()));
        }
        var items = look.getAsJsonArray("items");
        kinds = items.size();
        for (int i = 0; i < Math.min(MOST_ICONS, items.size()); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            var found = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item.get("item").getAsString()));
            if (found.isPresent()) {
                held.add(new ItemStack(found.get()));
                heldCounts.add(item.get("count").getAsLong());
            }
        }
        resize(width, height);
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    private static String shortCount(long n) {
        return n < 1000 ? Long.toString(n) : n < 10_000 ? String.format("%.1fk", n / 1000.0) : n < 1_000_000 ? (n / 1000) + "k" : (n / 1_000_000) + "M";
    }

    private String summary() {
        int slots = members.stream().mapToInt(Member::slots).sum();
        int free = members.stream().mapToInt(Member::free).sum();
        long count = members.stream().mapToLong(Member::count).sum();
        return members.size() + (members.size() == 1 ? " container, " : " containers, ") + free + " of " + slots + " slots free, "
                + String.format("%,d", count) + " items of " + kinds + (kinds == 1 ? " kind" : " kinds");
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Font font = minecraft.font;
        graphics.fill(0, 0, width, height, 0x80000000);
        // Each row: name -> block, a bar of how full, used/all slots, then its filter and strays.
        int nameWidth = 0;
        int targetWidth = 0;
        for (Member m : members) {
            nameWidth = Math.max(nameWidth, font.width(m.label()));
            targetWidth = Math.max(targetWidth, font.width(m.target()));
        }
        int barWidth = 60;
        int rowWidth = nameWidth + 4 + font.width("-> ") + targetWidth + 10 + barWidth + 8 + font.width("999/999") + 12;
        int extraWidth = 0;
        for (Member m : members) {
            extraWidth = Math.max(extraWidth, font.width(extra(m)));
        }
        rowWidth += extraWidth;
        int iconRows = (held.size() + ICONS_PER_ROW - 1) / ICONS_PER_ROW;
        String head = "storage(" + title + ")";
        String sum = summary();
        int innerWidth = Math.max(Math.max(rowWidth, ICONS_PER_ROW * CELL_W), Math.max(font.width(head), font.width(sum)));
        innerWidth = Math.min(innerWidth, width - 24);
        int innerHeight = 10 + 12 + 6 + 10 + members.size() * ROW_H + 8 + 10 + iconRows * ICON + (kinds > held.size() ? 10 : 0) + 8 + 10;
        int x = (width - innerWidth) / 2;
        int y = Math.max(8, (height - innerHeight) / 2);
        graphics.fill(x - 8, y - 8, x + innerWidth + 8, y + innerHeight + 4, PANEL);

        graphics.text(font, head, x, y, HEAD, false);
        y += 12;
        graphics.text(font, sum, x, y, TEXT, false);
        y += 16;
        graphics.text(font, "Containers, in the order it fills them (click one to see it):", x, y, DIM, false);
        y += 11;
        rows.clear();
        for (Member m : members) {
            boolean over = mouseX >= x - 2 && mouseX < x + innerWidth + 2 && mouseY >= y - 2 && mouseY < y + ROW_H - 2;
            if (over) {
                graphics.fill(x - 3, y - 2, x + innerWidth + 3, y + ROW_H - 2, ROW_HOVER);
            }
            rows.add(new Row(x - 3, y - 2, innerWidth + 6, m));
            int cx = x;
            graphics.text(font, m.label(), cx, y, TEXT, false);
            cx += nameWidth + 4;
            graphics.text(font, "->", cx, y, DIM, false);
            cx += font.width("-> ");
            graphics.text(font, m.target(), cx, y, DIM, false);
            cx += targetWidth + 10;
            int used = m.slots() - m.free();
            float share = m.slots() == 0 ? 0 : used / (float) m.slots();
            graphics.fill(cx, y + 1, cx + barWidth, y + 8, BAR_BACK);
            graphics.fill(cx, y + 1, cx + Math.round(barWidth * share), y + 8, m.free() == 0 ? BAR_FULL : BAR);
            cx += barWidth + 6;
            graphics.text(font, used + "/" + m.slots(), cx, y, m.free() == 0 ? WARN : TEXT, false);
            cx += font.width("999/999") + 8;
            String extra = extra(m);
            if (!extra.isEmpty()) {
                graphics.text(font, extra, cx, y, m.strays() > 0 ? WARN : DIM, false);
            }
            y += ROW_H;
        }
        y += 6;
        graphics.text(font, "What it holds, the most first:", x, y, DIM, false);
        y += 11;
        ItemStack hovered = null;
        for (int i = 0; i < held.size(); i++) {
            int ix = x + (i % ICONS_PER_ROW) * CELL_W;
            int iy = y + (i / ICONS_PER_ROW) * ICON;
            graphics.fill(ix, iy, ix + 17, iy + 17, BAR_BACK);
            graphics.item(held.get(i), ix, iy);
            graphics.text(font, shortCount(heldCounts.get(i)), ix + 20, iy + 5, TEXT, false);
            if (mouseX >= ix && mouseX < ix + 17 && mouseY >= iy && mouseY < iy + 17) {
                hovered = held.get(i);
            }
        }
        y += iconRows * ICON;
        if (held.isEmpty()) {
            graphics.text(font, "(nothing)", x, y, DIM, false);
            y += 10;
        } else if (kinds > held.size()) {
            graphics.text(font, "and " + (kinds - held.size()) + " more kinds", x, y + 2, DIM, false);
            y += 10;
        }
        y += 8;
        graphics.text(font, "As it was when clicked. Esc or a click elsewhere closes it.", x, y, DIM, false);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
    }

    private static String extra(Member m) {
        List<String> parts = new ArrayList<>();
        if (m.filter() != null) {
            parts.add(m.filter());
        }
        if (m.strays() > 0) {
            parts.add(m.strays() + " stray" + (m.strays() == 1 ? "" : "s"));
        }
        return String.join("   ", parts);
    }

    @Override
    public String @Nullable [] clicked(double mouseX, double mouseY) {
        for (Row row : rows) {
            if (mouseX >= row.x() && mouseX < row.x() + row.width() && mouseY >= row.y() && mouseY < row.y() + ROW_H) {
                return new String[] {row.member().kind(), row.member().address()};
            }
        }
        return null;
    }

    @Override
    public List<String> text() {
        List<String> lines = new ArrayList<>();
        lines.add("storage(" + title + ")");
        lines.add(summary());
        for (Member m : members) {
            lines.add(m.label() + " -> " + m.target() + " " + (m.slots() - m.free()) + "/" + m.slots() + " " + extra(m));
        }
        return lines;
    }
}
