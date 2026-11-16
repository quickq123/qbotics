package qbotics.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.block.EntitySensorBlockEntity;
import qbotics.net.SensorPayloads;

/**
 * The Entity Sensor's screen: how far it sees (radius out to each side, height up and down,
 * each 1 to 20, changed by its owner) and Show area, which outlines the box it sees in the
 * world until pressed again.
 */
public class SensorScreen extends Screen {
    private static final int WIDTH = 220;
    /** Sensors whose area this player has asked to see. */
    private static final Set<BlockPos> SHOWN = ConcurrentHashMap.newKeySet();

    private SensorPayloads.Info info;

    public SensorScreen(SensorPayloads.Info info) {
        super(Component.translatable("block.qbotics.entity_sensor"));
        this.info = info;
    }

    /** For the screenshot run: outlines a sensor's area as Show area would. */
    public static void showForShot(BlockPos pos) {
        SHOWN.add(pos.immutable());
    }

    /** True while a sensor's area is to be outlined. */
    public static boolean shown(BlockPos pos) {
        return SHOWN.contains(pos);
    }

    /** The block was right-clicked, or its size changed: show how it stands now. */
    public static void show(SensorPayloads.Info info) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof SensorScreen open && open.info.pos().equals(info.pos())) {
            open.info = info;
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new SensorScreen(info));
        }
    }

    private void send(int radius, int height) {
        int r = Math.clamp(radius, 1, EntitySensorBlockEntity.MOST);
        int h = Math.clamp(height, 1, EntitySensorBlockEntity.MOST);
        Qbotics.platform().sendToServer(new SensorPayloads.Action(info.pos(), r, h));
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;
        int y = this.height / 2 - 30;
        // A row each for radius and height: -5, -1, the number (drawn), +1, +5.
        int[] steps = {-5, -1, 1, 5};
        int[] xs = {left + 80, left + 106, left + 168, left + 194};
        for (int row = 0; row < 2; row++) {
            boolean radiusRow = row == 0;
            for (int i = 0; i < steps.length; i++) {
                int step = steps[i];
                Button button = Button.builder(Component.literal(step > 0 ? "+" + step : String.valueOf(step)), b -> send(
                        info.radius() + (radiusRow ? step : 0), info.height() + (radiusRow ? 0 : step)))
                        .bounds(xs[i], y + row * 24, 24, 20).build();
                button.active = info.mayChange();
                addRenderableWidget(button);
            }
        }
        y += 54;
        boolean on = SHOWN.contains(info.pos());
        addRenderableWidget(Button.builder(Component.translatable(on ? "screen.qbotics.sensor.hide_area" : "screen.qbotics.sensor.show_area"), b -> {
            if (!SHOWN.remove(info.pos())) {
                SHOWN.add(info.pos().immutable());
            }
            rebuildWidgets();
        }).bounds(left, y, WIDTH, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(left + WIDTH / 2 - 40, y, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = (this.width - WIDTH) / 2;
        int top = this.height / 2 - 30;
        int centre = this.width / 2;
        graphics.centeredText(this.font, this.title, centre, top - 34, 0xFFFFFFFF);
        int across = info.radius() * 2 + 1;
        int tall = info.height() * 2 + 1;
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.sensor.size", across, across, tall), centre, top - 18, 0xFF80D0FF);
        graphics.text(this.font, Component.translatable("screen.qbotics.sensor.radius"), left, top + 6, 0xFFC0C0C0, false);
        graphics.text(this.font, Component.translatable("screen.qbotics.sensor.height"), left, top + 30, 0xFFC0C0C0, false);
        graphics.centeredText(this.font, Component.literal(String.valueOf(info.radius())), left + 149, top + 6, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal(String.valueOf(info.height())), left + 149, top + 30, 0xFFFFFFFF);
        if (!info.mayChange()) {
            graphics.centeredText(this.font, Component.translatable("screen.qbotics.sensor.not_yours"), centre, top + 106, 0xFFFF7060);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
