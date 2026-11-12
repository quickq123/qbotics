package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import qbotics.Qbotics;
import qbotics.net.GitPayloads;

/**
 * Where a player gives the server their GitHub token, for git push (design doc §17.5). It
 * says first where the token will be kept and who could read it. Opened by /qbotics git token.
 */
public class GitTokenScreen extends Screen {
    private static final int WIDTH = 300;

    private final GitPayloads.OpenToken info;
    private EditBox tokenBox;

    public GitTokenScreen(GitPayloads.OpenToken info) {
        super(Component.translatable("screen.qbotics.git_token"));
        this.info = info;
    }

    public static void show(GitPayloads.OpenToken info) {
        Minecraft.getInstance().setScreen(new GitTokenScreen(info));
    }

    private int top() {
        return Math.max(20, this.height / 2 - 70);
    }

    @Override
    protected void init() {
        int left = (this.width - WIDTH) / 2;
        int y = top() + 84;
        tokenBox = new EditBox(this.font, left, y, WIDTH, 20, Component.translatable("screen.qbotics.git_token.box"));
        tokenBox.setMaxLength(200);
        tokenBox.setHint(Component.translatable("screen.qbotics.git_token.box"));
        addRenderableWidget(tokenBox);
        y += 26;
        int third = (WIDTH - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("screen.qbotics.git_token.save"), button -> {
            if (!tokenBox.getValue().isBlank()) {
                Qbotics.platform().sendToServer(new GitPayloads.SetToken(tokenBox.getValue().strip()));
                onClose();
            }
        }).bounds(left, y, third, 20).build());
        Button forget = Button.builder(Component.translatable("screen.qbotics.git_token.forget"), button -> {
            Qbotics.platform().sendToServer(new GitPayloads.SetToken(""));
            onClose();
        }).bounds(left + third + 4, y, third, 20).build();
        forget.active = info.hasToken();
        addRenderableWidget(forget);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 2 * (third + 4), y, third, 20).build());
        setInitialFocus(tokenBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int y = top();
        graphics.centeredText(this.font, Component.translatable("screen.qbotics.git_token.title"), this.width / 2, y, 0xFFFFFFFF);
        y += 16;
        for (int line = 1; line <= 4; line++) {
            graphics.centeredText(this.font, Component.translatable("screen.qbotics.git_token.warning" + line), this.width / 2, y,
                    line == 1 ? 0xFFFFC040 : 0xFFC0C0C0);
            y += 12;
        }
        graphics.centeredText(this.font, Component.translatable(info.hasToken() ? "screen.qbotics.git_token.has" : "screen.qbotics.git_token.none"),
                this.width / 2, y + 4, 0xFFA0A0A0);
        if (!info.pushAllowed()) {
            graphics.centeredText(this.font, Component.translatable("screen.qbotics.git_token.no_push"), this.width / 2, top() + 140, 0xFFFF8080);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
