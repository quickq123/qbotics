package qbotics.ide;

import java.util.ArrayList;
import java.util.List;

/** Drive paths as text: always absolute, "/" separated, like "/lib/tools.py". Shared by the shell and the editor. */
public final class Paths {
    private Paths() {
    }

    /** Makes a path absolute and tidy: resolves it against a folder and removes "." and "..". */
    public static String resolve(String folder, String path) {
        String full = path.startsWith("/") ? path : folder + "/" + path;
        List<String> parts = new ArrayList<>();
        for (String part : full.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
            } else {
                parts.add(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    public static String join(String folder, String name) {
        return folder.endsWith("/") ? folder + name : folder + "/" + name;
    }

    /** The folder a path is in; the root's parent is the root. */
    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /** The last part of a path. */
    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** True if the path is the folder itself or something inside it. */
    public static boolean isInside(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder.equals("/") ? "/" : folder + "/");
    }
}
