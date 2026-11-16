package qbotics.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.block.BlockSensorBlockEntity;
import qbotics.net.BoxPayloads;

/**
 * The Block Sensor's screen: its box, grown or shrunk a face at a time (up, down, north,
 * south, west, east; only by its owner), and Show area, which outlines it in the world.
 */
public class BoxScreen extends Screen {
    private static final int WIDTH = 220;
    /** Each face: its word, which offset it moves ([minX, minY, minZ, maxX, maxY, maxZ]) and which way is "grow". */
    private static final String[] FACES = {"up", "down", "north", "south", "west", "east"};
    private static final int[] INDEX = {4, 1, 2, 5, 0, 3};
    private static final int[] GROW = {1, -1, -1, 1, -1, 1};

    private BoxPayloads.Info info;

    public BoxScreen(BoxPayloads.Info info) {
        super(Component.translatable("block.qbotics.block_sensor"));
        this.info = info;
    }

    /** The block was right-clicked, or its box changed: show how it stands now. */
    public static void show(BoxPayloads.Info info) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BoxScreen open && open.info.pos().equals(info.pos())) {
            open.info = info;
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new BoxScreen(info));
        }
    }

    /** The box with one face moved by a step (out when step is 1, in when -1); unchanged if that box can't be had. */
    private List<Integer> moved(int face, int step) {
        List<Integer> box = new ArrayList<>(info.box());
        int index = INDEX[face];
        box.set(index, box.get(index) + GROW[face] * step);
        int[] wanted = box.stream().mapToInt(Integer::intValue).toArray();
        return BlockSensorBlockEntity.problem(wanted) == null ? box : info.box();
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;
        int y = this.height / 2 - 70;
        for (int face = 0; face < FACES.length; face++) {
            int which = face;
            Button shrink = Button.builder(Component.literal("-"), b -> send(moved(which, -1))).bounds(left + 150, y + face * 22, 30, 20).build();
            Button grow = Button.builder(Component.literal("+"), b -> send(moved(which, 1))).bounds(left + 186, y + face * 22, 30, 20).build();
            shrink.active = info.mayChange() && !moved(which, -1).equals(info.box());
            grow.active = info.mayChange() && !moved(which, 1).equals(info.box());
            addRenderableWidget(shrink);
            addRenderableWidget(grow);
        }
        y += 6 * 22 + 8;
        boolean on = SensorScreen.shown(info.pos());
        addRenderableWidget(Button.builder(Component.translatable(on ? "screen.qbotics.sensor.hide_area" : "screen.qbotics.sensor.show_area"), b -> {
            SensorScreen.toggle(info.pos());
            rebuildWidgets();
        }).bounds(left, y, WIDTH, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(left + WIDTH / 2 - 40, y, 80, 20).build());
    }

    private void send(List<Integer> box) {
        if (!box.equals(info.box())) {
            Qbotics.platform().sendToServer(new BoxPayloads.Action(info.pos(), box));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - WIDTH) / 2;
        int top = this.height / 2 - 70;
        List<Integer> b = info.box();
        graphics.centeredText(this.font, this.title, this.width / 2, top - 30, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.box.size", b.get(3) - b.get(0) + 1, b.get(5) - b.get(2) + 1,
                b.get(4) - b.get(1) + 1), this.width / 2, top - 16, 0xFF80D0FF);
        // Each face as its offset from the sensor, in blocks: Top +1 is the block above it.
        for (int face = 0; face < FACES.length; face++) {
            graphics.text(this.font, Component.translatable("screen.qbotics.box." + FACES[face], String.format("%+d", b.get(INDEX[face]))),
                    left, top + face * 22 + 6, 0xFFC0C0C0, false);
        }
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.box.hint"), this.width / 2, top - 4 + 6 * 22 + 56, 0xFF909090);
        if (!info.mayChange()) {
            graphics.centeredText(this.font, Component.translatable("screen.qbotics.sensor.not_yours"), this.width / 2, top + 6 * 22 + 62, 0xFFFF7060);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
