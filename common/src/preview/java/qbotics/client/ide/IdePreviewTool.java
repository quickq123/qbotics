package qbotics.client.ide;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.input.KeyEvent;
import qbotics.engine.HostFileSystem;
import qbotics.ide.Paths;
import qbotics.net.IdePayloads.FileRequest;

/**
 * A development aid: draws the editor screen to PNG files without starting Minecraft, so
 * its layout can be checked on a machine with no display. Run it with
 * {@code gradlew :common:idePreview}; the pictures are written to common/build/ide-preview.
 *
 * <p>It drives the real {@link IdeView} with sample files and simulated clicks and keys.
 * Text is drawn with Minecraft's own font bitmap, so it lines up as it does in the game.
 */
public final class IdePreviewTool {
    private static File outputFolder;

    public static void main(String[] args) throws IOException {
        outputFolder = new File(args.length > 0 ? args[0] : "build/ide-preview");
        outputFolder.mkdirs();
        ImageUi.loadFont();

        // A 1920x1080 window: two screen pixels per font pixel.
        Scene big = new Scene(960, 540, 2);
        big.view.openFile("/miner.py");
        big.frame();
        big.click(400, 120, IdeView.LEFT_BUTTON);          // put the cursor in the code
        for (int i = 0; i < 3; i++) {
            big.key(InputConstants.KEY_DOWN, 1);           // shift+Down three times: select three lines
        }
        big.shot("1-editor");

        big.click(960 - 30, 6, IdeView.LEFT_BUTTON);       // the Python Scratchpad button
        big.session.scratchOutput = "hello world\n3 + 4 = 7\n";
        big.frame();
        big.click(40, 60, IdeView.RIGHT_BUTTON);           // right-click a folder in the explorer
        big.mouse(70, 80);
        big.shot("2-scratchpad-and-menu");

        big.key(InputConstants.KEY_ESCAPE, 0);
        big.view.openFile("/config.json");
        big.shot("3-json-problem");

        big.click(960 - 30, 6, IdeView.LEFT_BUTTON);       // hide the scratchpad again
        big.frame();
        big.click(100, 22, IdeView.LEFT_BUTTON);           // "+ File" in the explorer header
        for (char c : "notes.py".toCharArray()) {
            big.view.charTyped(c);
        }
        big.shot("4-new-file-dialog");
        big.key(InputConstants.KEY_ESCAPE, 0);

        big.view.openFile("/lib/tools.py");
        big.click(400, 420, IdeView.LEFT_BUTTON);          // focus the terminal
        for (char c : "run miner.py".toCharArray()) {
            big.view.charTyped(c);
        }
        big.shot("5-terminal-focused");

        // Find and replace, autocomplete, and a terminal that has been scrolled back and made taller.
        Scene tools = new Scene(960, 540, 2);
        tools.view.openFile("/miner.py");
        tools.frame();
        tools.key(72, 2);                                  // Ctrl+H: find and replace
        for (char c : "layer".toCharArray()) {
            tools.view.charTyped(c);
        }
        tools.key(InputConstants.KEY_RETURN, 0);           // on to the next match
        tools.shot("8-find-and-replace");
        tools.key(InputConstants.KEY_ESCAPE, 0);

        tools.click(400, 300, IdeView.LEFT_BUTTON);        // an empty line near the end of main()
        tools.key(InputConstants.KEY_END, 0);
        tools.key(InputConstants.KEY_RETURN, 0);
        for (char c : "miner.".toCharArray()) {
            tools.view.charTyped(c);
        }
        tools.view.mouseClicked(500, 367, IdeView.LEFT_BUTTON, false, false);   // grab the terminal's top edge...
        tools.view.mouseDragged(500, 300, IdeView.LEFT_BUTTON);                 // ...and pull it up
        tools.view.mouseReleased();
        tools.view.mouseScrolled(500, 400, 0, 4, false);   // wheel up over the terminal
        tools.shot("9-autocomplete-and-scrollback");

        // Selecting terminal text with the mouse, then copying it with Ctrl+C.
        Scene copying = new Scene(960, 540, 2);
        copying.frame();
        copying.view.mouseClicked(200, 400, IdeView.LEFT_BUTTON, false, false);
        copying.view.mouseDragged(330, 432, IdeView.LEFT_BUTTON);
        copying.view.mouseReleased();
        copying.shot("10-terminal-selection");
        copying.key(InputConstants.KEY_C, 2);
        System.out.println("Copied from the terminal: [" + copying.clipboard.replace("\n", "\\n") + "]");
        copying.click(260, 420, IdeView.RIGHT_BUTTON);
        copying.shot("11-terminal-menu");

        // A small window (854x480, Minecraft's default size): one screen pixel per font pixel.
        Scene small = new Scene(854, 480, 1);
        small.view.openFile("/miner.py");
        small.frame();
        small.click(854 - 30, 6, IdeView.LEFT_BUTTON);
        small.shot("6-small-window");

        // An empty drive, as a new player first sees it.
        Scene empty = new Scene(960, 540, 2, new SampleBackend(false));
        empty.shot("7-empty-drive");
        System.out.println("Wrote previews to " + outputFolder.getAbsolutePath());
    }

    /** One editor at one window size. */
    private static final class Scene implements IdeView.Host {
        final int width;
        final int height;
        final int pixel;
        final IdeSession session = IdeSession.of(UUID.randomUUID());
        final IdeView view;
        double mouseX = -1;
        double mouseY = -1;
        private String clipboard = "";

        Scene(int width, int height, int pixel) {
            this(width, height, pixel, new SampleBackend(true));
        }

        Scene(int width, int height, int pixel, SampleBackend backend) {
            this.width = width;
            this.height = height;
            this.pixel = pixel;
            if (backend.hasFiles) {
                sampleTerminal(session);
            } else {
                qbotics.computer.Terminal terminal = new qbotics.computer.Terminal();
                terminal.write("Qbotics computer. Type help for a list of commands.\n/ $ ");
                session.applyTerminal(terminal.full());
            }
            this.view = new IdeView(session, backend, this);
        }

        BufferedImage frame() {
            ImageUi ui = new ImageUi(width, height, pixel);
            view.render(ui, width, height, mouseX, mouseY);
            ui.done();
            return ui.image;
        }

        void mouse(double x, double y) {
            mouseX = x;
            mouseY = y;
        }

        void click(double x, double y, int button) {
            mouse(x, y);
            view.mouseClicked(x, y, button, false, false);
            view.mouseReleased();
            frame();
        }

        /** modifiers: 1 = shift, 2 = control, 4 = alt. */
        void key(int key, int modifiers) {
            view.keyPressed(new KeyEvent(key, 0, modifiers));
            frame();
        }

        void shot(String name) throws IOException {
            ImageIO.write(frame(), "png", new File(outputFolder, "ide-" + name + ".png"));
        }

        @Override
        public String clipboard() {
            return clipboard;
        }

        @Override
        public void setClipboard(String text) {
            clipboard = text;
        }

        @Override
        public void changeZoom(int by) {
        }
    }

    /** A real terminal with more output than fits, so there is scroll-back to look at. */
    private static void sampleTerminal(IdeSession session) {
        qbotics.computer.Terminal terminal = new qbotics.computer.Terminal();
        terminal.write("Qbotics computer. Type help for a list of commands.\n/ $ run count.py\n");
        for (int i = 1; i <= 30; i++) {
            terminal.write("counting " + i + "\n");
        }
        terminal.write(String.join("\n",
                "/ $ ls",
                "data/",
                "lib/",
                "config.json                         89 bytes",
                "miner.py                           718 bytes",
                "startup.py                          15 bytes",
                "/ $ run miner.py",
                "qbot-1: mining layer 1",
                "qbot-1: mining layer 2",
                "Traceback (most recent call last):",
                "  File \"/miner.py\", line 18, in dig_layer",
                "ValueError: out of torches",
                "/ $ "));
        session.applyTerminal(terminal.full());
    }

    /** Draws into an image, using Minecraft's font bitmap for text. */
    private static final class ImageUi extends Ui {
        private static BufferedImage fontSheet;
        private static final int[] ADVANCE = new int[256];

        final BufferedImage image;
        private final Graphics2D graphics;
        private final int pixel;
        private final Deque<Shape> clips = new ArrayDeque<>();

        ImageUi(int width, int height, int pixel) {
            this.pixel = pixel;
            this.image = new BufferedImage(width * pixel, height * pixel, BufferedImage.TYPE_INT_ARGB);
            this.graphics = image.createGraphics();
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(new Color(Ui.EDITOR_BG, true));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        }

        /** Minecraft's default font: a 16x16 grid of 8x8 glyphs; a glyph is as wide as its ink. */
        static void loadFont() throws IOException {
            try (InputStream in = IdePreviewTool.class.getResourceAsStream("/assets/minecraft/textures/font/ascii.png")) {
                if (in == null) {
                    throw new IOException("Minecraft's font texture is not on the classpath");
                }
                fontSheet = ImageIO.read(in);
            }
            for (int c = 0; c < 256; c++) {
                int last = -1;
                for (int column = 0; column < 8; column++) {
                    for (int row = 0; row < 8; row++) {
                        if (ink(c, column, row)) {
                            last = column;
                        }
                    }
                }
                ADVANCE[c] = last + 2;
            }
            ADVANCE[' '] = 4;
        }

        private static boolean ink(int c, int column, int row) {
            return (fontSheet.getRGB(c % 16 * 8 + column, c / 16 * 8 + row) >>> 24) > 0;
        }

        void done() {
            graphics.dispose();
        }

        @Override
        void fill(int x, int y, int width, int height, int color) {
            if (width > 0 && height > 0) {
                graphics.setColor(new Color(color, true));
                graphics.fillRect(x * pixel, y * pixel, width * pixel, height * pixel);
            }
        }

        @Override
        void text(String text, int x, int y, int color) {
            graphics.setColor(new Color(color, true));
            for (int i = 0; i < text.length(); i++) {
                int c = text.charAt(i) < 256 ? text.charAt(i) : '?';
                for (int column = 0; column < 8; column++) {
                    for (int row = 0; row < 8; row++) {
                        if (ink(c, column, row)) {
                            graphics.fillRect((x + column) * pixel, (y + row) * pixel, pixel, pixel);
                        }
                    }
                }
                x += ADVANCE[c];
            }
        }

        @Override
        int textWidth(String text) {
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                width += ADVANCE[text.charAt(i) < 256 ? text.charAt(i) : '?'];
            }
            return width;
        }

        @Override
        void clip(int x, int y, int width, int height) {
            clips.push(graphics.getClip() == null ? new java.awt.Rectangle(0, 0, image.getWidth(), image.getHeight()) : graphics.getClip());
            graphics.clipRect(x * pixel, y * pixel, Math.max(0, width) * pixel, Math.max(0, height) * pixel);
        }

        @Override
        void unclip() {
            graphics.setClip(clips.pop());
        }
    }

    /** Files held in memory, standing in for a computer's drive. */
    private static final class SampleBackend implements IdeBackend {
        final boolean hasFiles;
        private final Map<String, byte[]> files = new TreeMap<>();
        private final Set<String> folders = new TreeSet<>(Set.of("/"));

        SampleBackend(boolean withFiles) {
            hasFiles = withFiles;
            if (!withFiles) {
                return;
            }
            folders.addAll(List.of("/lib", "/data"));
            put("/miner.py", String.join("\n",
                    "\"\"\"Digs a staircase down to diamond level.\"\"\"",
                    "import time",
                    "from lib.tools import refuel, place_torch",
                    "",
                    "DEPTH = 58        # how far down to go",
                    "TORCH_EVERY = 6",
                    "",
                    "",
                    "class Miner:",
                    "    def __init__(self, name):",
                    "        self.name = name",
                    "        self.layer = 0",
                    "",
                    "    def dig_layer(self):",
                    "        self.layer += 1",
                    "        print(f\"{self.name}: mining layer {self.layer}\")",
                    "        if self.layer % TORCH_EVERY == 0 and not place_torch():",
                    "            raise ValueError('out of torches')",
                    "        return self.layer < DEPTH",
                    "",
                    "",
                    "@refuel",
                    "def main():",
                    "    miner = Miner(\"qbot-1\")",
                    "    while miner.dig_layer():",
                    "        time.sleep(0.25)",
                    "    print('done', len(range(DEPTH)), True, None)",
                    "",
                    "",
                    "main()",
                    ""));
            put("/config.json", "{\n  \"name\": \"qbot-1\",\n  \"depth\": 58,\n  \"torches\": true,\n  \"speed\": 0.25,\n  \"tools\": [\"pickaxe\", \"shovel\",]\n}\n");
            put("/lib/tools.py", "def refuel(fn):\n    return fn\n\n\ndef place_torch():\n    return True\n");
            put("/data/log.txt", "started\n");
            put("/startup.py", "print('hello')\n");
        }

        private void put(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void file(byte op, String path, byte[] data, FileCallback callback) {
            switch (op) {
                case FileRequest.LIST -> {
                    StringBuilder out = new StringBuilder();
                    for (String folder : folders) {
                        if (!folder.equals("/") && Paths.parent(folder).equals(path)) {
                            out.append("d\t0\t").append(Paths.name(folder)).append('\n');
                        }
                    }
                    files.forEach((file, bytes) -> {
                        if (Paths.parent(file).equals(path)) {
                            out.append("f\t").append(bytes.length).append('\t').append(Paths.name(file)).append('\n');
                        }
                    });
                    callback.done(0, out.toString().getBytes(StandardCharsets.UTF_8));
                }
                case FileRequest.READ -> {
                    byte[] bytes = files.get(path);
                    callback.done(bytes == null ? HostFileSystem.ENOENT : 0, bytes == null ? new byte[0] : bytes);
                }
                case FileRequest.WRITE, FileRequest.CREATE -> {
                    files.put(path, data);
                    callback.done(0, new byte[0]);
                }
                case FileRequest.MKDIR -> {
                    folders.add(path);
                    callback.done(0, new byte[0]);
                }
                default -> callback.done(HostFileSystem.EACCES, new byte[0]);
            }
        }

        @Override
        public void terminalInput(byte action, String line) {
        }

        @Override
        public void runScratch(String code, ScratchCallback callback) {
            callback.done(true, "hello world\n");
        }
    }
}
