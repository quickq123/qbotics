package qbotics.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.Set;

/**
 * The editor's Dry run (Shift+F5; design doc §17.1): the program runs, reading the world as it
 * likes, but changes nothing. A move() is only tried: the server works out how many would move
 * and the program prints it ("[dry run] 32 coal: storage_1 -> oven (top)") and carries on as if
 * they had. Anything else that would change the world (pressing a screen's button, a Qbot
 * stepping, writing a file) stops the program there, saying what it was about to do.
 */
public final class DryRun {
    private DryRun() {
    }

    /** Calls a dry run lets through as they are, beyond the reads: opening and reading a screen changes nothing. */
    private static final Set<String> HARMLESS = Set.of("screen_open", "screen_contents", "screen_slots", "screen_cost", "screen_close",
            "inspect", "slots");

    /** What some calls would have done, in words. */
    private static final Map<String, String> DOES = Map.ofEntries(
            Map.entry("screen_put", "put items into a block's screen"),
            Map.entry("screen_take", "take items out of a block's screen"),
            Map.entry("screen_button", "press a button on a block's screen"),
            Map.entry("craft", "craft"),
            Map.entry("qbot_move", "move the Qbot"),
            Map.entry("qbot_turn", "turn the Qbot"),
            Map.entry("qbot_dig", "dig with the Qbot"),
            Map.entry("qbot_place", "place a block with the Qbot"),
            Map.entry("qbot_craft", "craft in the Qbot"),
            Map.entry("pin_set", "set a Logic Pin"),
            Map.entry("place", "place a block"),
            Map.entry("break", "break a block"),
            Map.entry("drop", "drop items"),
            Map.entry("chat_say", "write to the chat"),
            Map.entry("chat_tell", "write to a player"),
            Map.entry("chat_command", "run a command"),
            Map.entry("command_run", "run a command"));

    /** Null if a dry run may make the call (perhaps as rewrite gives it); else why not, for the program to show. */
    public static String refusal(String call) {
        String method = method(call);
        if (method.equals("move") && call.replace(" ", "").contains("\"screen\":true")) {
            // A screen can't be tried: what it makes depends on what is really laid out in it.
            return stopped("use a block's screen (put items in or take them out)");
        }
        if (method.equals("move") || HARMLESS.contains(method) || LookOnly.refusal(call) == null) {
            return null;
        }
        return stopped(DOES.getOrDefault(method, "change the world ('" + method + "')"));
    }

    private static String stopped(String does) {
        return "Dry run: stopped here, where the program would " + does + ". Everything before this ran as a dry run; "
                + "press F5 to run it for real.";
    }

    /** The call as a dry run makes it: a move is only tried (its seventh value, dry, set). */
    public static String rewrite(String call) {
        if (!method(call).equals("move")) {
            return call;
        }
        JsonArray args = JsonParser.parseString(call).getAsJsonArray();
        while (args.size() < 6) {
            args.add(JsonNull.INSTANCE);
        }
        if (args.size() == 6) {
            args.add(new JsonPrimitive(true));
        } else {
            args.set(6, new JsonPrimitive(true));
        }
        return args.toString();
    }

    private static String method(String call) {
        if (!call.startsWith("[\"")) {
            return "";
        }
        int end = call.indexOf('"', 2);
        return end < 0 ? "" : call.substring(2, end);
    }
}
