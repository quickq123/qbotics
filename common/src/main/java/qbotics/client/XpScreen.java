package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.block.Experience;
import qbotics.net.XpPayloads;
import qbotics.network.RadioNetwork;

/**
 * The Experience Interface's screen: how much it holds, a box for a number of levels with
 * Store and Take beside it, Store All and Take All, and the lock that says who may use it.
 */
public class XpScreen extends Screen {
    private static final int WIDTH = 230;

    private XpPayloads.Info info;
    private EditBox levels;
    private String typed = "1";

    public XpScreen(XpPayloads.Info info) {
        super(Component.translatable("block.qbotics.experience_interface"));
        this.info = info;
    }

    /** The block was right-clicked, or something on its screen was pressed: show how it stands now. */
    public static void show(XpPayloads.Info info) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof XpScreen open && open.info.pos().equals(info.pos())) {
            open.info = info;
            open.typed = open.levels == null ? open.typed : open.levels.getValue();
            open.rebuildWidgets();
        } else {
            minecraft.setScreen(new XpScreen(info));
        }
    }

    private int typedLevels() {
        try {
            return Math.max(0, Integer.parseInt(levels.getValue().strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void send(int what, int amount) {
        Qbotics.platform().sendToServer(new XpPayloads.Action(info.pos(), what, amount));
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;
        int y = this.height / 2 - 20;

        levels = new EditBox(this.font, left, y, 60, 20, Component.translatable("screen.qbotics.xp.levels"));
        levels.setMaxLength(9);
        levels.setValue(typed);
        addRenderableWidget(levels);
        Button store = Button.builder(Component.translatable("screen.qbotics.xp.store"), button -> send(XpPayloads.Action.STORE, typedLevels()))
                .bounds(left + 66, y, 80, 20).build();
        Button take = Button.builder(Component.translatable("screen.qbotics.xp.take"), button -> send(XpPayloads.Action.TAKE, typedLevels()))
                .bounds(left + 150, y, 80, 20).build();
        y += 26;
        Button storeAll = Button.builder(Component.translatable("screen.qbotics.xp.store_all"),
                button -> send(XpPayloads.Action.STORE, Integer.MAX_VALUE)).bounds(left, y, 112, 20).build();
        Button takeAll = Button.builder(Component.translatable("screen.qbotics.xp.take_all"),
                button -> send(XpPayloads.Action.TAKE, Integer.MAX_VALUE)).bounds(left + 118, y, 112, 20).build();
        for (Button button : new Button[] {store, take, storeAll, takeAll}) {
            button.active = info.mayUse();
            addRenderableWidget(button);
        }
        y += 30;
        // The lock: the owner clicks to step through private, team and public.
        RadioNetwork.Access[] all = RadioNetwork.Access.values();
        RadioNetwork.Access lock = all[Math.clamp(info.lock(), 0, all.length - 1)];
        Button lockButton = Button.builder(Component.translatable("screen.qbotics.xp.lock." + lock.word),
                button -> send(XpPayloads.Action.LOCK, (lock.ordinal() + 1) % all.length)).bounds(left, y, WIDTH, 20).build();
        lockButton.active = info.mayChangeLock();
        addRenderableWidget(lockButton);
        y += 30;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(left + WIDTH / 2 - 40, y, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int top = this.height / 2 - 20;
        int centre = this.width / 2;
        graphics.centeredText(this.font, this.title, centre, top - 58, 0xFFFFFFFF);
        // "Holds 1,395 points: enough to take a new player to level 30."
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.xp.holds", String.format("%,d", info.points()),
                String.format("%,d", Experience.levelAt(info.points()))), centre, top - 42, 0xFF80FF60);
        int own = this.minecraft.player == null ? 0 : this.minecraft.player.experienceLevel;
        graphics.centeredText(this.font, Component.translatable(info.mayUse() ? "screen.qbotics.xp.you" : "screen.qbotics.xp.locked", own),
                centre, top - 28, info.mayUse() ? 0xFFC0C0C0 : 0xFFFF7060);
        graphics.text(this.font, Component.translatable("screen.qbotics.xp.levels"), (this.width - WIDTH) / 2, top - 11, 0xFFA0A0A0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
