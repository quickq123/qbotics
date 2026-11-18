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
    /** Sharp screen fonts (true) or Minecraft's pixel font; Ctrl+P switches. Shared by all computers. */
    private static boolean crisp = !Boolean.getBoolean("qbotics.idePixelFont");

    private final IdeView view;
    /**
     * What was Ctrl+clicked in the code, shown over the editor until a click or Esc: a device, or
     * a storage with one of its containers opened over it (Esc goes back a step).
     */
    private final java.util.ArrayDeque<Look> looks = new java.util.ArrayDeque<>();
    /** Editor units to Minecraft GUI units. */
    private float scale = 1;

    public IdeScreen(IdeSession session, IdeBackend backend) {
        super(Component.translatable("screen.qbotics.terminal"));
        this.view = new IdeView(session, backend, this);
    }

    IdeSession session() {
        return view.session();
    }

    /** Lists the open folders again (the files changed outside the editor). */
    void filesSwitched() {
        view.filesSwitched();
    }

    void reloadFiles() {
        view.reloadFiles();
    }

    void openFile(String path) {
        view.openFile(path);
    }

    /** Brings the line the program paused on into view. */
    void showLine(String path, int line) {
        view.showLine(path, line);
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
        boolean looking = !looks.isEmpty();
        view.render(new GameUi(graphics, this.font, pixelSize(), crisp),
                (int) Math.ceil(this.width / scale), (int) Math.ceil(this.height / scale), looking ? -1 : mouseX / scale, looking ? -1 : mouseY / scale);
        graphics.pose().popMatrix();
        if (looking) {
            looks.peek().render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void showDevice(com.google.gson.JsonObject device) {
        // A device opened from a storage's or a filter's list goes over it; anything else starts afresh.
        boolean fromList = !looks.isEmpty() && (looks.peek() instanceof StorageLook || looks.peek() instanceof FilterLook);
        if (!fromList) {
            looks.clear();
        }
        String kind = device.has("kind") ? device.get("kind").getAsString() : "";
        looks.push(kind.equals("storage") ? new StorageLook(device, this.width, this.height)
                : kind.equals("filter") ? new FilterLook(device, this.width, this.height)
                : new DeviceLook(device, this.width, this.height, fromList));
    }

    /** Whether a device or storage is shown over the editor (its items' tooltips then show names and tags). */
    public boolean looking() {
        return !looks.isEmpty();
    }

    /** Where the first item of what is shown over the editor was drawn, or null; for the screenshots. */
    public int[] firstItemForShots() {
        return looks.isEmpty() ? null : looks.peek().firstItem();
    }

    /** What the device shown over the editor says beside its picture, or null; for the screenshots. */
    public java.util.List<String> deviceTextForShots() {
        return looks.isEmpty() ? null : looks.peek().text();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        for (Look look : looks) {
            look.resize(width, height);
        }
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

    /** For the screenshot run: devices the editor knows of, as if from the computer's network. */
    public void devicesForShots(java.util.List<qbotics.ide.Completer.Device> devices) {
        session().devices = java.util.List.copyOf(devices);
    }

    /** For the screenshot run: types at the end of the open file (its suggestions show as for a player). */
    public void closeLooksForShots() {
        looks.clear();
    }

    public void typeForShots(String text) {
        IdeSession.Tab tab = session().active();
        if (tab != null && tab.view != null) {
            tab.view.typeAtEndForShots(text);
        }
    }

    /** For the screenshot run: the Chat panel, sending a message to the AI or the game's chat. */
    public void chatForShots(boolean toAi, String text) {
        view.chatForShots(toAi, text);
    }

    /** For the screenshot run: drags one file or folder in the explorer over another; over null drops it. */
    public void dragForShots(String from, String over) {
        view.dragForShots(from, over);
    }

    /** For the screenshot run: this many window pixels per editor unit. */
    public void setZoomForShots(int pixels) {
        zoom = pixels;
    }

    @Override
    public void sendChat(String text) {
        if (this.minecraft.player == null) {
            return;
        }
        this.minecraft.gui.getChat().addRecentChat(text);
        // As the chat box does: a /command, or a message (which may be "@ai ...", for the agent).
        if (text.startsWith("/")) {
            this.minecraft.player.connection.sendCommand(text.substring(1));
        } else {
            this.minecraft.player.connection.sendChat(text);
        }
    }

    @Override
    public void toggleFont() {
        crisp = !crisp;
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
        if (!looks.isEmpty()) {
            return true;
        }
        view.charTyped(event.codepoint());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!looks.isEmpty()) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                looks.pop();
            }
            return true; // only a picture: keys don't reach the editor under it
        }
        if (!view.keyPressed(event)) {
            this.onClose();
        }
        return true; // nothing typed here should also trigger a game key
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!looks.isEmpty()) {
            String[] opens = looks.peek().clicked(event.x(), event.y());
            if (opens != null) {
                view.lookAtDevice(opens[0], opens[1]); // a storage's container: shown over the list
            } else {
                looks.pop(); // a click closes the picture (or goes back a step), and does nothing else
            }
            return true;
        }
        view.mouseClicked(event.x() / scale, event.y() / scale, event.button(), event.hasShiftDown(), doubleClick, event.hasControlDown());
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
        if (!looks.isEmpty()) {
            return true;
        }
        view.mouseScrolled(x / scale, y / scale, scrollX, scrollY, this.minecraft.hasShiftDown());
        return true;
    }
}
