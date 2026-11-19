package qbotics.client.ide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * A device the editor's code names, shown over the editor when it is Ctrl+clicked (design doc
 * §17.1): the block's own screen as a player sees it, filled with what its slots held when it
 * was clicked, each slot's number as a program writes it (yellow) and the slots that take
 * nothing marked; beside it which face of the block reaches which slots. Only a picture: clicks
 * close it, and nothing moves. A screen that can't be made here (some mods' machines) is drawn
 * as plain slots in the same places.
 */
final class DeviceLook implements Look {
    private static final int LABEL = 0xFFFFE85C;
    private static final int TAKES_NOTHING = 0x60FF3030;
    private static final int PANEL = 0xF0101418;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int DIM = 0xFF9AA4AE;
    private static final int HEAD = 0xFFFFD27A;
    /** Never sent to the server: the menu exists only in this picture. */
    private static final int CONTAINER_ID = 1000;

    /** One slot of the block, where the screen (or the plain grid) has it. */
    private record Spot(int index, int x, int y, int label, ItemStack stack, boolean accepts) {
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    /** Opened from a storage's list: Esc goes back to it. */
    private final boolean fromStorage;
    private final List<Spot> spots = new ArrayList<>();
    private final List<String[]> lines = new ArrayList<>(); // {text, colour}
    private @Nullable AbstractContainerScreen<?> screen;
    /** The plain grid's size, when there is no screen. */
    private int gridWidth;
    private int gridHeight;
    private int left;
    private int top;
    private int width;
    private int height;

    DeviceLook(JsonObject look, int width, int height, boolean fromStorage) {
        this.fromStorage = fromStorage;
        JsonObject layout = look.has("screen") ? look.getAsJsonObject("screen") : null;
        if (layout != null) {
            for (JsonElement e : layout.getAsJsonArray("slots")) {
                JsonObject s = e.getAsJsonObject();
                spots.add(new Spot(s.get("i").getAsInt(), s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("slot").getAsInt(), stack(s),
                        s.get("accepts").getAsBoolean()));
            }
            screen = makeScreen(layout, look);
        } else if (look.has("slots")) {
            // No screen: its slots as a plain grid, nine a row.
            JsonArray slots = look.getAsJsonArray("slots");
            for (int i = 0; i < slots.size(); i++) {
                JsonObject s = slots.get(i).getAsJsonObject();
                spots.add(new Spot(i, 8 + (i % 9) * 18, 18 + (i / 9) * 18, s.get("slot").getAsInt(), stack(s), s.get("accepts").getAsBoolean()));
            }
        }
        int maxX = 0;
        int maxY = 0;
        for (Spot spot : spots) {
            maxX = Math.max(maxX, spot.x() + 16);
            maxY = Math.max(maxY, spot.y() + 16);
        }
        gridWidth = Math.max(176, maxX + 8);
        gridHeight = Math.max(40, maxY + 8);
        describe(look, layout);
        resize(width, height);
    }

    private static ItemStack stack(JsonObject slot) {
        if (!slot.has("item") || slot.get("item").isJsonNull()) {
            return ItemStack.EMPTY;
        }
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(slot.get("item").getAsString()));
        return item.map(i -> new ItemStack(i, Math.max(1, slot.get("count").getAsInt()))).orElse(ItemStack.EMPTY);
    }

    /** The block's own screen, made here with what its slots held; null if it can't be. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable AbstractContainerScreen<?> makeScreen(JsonObject layout, JsonObject look) {
        if (!layout.has("type") || minecraft.player == null) {
            return null;
        }
        try {
            MenuType type = BuiltInRegistries.MENU.getValue(Identifier.parse(layout.get("type").getAsString()));
            var factory = type == null ? java.util.Optional.empty() : MenuScreens.getScreenFactory(type);
            if (factory.isEmpty()) {
                return null;
            }
            AbstractContainerMenu menu = type.create(CONTAINER_ID, minecraft.player.getInventory());
            if (menu == null || menu.slots.size() != layout.get("total").getAsInt()) {
                return null; // made differently here than on the server: its slots wouldn't line up
            }
            for (Spot spot : spots) {
                menu.getSlot(spot.index()).set(spot.stack().copy());
            }
            String title = look.has("title") ? look.get("title").getAsString() : "";
            Object made = ((MenuScreens.ScreenConstructor) factory.get()).create(menu, minecraft.player.getInventory(), Component.literal(title));
            return made instanceof AbstractContainerScreen<?> container ? container : null;
        } catch (RuntimeException | LinkageError e) {
            // Another mod's screen that needs what only opening it for real provides.
            Qbotics.LOGGER.debug("Couldn't draw the screen of {} in the editor", look.get("block"), e);
            return null;
        }
    }

    /** The words beside the picture: what it is, the face it works through, and what each face reaches. */
    private void describe(JsonObject look, @Nullable JsonObject layout) {
        String name = look.has("name") && !look.get("name").getAsString().isEmpty() ? look.get("name").getAsString() : look.get("ip").getAsString();
        add(name + "  (" + look.get("kind").getAsString().replace('_', ' ') + ")", HEAD);
        if (look.has("title")) {
            add("works " + look.get("title").getAsString() + "  [" + look.get("block").getAsString() + "]", TEXT);
            add("through its " + look.get("face").getAsString() + " face", TEXT);
        }
        if (look.has("slots")) {
            JsonArray slots = look.getAsJsonArray("slots");
            int filled = 0;
            for (JsonElement e : slots) {
                if (!e.getAsJsonObject().get("item").isJsonNull()) {
                    filled++;
                }
            }
            add(slots.size() + " slots, " + filled + " holding something", TEXT);
        }
        if (look.has("faces")) {
            add("", TEXT);
            add("Each face reaches:", HEAD);
            for (JsonElement e : look.getAsJsonArray("faces")) {
                JsonObject face = e.getAsJsonObject();
                int count = face.get("count").getAsInt();
                String reach = count == 0 ? "nothing"
                        : face.has("slots") ? "slot" + (face.getAsJsonArray("slots").size() == 1 ? " " : "s ") + join(face.getAsJsonArray("slots"))
                        : look.has("slots") && count == look.getAsJsonArray("slots").size() ? "all " + count + " slots"
                        : count + " slot" + (count == 1 ? "" : "s");
                add(String.format("  %-6s (%s)  %s", face.get("face").getAsString(), face.get("side").getAsString(), reach),
                        face.get("face").getAsString().equals(look.has("face") ? look.get("face").getAsString() : "") ? HEAD : TEXT);
            }
        }
        if (look.has("tanks")) {
            add("", TEXT);
            add("Tanks:", HEAD);
            for (JsonElement e : look.getAsJsonArray("tanks")) {
                add("  " + e.toString(), TEXT);
            }
        }
        if (look.has("energy")) {
            add("", TEXT);
            add("Energy: " + look.get("energy").toString(), TEXT);
        }
        if (!spots.isEmpty()) {
            add("", TEXT);
            add("Yellow: the slot numbers a program uses.", DIM);
            if (spots.stream().anyMatch(s -> !s.accepts())) {
                add("Red: slots nothing can be put in.", DIM);
            }
            if (layout != null && screen == null) {
                add("(Its screen can't be drawn here; its slots are.)", DIM);
            }
        }
        add("", TEXT);
        add(fromStorage ? "As it was when clicked. Esc or a click goes back." : "As it was when clicked. Esc or a click closes it.", DIM);
    }

    private static String join(JsonArray numbers) {
        List<String> words = new ArrayList<>();
        numbers.forEach(n -> words.add(n.getAsString()));
        return String.join(", ", words);
    }

    private void add(String text, int colour) {
        lines.add(new String[] {text, Integer.toString(colour)});
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        if (screen != null) {
            screen.init(width, height);
        }
    }

    /** Drawn over the editor. */
    @Override
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Font font = minecraft.font;
        int textWidth = 0;
        for (String[] line : lines) {
            textWidth = Math.max(textWidth, font.width(line[0]));
        }
        textWidth += 12;
        int imageWidth = screen != null ? screen.getImageWidth() : spots.isEmpty() ? 0 : gridWidth;
        int imageHeight = screen != null ? screen.getImageHeight() : spots.isEmpty() ? 0 : gridHeight;
        // The picture and the words beside it, centred together; the words below if there's no room beside.
        boolean beside = imageWidth + 8 + textWidth <= width - 8;
        int startX = beside ? (width - imageWidth - 8 - textWidth) / 2 : (width - imageWidth) / 2;
        graphics.fill(0, 0, width, height, 0x80000000);
        int shift = 0;
        if (screen != null) {
            // The game centres its screen; it is moved over to make room for the words.
            shift = screen.getLeftPos() - startX;
            graphics.pose().pushMatrix();
            graphics.pose().translate(-shift, 0);
            screen.extractBackground(graphics, mouseX + shift, mouseY, partialTick);
            screen.extractRenderState(graphics, mouseX + shift, mouseY, partialTick);
            left = screen.getLeftPos();
            top = screen.getTopPos();
        } else {
            left = startX;
            top = (height - imageHeight) / 2;
            if (!spots.isEmpty()) {
                graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xFFC6C6C6);
                graphics.outline(left, top, imageWidth, imageHeight, 0xFF555555);
                for (Spot spot : spots) {
                    graphics.fill(left + spot.x() - 1, top + spot.y() - 1, left + spot.x() + 17, top + spot.y() + 17, 0xFF8B8B8B);
                    graphics.fill(left + spot.x(), top + spot.y(), left + spot.x() + 16, top + spot.y() + 16, 0xFF6F6F6F);
                    graphics.item(spot.stack(), left + spot.x(), top + spot.y());
                    graphics.itemDecorations(font, spot.stack(), left + spot.x(), top + spot.y());
                }
            }
        }
        for (Spot spot : spots) {
            int x = left + spot.x();
            int y = top + spot.y();
            if (!spot.accepts()) {
                graphics.fill(x, y, x + 16, y + 16, TAKES_NOTHING);
            }
            qbotics.client.SlotNumbers.draw(graphics, font, spot.label(), x, y, LABEL);
        }
        if (screen != null) {
            graphics.pose().popMatrix();
        }
        int textX;
        int textY;
        if (imageWidth == 0) {
            textX = Math.max(6, (width - textWidth) / 2);
            textY = Math.max(6, (height - lines.size() * 10) / 2);
        } else if (beside) {
            textX = startX + imageWidth + 14;
            textY = Math.max(6, top);
        } else {
            textX = Math.max(6, (width - textWidth) / 2);
            textY = Math.max(6, Math.min(height - lines.size() * 10 - 6, top + imageHeight + 10));
        }
        graphics.fill(textX - 6, textY - 6, textX + textWidth - 6, textY + lines.size() * 10 + 4, PANEL);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i)[0], textX, textY + i * 10, Integer.parseInt(lines.get(i)[1]), false);
        }
    }

    /** The lines beside the picture, for the screenshots and tests. */
    @Override
    public List<String> text() {
        return lines.stream().map(l -> l[0]).toList();
    }
}
