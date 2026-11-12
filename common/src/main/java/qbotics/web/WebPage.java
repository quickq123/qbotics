package qbotics.web;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One web page, laid out by litehtml running in WebAssembly (design doc §10.3; engine/web).
 * The page asks this class about fonts and images; it hands back a display list, which
 * {@link WebPainter} paints. Each panel showing a page has its own WebPage. Not thread-safe:
 * use it from one thread.
 */
public final class WebPage {
    private static final ValType I32 = ValType.I32;
    private static final ValType F32 = ValType.F32;
    /** How text is measured and drawn: smoothed, with fractional widths, as a browser does. */
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static WasmModule module;
    private static Function<Instance, Machine> machines;

    /** The engine, parsed and translated to JVM bytecode once for every page (about a second). */
    private static synchronized void loadModule() {
        if (module != null) {
            return;
        }
        try (InputStream in = WebPage.class.getResourceAsStream("web.wasm")) {
            if (in == null) {
                throw new IllegalStateException("web.wasm is missing from the mod jar");
            }
            module = Parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        machines = MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.SILENT).compile();
    }

    private final Instance instance;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction load;
    private final ExportFunction layout;
    private final ExportFunction draw;
    private final ExportFunction listAt;
    private final ExportFunction pointer;
    private final ExportFunction hit;
    private final ExportFunction hitText;
    /** The fonts the page has asked for, by number. */
    final List<Font> fonts = new ArrayList<>();
    /** Images by address: their size, for layout. */
    private final Function<String, float[]> imageSize;

    private float width;
    private float height;
    private float contentHeight;

    /** @param imageSize the width and height of an image (item:minecraft:iron_ingot), or null if unknown */
    public WebPage(Function<String, float[]> imageSize) {
        loadModule();
        this.imageSize = imageSize;
        instance = Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(hostFunctions()).build())
                .withMachineFactory(machines)
                .withStart(false)
                .build();
        instance.export("_initialize").apply();
        alloc = instance.export("web_alloc");
        free = instance.export("web_free");
        load = instance.export("web_load");
        layout = instance.export("web_layout");
        draw = instance.export("web_draw");
        listAt = instance.export("web_list");
        pointer = instance.export("web_pointer");
        hit = instance.export("web_hit");
        hitText = instance.export("web_hit_text");
    }

    /** Shows a page: its HTML, and CSS the game adds (panel defaults). Lay it out again afterwards. */
    public void load(String html, String css) {
        byte[] h = html.getBytes(StandardCharsets.UTF_8);
        byte[] c = css.getBytes(StandardCharsets.UTF_8);
        int hp = copyIn(h);
        int cp = copyIn(c);
        try {
            load.apply(hp, h.length, cp, c.length);
        } finally {
            free.apply(hp);
            free.apply(cp);
        }
    }

    /** Lays the page out for a panel of this size (CSS pixels). Returns the height of what is on it. */
    public float layout(float width, float height) {
        this.width = width;
        this.height = height;
        contentHeight = Float.intBitsToFloat((int) layout.apply(Float.floatToRawIntBits(width), Float.floatToRawIntBits(height))[0]);
        return contentHeight;
    }

    public float contentHeight() {
        return contentHeight;
    }

    /** The drawing steps for the page scrolled down by scrollY, as WebPainter reads them. */
    public ByteBuffer displayList(float scrollY) {
        int length = (int) draw.apply(Float.floatToRawIntBits(scrollY))[0];
        int at = (int) listAt.apply()[0];
        return ByteBuffer.wrap(instance.memory().readBytes(at, length)).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** The pointer moved (0), went down (1) or up (2) at a point. True if the page looks different now. */
    public boolean pointer(int kind, float x, float y) {
        return pointer.apply(kind, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y))[0] != 0;
    }

    /** What a click at a point is on, as JSON {tag, id, name, event, value}, or null if nothing a program can tell apart. */
    public String hit(float x, float y) {
        int length = (int) hit.apply(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y))[0];
        if (length == 0) {
            return null;
        }
        int at = (int) hitText.apply()[0];
        return new String(instance.memory().readBytes(at, length), StandardCharsets.UTF_8);
    }

    private int copyIn(byte[] bytes) {
        int ptr = (int) alloc.apply(Math.max(1, bytes.length))[0];
        instance.memory().write(ptr, bytes);
        return ptr;
    }

    private String readString(int ptr, int len) {
        return new String(instance.memory().readBytes(ptr, len), StandardCharsets.UTF_8);
    }

    private void writeFloats(int ptr, float... values) {
        for (int i = 0; i < values.length; i++) {
            instance.memory().writeI32(ptr + 4 * i, Float.floatToRawIntBits(values[i]));
        }
    }

    /** A CSS font-family list to one of Java's fonts: the first family it knows, else sans-serif. */
    static String javaFamily(String css) {
        for (String part : css.split(",")) {
            String name = part.strip().replace("\"", "").replace("'", "").toLowerCase(Locale.ROOT);
            switch (name) {
                case "monospace", "courier", "courier new", "consolas", "menlo" -> {
                    return Font.MONOSPACED;
                }
                case "serif", "times", "times new roman", "georgia" -> {
                    return Font.SERIF;
                }
                case "sans-serif", "arial", "helvetica", "verdana", "system-ui", "segoe ui" -> {
                    return Font.SANS_SERIF;
                }
                default -> {
                }
            }
        }
        return Font.SANS_SERIF;
    }

    private HostFunction[] hostFunctions() {
        return new HostFunction[] {
            new HostFunction("web", "font_create", FunctionType.of(List.of(I32, I32, F32, I32, I32), List.of(I32)), (inst, args) -> {
                String family = javaFamily(readString((int) args[0], (int) args[1]));
                float size = Float.intBitsToFloat((int) args[2]);
                int weight = (int) args[3];
                int style = (weight >= 600 ? Font.BOLD : Font.PLAIN) | (args[4] != 0 ? Font.ITALIC : Font.PLAIN);
                fonts.add(new Font(family, style, 1).deriveFont(Math.max(1f, size)));
                return new long[] {fonts.size() - 1};
            }),
            new HostFunction("web", "font_metrics", FunctionType.of(List.of(I32, I32), List.of()), (inst, args) -> {
                Font font = fonts.get((int) args[0]);
                var metrics = font.getLineMetrics("Xg", FRC);
                float xHeight = (float) font.createGlyphVector(FRC, "x").getVisualBounds().getHeight();
                float zero = (float) font.getStringBounds("0", FRC).getWidth();
                writeFloats((int) args[1], font.getSize2D(), metrics.getAscent() + metrics.getDescent() + metrics.getLeading(),
                        metrics.getAscent(), metrics.getDescent(), xHeight, zero);
                return null;
            }),
            new HostFunction("web", "text_width", FunctionType.of(List.of(I32, I32, I32), List.of(F32)), (inst, args) -> {
                Font font = fonts.get((int) args[0]);
                float w = (float) font.getStringBounds(readString((int) args[1], (int) args[2]), FRC).getWidth();
                return new long[] {Float.floatToRawIntBits(w)};
            }),
            new HostFunction("web", "image_size", FunctionType.of(List.of(I32, I32, I32), List.of()), (inst, args) -> {
                float[] size = imageSize.apply(readString((int) args[0], (int) args[1]));
                writeFloats((int) args[2], size == null ? 0 : size[0], size == null ? 0 : size[1]);
                return null;
            }),
            // The few system calls the C++ library links: nothing to open, and messages go nowhere.
            new HostFunction("wasi_snapshot_preview1", "fd_close", FunctionType.of(List.of(I32), List.of(I32)), (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_prestat_get", FunctionType.of(List.of(I32, I32), List.of(I32)),
                    (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_prestat_dir_name", FunctionType.of(List.of(I32, I32, I32), List.of(I32)),
                    (inst, args) -> new long[] {8}),
            new HostFunction("wasi_snapshot_preview1", "fd_write", FunctionType.of(List.of(I32, I32, I32, I32), List.of(I32)), (inst, args) -> {
                // Pretend everything was written: sum the lengths of the pieces.
                int iovs = (int) args[1];
                int total = 0;
                for (int i = 0; i < (int) args[2]; i++) {
                    total += inst.memory().readInt(iovs + 8 * i + 4);
                }
                inst.memory().writeI32((int) args[3], total);
                return new long[] {0};
            }),
            new HostFunction("wasi_snapshot_preview1", "proc_exit", FunctionType.of(List.of(I32), List.of()), (inst, args) -> {
                throw new IllegalStateException("the web engine stopped (" + args[0] + ")");
            }),
        };
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }
}
