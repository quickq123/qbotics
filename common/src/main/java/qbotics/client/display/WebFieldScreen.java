package qbotics.client.display;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import qbotics.web.WebInputs;

/**
 * Using a Web Panel's form control from the world, without the close-up: right-click a text
 * field and this box opens to type in (Enter sends it); right-click a select and this lists its
 * options.
 */
public class WebFieldScreen extends Screen {
    private static final int WIDTH = 240;

    private final ClientWebPanels.WebView view;
    private final String name;
    private final @Nullable Integer node;
    private final boolean area;
    private final @Nullable List<WebInputs.Option> options;
    private final @Nullable Screen back;
    private @Nullable EditBox line;
    private @Nullable MultiLineEditBox lines;

    private WebFieldScreen(ClientWebPanels.WebView view, String name, @Nullable Integer node, boolean area,
            @Nullable List<WebInputs.Option> options, @Nullable Screen back) {
        super(Component.literal(name));
        this.view = view;
        this.name = name;
        this.node = node;
        this.area = area;
        this.options = options;
        this.back = back;
    }

    static WebFieldScreen text(ClientWebPanels.WebView view, String name, @Nullable Integer node, boolean area) {
        return new WebFieldScreen(view, name, node, area, null, null);
    }

    static WebFieldScreen choose(ClientWebPanels.WebView view, String name, @Nullable Integer node, List<WebInputs.Option> options,
            @Nullable Screen back) {
        return new WebFieldScreen(view, name, node, false, options, back);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int y = height / 2 - 40;
        if (options != null) {
            int rows = Math.min(options.size(), Math.max(1, (height - 60) / 22));
            y = Math.max(30, height / 2 - rows * 11);
            String now = view.typed.get(name);
            for (int i = 0; i < rows; i++) {
                WebInputs.Option option = options.get(i);
                String label = (option.value().equals(now) ? "> " : "") + option.label();
                addRenderableWidget(Button.builder(Component.literal(label), button -> {
                    ClientWebPanels.choose(view, name, option.value(), node);
                    onClose();
                }).bounds(left, y + i * 22, WIDTH, 20).build());
            }
            return;
        }
        String now = view.typed.getOrDefault(name, "");
        if (area) {
            lines = MultiLineEditBox.builder().setX(left).setY(y).setPlaceholder(Component.literal(""))
                    .build(font, WIDTH, 80, Component.literal(name));
            lines.setCharacterLimit(WebInputs.MAX_AREA_LENGTH);
            lines.setValue(now);
            addRenderableWidget(lines);
            setInitialFocus(lines);
            addRenderableWidget(Button.builder(Component.translatable("screen.qbotics.web.send"), button -> send())
                    .bounds(left, y + 86, WIDTH / 2 - 2, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(left + WIDTH / 2 + 2, y + 86, WIDTH / 2 - 2, 20).build());
        } else {
            line = new EditBox(font, left, y + 20, WIDTH, 20, Component.literal(name));
            line.setMaxLength(WebInputs.MAX_LENGTH);
            line.setValue(now);
            addRenderableWidget(line);
            setInitialFocus(line);
        }
    }

    private void send() {
        String text = area ? (lines == null ? "" : lines.getValue()) : (line == null ? "" : line.getValue());
        ClientWebPanels.entered(view, name, text, node);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!area && options == null && (event.key() == 257 || event.key() == 335)) {
            send();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(back);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int y = options != null ? 14 : height / 2 - 52;
        graphics.centeredText(font, Component.translatable(options != null ? "screen.qbotics.web.choose" : "screen.qbotics.web.type", name),
                width / 2, y, 0xFFFFFFFF);
    }
}
