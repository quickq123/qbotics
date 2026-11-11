package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.net.ProbePayloads;
import qbotics.network.Addresses;

/**
 * The Network Probe's pop-up: one device's name and addresses. Clicking a line copies its
 * value, ready to paste into a program with Ctrl+V. The box at the top names the device.
 */
public class ProbeScreen extends Screen {
    private static final int WIDTH = 250;
    private static final int ROW = 24;

    private ProbePayloads.Info info;
    private EditBox nameBox;
    /** The line that was copied last, so it can say so. */
    private String copied = "";

    public ProbeScreen(ProbePayloads.Info info) {
        super(Component.translatable("screen.qbotics.probe"));
        this.info = info;
    }

    /** A device was probed, or the one on screen was renamed: show it. */
    public static void show(ProbePayloads.Info info) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ProbeScreen open && open.info.pos().equals(info.pos()) && open.info.side() == info.side()) {
            open.info = info;
            open.copied = "";
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new ProbeScreen(info));
        }
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;
        int rows = 4 + (info.name().isEmpty() ? 0 : 1) + (info.external().isEmpty() ? 0 : 1);
        int y = Math.max(30, (this.height - (rows + 1) * ROW) / 2);

        nameBox = new EditBox(this.font, left, y, WIDTH - 74, 20, Component.translatable("screen.qbotics.probe.name"));
        nameBox.setMaxLength(Addresses.MAX_NAME_LENGTH);
        nameBox.setValue(info.name());
        nameBox.setHint(Component.translatable("screen.qbotics.probe.name"));
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.qbotics.probe.set_name"), button -> rename())
                .bounds(left + WIDTH - 70, y, 70, 20).build());
        y += ROW + 4;

        y = copyRow(left, y, "screen.qbotics.probe.python", info.python());
        if (!info.name().isEmpty()) {
            y = copyRow(left, y, "screen.qbotics.probe.name_row", info.name());
        }
        y = copyRow(left, y, "screen.qbotics.probe.ip", info.ip());
        y = copyRow(left, y, "screen.qbotics.probe.mac", info.mac());
        if (!info.external().isEmpty()) {
            y = copyRow(left, y, "screen.qbotics.probe.external", info.external());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + WIDTH / 2 - 40, y + 4, 80, 20).build());
    }

    /** A wide button showing a label and a value; clicking it copies the value. */
    private int copyRow(int left, int y, String label, String value) {
        Component text = Component.translatable(value.equals(copied) ? "screen.qbotics.probe.copied" : label, value);
        addRenderableWidget(Button.builder(text, button -> {
            this.minecraft.keyboardHandler.setClipboard(value);
            copied = value;
            rebuildWidgets();
        }).bounds(left, y, WIDTH, 20).build());
        return y + ROW;
    }

    private void rename() {
        Qbotics.platform().sendToServer(new ProbePayloads.Rename(info.pos(), info.side(), nameBox.getValue()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = nameBox == null ? 30 : nameBox.getY();
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.probe.title", info.kind()), this.width / 2, top - 24, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.probe.hint"), this.width / 2, top - 12, 0xFFA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
