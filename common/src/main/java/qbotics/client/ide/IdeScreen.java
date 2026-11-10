package qbotics.client.ide;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shows a computer's editor ({@link IdeView}) as a Minecraft screen.
 *
 * <p>It fills the whole window and draws at the window's real resolution, not Minecraft's
 * enlarged GUI scale, so there is room for code. Ctrl+Plus and Ctrl+Minus change the size.
 */
public class IdeScreen extends Screen implements IdeView.Host {
    /** Screen pixels per font pixel; 0 = choose from the window size. Shared by all computers. */
    private static int zoom = Integer.getInteger("qbotics.ideZoom", 0);

    private final IdeView view;
    /** Editor units to Minecraft GUI units. */
    private float scale = 1;

    public IdeScreen(IdeSession session, IdeBackend backend) {
        super(Component.translatable("screen.qbotics.terminal"));
        this.view = new IdeView(session, backend, this);
    }

    IdeSession session() {
        return view.session();
    }

    void openFile(String path) {
        view.openFile(path);
    }

    /** Screen pixels per font pixel: about one for every 500 pixels of window height. */
    private int pixelSize() {
        return zoom > 0 ? zoom : Math.max(1, this.minecraft.getWindow().getHeight() / 500);
    }

    private void updateScale() {
        scale = pixelSize() / (float) this.minecraft.getWindow().getGuiScale();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        view.tick();
    }

    @Override
    public void removed() {
        view.closed();
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, Ui.EDITOR_BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        view.render(new GameUi(graphics, this.font),
                (int) Math.ceil(this.width / scale), (int) Math.ceil(this.height / scale), mouseX / scale, mouseY / scale);
        graphics.pose().popMatrix();
    }

    // ---- IdeView.Host ------------------------------------------------------------

    @Override
    public String clipboard() {
        return this.minecraft.keyboardHandler.getClipboard();
    }

    @Override
    public void setClipboard(String text) {
        this.minecraft.keyboardHandler.setClipboard(text);
    }

    @Override
    public void changeZoom(int by) {
        var window = this.minecraft.getWindow();
        // Stop before the window becomes too small to hold the layout.
        int largest = Math.max(1, Math.min(window.getWidth() / 480, window.getHeight() / 300));
        zoom = Math.clamp(pixelSize() + by, 1, largest);
    }

    // ---- input -------------------------------------------------------------------

    @Override
    public boolean charTyped(CharacterEvent event) {
        view.charTyped(event.codepoint());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!view.keyPressed(event)) {
            this.onClose();
        }
        return true; // nothing typed here should also trigger a game key
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        view.mouseClicked(event.x() / scale, event.y() / scale, event.button(), event.hasShiftDown(), doubleClick);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        view.mouseDragged(event.x() / scale, event.y() / scale, event.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        view.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        view.mouseScrolled(x / scale, y / scale, scrollY);
        return true;
    }
}
