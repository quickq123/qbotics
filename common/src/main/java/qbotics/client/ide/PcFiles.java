package qbotics.client.ide;

import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * The player's own PC's file dialogs, for the Git panel's simple mode: where to save a .zip,
 * and which .zip or folder to load. Shown off the game's thread (except on macOS, which wants
 * them on it), so a dialog left open doesn't stop the game or drop the player from a server;
 * the answer comes back on the game's thread, null if the player cancelled.
 */
final class PcFiles {
    private PcFiles() {
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static String home() {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        return (java.nio.file.Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home"))).toString()
                + java.io.File.separator;
    }

    private static void ask(java.util.function.Supplier<@Nullable String> dialog, Consumer<@Nullable Path> then) {
        Runnable run = () -> {
            String chosen;
            try {
                chosen = dialog.get();
            } catch (RuntimeException | LinkageError e) {
                chosen = null;
            }
            Path path = chosen == null || chosen.isBlank() ? null : Path.of(chosen);
            Minecraft.getInstance().execute(() -> then.accept(path));
        };
        if (mac()) {
            run.run();
        } else {
            Thread thread = new Thread(run, "qbotics-pc-files");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Where to save a .zip, starting in the player's Downloads with this name. */
    static void saveZip(String name, Consumer<@Nullable Path> then) {
        ask(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer zips = stack.mallocPointer(1).put(stack.UTF8("*.zip")).flip();
                return TinyFileDialogs.tinyfd_saveFileDialog("Save the folder as a .zip", home() + name, zips, "Zip files");
            }
        }, then);
    }

    /** A .zip to load. */
    static void openZip(Consumer<@Nullable Path> then) {
        ask(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer zips = stack.mallocPointer(1).put(stack.UTF8("*.zip")).flip();
                return TinyFileDialogs.tinyfd_openFileDialog("Load a .zip onto the computer", home(), zips, "Zip files", false);
            }
        }, then);
    }

    /** A folder to load. */
    static void openFolder(Consumer<@Nullable Path> then) {
        ask(() -> TinyFileDialogs.tinyfd_selectFolderDialog("Load a folder onto the computer", home()), then);
    }
}
