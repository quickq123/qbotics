package qbotics.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.Set;

/**
 * What a computer may do while it runs the AI helper's look-only scratchpad (design doc
 * §17.8): only calls that read. Anything that would change the world or the drive (moving
 * items, using or attacking creatures, breaking, placing, writing signs, files, the internet)
 * is refused, so the helper can explore a player's setup without asking them first.
 */
public final class LookOnly {
    private LookOnly() {
    }

    /** The world calls that only read. */
    private static final Set<String> READS = Set.of(
            "devices", "find", "self", "contents", "count", "energy", "filters", "available", "stock", "env",
            "sensor_entities", "sensor_entity", "sensor_contents", "sensor_offers", "bsensor_blocks", "bsensor_block",
            "look", "pin", "tablets", "tablet_find", "radio", "player_info",
            "repo_list", "repo_tree", "repo_read", "watch_open", "watch_poll", "watch_close",
            "qbot_info", "qbot_inspect", "qbot_at", "qbot_scan", "qbot_items", "qbot_read", "qbot_snapshot", "qbot_view");

    /** The method of a call: its first word, as ["name", ...]. */
    private static String method(String call) {
        if (!call.startsWith("[\"")) {
            return "";
        }
        int end = call.indexOf('"', 2);
        return end < 0 ? "" : call.substring(2, end);
    }

    /** Null if a call may be made look-only; else why not, for the program to show. */
    public static String refusal(String call) {
        String method = method(call);
        if (method.startsWith("game_") || READS.contains(method)) {
            return null;
        }
        if (method.equals("area") || method.equals("bsensor_box")) {
            // Reading an area is fine; setting one is a change.
            try {
                JsonArray args = JsonParser.parseString(call).getAsJsonArray();
                boolean sets = false;
                for (int i = 2; i < args.size(); i++) {
                    sets |= !args.get(i).isJsonNull();
                }
                if (!sets) {
                    return null;
                }
            } catch (RuntimeException e) {
                // unreadable: refused below
            }
        }
        return "The AI helper's look-only scratchpad can't do '" + (method.isEmpty() ? "that" : method)
                + "': it would change something. It reads only; ask the player to let it run for real.";
    }

    /** True for a file operation that changes the drive. */
    public static boolean writes(int operation) {
        return operation == HostFileSystem.WRITE || operation == HostFileSystem.APPEND || operation == HostFileSystem.MKDIR
                || operation == HostFileSystem.REMOVE || operation == HostFileSystem.RENAME;
    }
}
