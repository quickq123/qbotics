package qbotics.devkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qbotics.devkit.Tokens.Token;
import qbotics.devkit.Tokens.Type;

/**
 * What a program needs from the world to run, read from its code without running it: the
 * devices it looks up (by name, or the only one of a kind), whether it must run on a Qbot, an
 * antenna or an Uplink, and the files and modules it expects on the drive.
 *
 * <p>It reads the calls as written. A name worked out while the program runs is reported as
 * such, never guessed.
 */
final class Needs {
    /** One thing needed, and where the code asks for it (line 0-based). */
    record Need(int line, int col, String what) {
    }

    /** A module the file imports that isn't one of the computers' own: a file of the program's, or a library. */
    record LocalImport(String name, int line, int col) {
    }

    /** The network's lookups (net.bus("store")), and how a player would name what they find. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("device", "a device");
        KINDS.put("interface", "an Interface");
        KINDS.put("bus", "an Item Serial Bus (or USB)");
        KINDS.put("usb", "a Universal Serial Bus (USB)");
        KINDS.put("fluid_interface", "a Fluid Interface");
        KINDS.put("fluid_bus", "a Fluid Serial Bus (or USB)");
        KINDS.put("energy_interface", "an Energy Interface");
        KINDS.put("energy_bus", "an Energy Serial Bus (or USB)");
        KINDS.put("experience_interface", "an Experience Interface");
        KINDS.put("player_interface", "a Player Interface");
        KINDS.put("chat_interface", "a Chat Interface");
        KINDS.put("command_interface", "a Command Interface");
        KINDS.put("entity_sensor", "an Entity Sensor");
        KINDS.put("collector", "a Collector");
        KINDS.put("block_sensor", "a Block Sensor");
        KINDS.put("monitor", "a Text Monitor");
        KINDS.put("panel", "a Control Panel");
        KINDS.put("web_panel", "a Web Panel");
        KINDS.put("tablet", "a Tablet");
        KINDS.put("programmer", "a Programmer");
        KINDS.put("drive_bay", "a Drive Bay");
        KINDS.put("pin", "a Logic Pin");
        KINDS.put("placer", "a Placer Plate");
        KINDS.put("breaker", "a Breaker Plate");
        KINDS.put("dropper", "a Dropper Plate");
    }

    private static final String LOCAL = "";

    private Needs() {
    }

    /**
     * The names that hold a network, across the program's files: net = network(), and
     * far = net.remote("100.64.0.2") (with that address). "net" always counts.
     */
    static void receivers(List<Token> t, Map<String, String> into) {
        into.putIfAbsent("net", LOCAL);
        for (int start = 0; start < t.size(); start = nextLine(t, start)) {
            if (start + 2 >= t.size() || t.get(start).type() != Type.NAME || !t.get(start + 1).is("=")) {
                continue;
            }
            int end = nextLine(t, start) - 1;
            String address = null;
            for (int i = start + 2; i + 3 <= end; i++) {
                if (t.get(i).is(".") && t.get(i + 1).is("remote") && t.get(i + 2).is("(")) {
                    Token arg = t.get(i + 3);
                    address = arg.type() == Type.STRING && arg.value() != null ? arg.value() : "?";
                }
            }
            List<String> words = new ArrayList<>();
            for (int i = start + 2; i < end; i++) {
                words.add(t.get(i).text());
            }
            String rest = String.join(" ", words);
            if (address != null) {
                into.put(t.get(start).text(), address);
            } else if (rest.equals("network ( )") || rest.equals("logistics . network ( )")) {
                into.put(t.get(start).text(), LOCAL);
            }
        }
    }

    static List<Need> of(List<Token> t, Map<String, String> receivers, Set<String> knownModules, List<LocalImport> locals) {
        List<Need> needs = new ArrayList<>();
        Map<String, Token> constants = constants(t);
        boolean networkModule = false;
        for (int i = 0; i < t.size(); i++) {
            Token token = t.get(i);
            boolean lineStart = i == 0 || t.get(i - 1).type() == Type.NEWLINE;
            // import a, b as c / from a import b
            if (lineStart && (token.is("import") || token.is("from"))) {
                for (int j = i + 1; j < t.size() && t.get(j).type() != Type.NEWLINE; j++) {
                    Token module = t.get(j);
                    boolean named = token.is("from") ? j == i + 1 : (t.get(j - 1).is("import") || t.get(j - 1).is(","));
                    if (!named || module.type() != Type.NAME) {
                        if (token.is("from") && j == i + 1 && module.is(".")) {
                            break; // from . import x: the program's own files, beside it
                        }
                        continue;
                    }
                    String name = module.text();
                    if (token.is("import") && name.equals("network")) {
                        networkModule = true;
                    }
                    switch (name) {
                        case "qbot" -> needs.add(new Need(module.line(), module.col(), "to run on a Qbot (it imports qbot)"));
                        case "requests", "git" -> needs.add(new Need(module.line(), module.col(),
                                "an Uplink on its network, for the internet (a laptop has one built in)"));
                        default -> {
                            if (!knownModules.contains(name)) {
                                locals.add(new LocalImport(name, module.line(), module.col()));
                            }
                        }
                    }
                    if (token.is("from")) {
                        break;
                    }
                }
            }
            if (token.is(".") && i + 2 < t.size() && t.get(i + 1).type() == Type.NAME && t.get(i + 2).is("(")) {
                String method = t.get(i + 1).text();
                String address = receiverAt(t, i, receivers);
                Token at = t.get(i + 1);
                if (address != null && KINDS.containsKey(method)) {
                    needs.add(new Need(at.line(), at.col(), where(address) + lookup(method, t, i + 3, constants)));
                } else if (address != null && method.equals("storage")) {
                    needs.add(new Need(at.line(), at.col(), where(address) + storage(t, i + 3)));
                } else if (method.equals("remote")) {
                    Token arg = t.get(i + 3);
                    String far = arg.type() == Type.STRING && arg.value() != null ? "the one at " + arg.value() : "the other one";
                    needs.add(new Need(at.line(), at.col(), "an antenna on its network, with a Transport Upgrade in it and in " + far));
                } else if (networkModule && i > 0 && t.get(i - 1).is("network") && method.equals("scan")) {
                    needs.add(new Need(at.line(), at.col(), "an antenna on its network"));
                } else if (networkModule && i > 0 && t.get(i - 1).is("network") && method.equals("send")) {
                    Token arg = t.get(i + 3);
                    if (arg.type() == Type.STRING && arg.value() != null && arg.value().matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+")) {
                        needs.add(new Need(at.line(), at.col(), "an antenna on its network, to reach " + arg.value()));
                    }
                } else if (i > 0 && t.get(i - 1).is("git") && method.equals("push")) {
                    needs.add(new Need(at.line(), at.col(), "your GitHub token, set in the game with /qbotics git token"));
                }
            }
            if (token.is("Radio") && i + 1 < t.size() && t.get(i + 1).is("(") && networkModule) {
                needs.add(new Need(token.line(), token.col(), "an antenna on its network"));
            }
            if (token.is(".") && i + 2 < t.size() && t.get(i + 1).is("chunk_loading") && t.get(i + 2).is("=")) {
                needs.add(new Need(t.get(i + 1).line(), t.get(i + 1).col(), "a Chunk Loader Upgrade in its antenna"));
            }
            // open("notes.txt") and execfile("other.py"): files read from the drive
            if ((token.is("open") || token.is("execfile")) && (i == 0 || !t.get(i - 1).is(".") && !t.get(i - 1).is("def"))
                    && i + 2 < t.size() && t.get(i + 1).is("(") && t.get(i + 2).type() == Type.STRING && t.get(i + 2).value() != null) {
                String mode = "r";
                if (token.is("open") && i + 4 < t.size() && t.get(i + 3).is(",") && t.get(i + 4).type() == Type.STRING
                        && t.get(i + 4).value() != null) {
                    mode = t.get(i + 4).value();
                }
                if (!mode.contains("w") && !mode.contains("a") && !mode.contains("x")) {
                    needs.add(new Need(token.line(), token.col(), "the file \"" + t.get(i + 2).value() + "\" on its drive"));
                }
            }
        }
        return needs;
    }

    /** The network a lookup at the dot {@code dot} is made on: "" this one, an address, or null if it isn't a network. */
    private static String receiverAt(List<Token> t, int dot, Map<String, String> receivers) {
        if (dot == 0) {
            return null;
        }
        Token before = t.get(dot - 1);
        if (before.type() == Type.NAME && (dot < 2 || !t.get(dot - 2).is("."))) {
            return receivers.get(before.text());
        }
        if (before.type() == Type.NAME) {
            return receivers.get(before.text()); // devices.net.bus(...)
        }
        // network().bus(...)
        if (before.is(")") && dot >= 3 && t.get(dot - 2).is("(") && t.get(dot - 3).is("network")) {
            return LOCAL;
        }
        return null;
    }

    private static String where(String address) {
        if (address.equals(LOCAL)) {
            return "on its network: ";
        }
        return address.equals("?") ? "on another network (behind an antenna): " : "on the network behind " + address + ": ";
    }

    /**
     * Names given a value once, at the top of the file, as text or None: CHEST = "chest". A
     * lookup by such a name is read as if the value were written there.
     */
    private static Map<String, Token> constants(List<Token> t) {
        Map<String, Token> found = new java.util.HashMap<>();
        Set<String> changed = new java.util.HashSet<>();
        for (int i = 0; i < t.size(); i++) {
            Token token = t.get(i);
            if (token.type() != Type.NAME) {
                continue;
            }
            Token next = i + 1 < t.size() ? t.get(i + 1) : null;
            boolean assigned = next != null && next.type() == Type.OP && next.text().matches("[-+*/%]?=");
            if (!assigned) {
                if (i > 0 && (t.get(i - 1).is("for") || t.get(i - 1).is("as") || t.get(i - 1).is("global"))) {
                    changed.add(token.text());
                }
                continue;
            }
            boolean top = (i == 0 || t.get(i - 1).type() == Type.NEWLINE) && token.col() == 0;
            Token value = i + 2 < t.size() ? t.get(i + 2) : null;
            boolean simple = value != null && i + 3 < t.size() && t.get(i + 3).type() == Type.NEWLINE
                    && ((value.type() == Type.STRING && value.value() != null) || value.is("None"));
            if (top && simple && next.is("=") && !found.containsKey(token.text())) {
                found.put(token.text(), value);
            } else {
                changed.add(token.text());
            }
        }
        changed.forEach(found::remove);
        return found;
    }

    private static String lookup(String method, List<Token> t, int arg, Map<String, Token> constants) {
        String kind = KINDS.get(method);
        if (arg >= t.size()) {
            return kind;
        }
        Token first = t.get(arg);
        if (first.type() == Type.NAME && constants.containsKey(first.text()) && arg + 1 < t.size() && t.get(arg + 1).is(")")) {
            Token value = constants.get(first.text());
            if (value.type() == Type.STRING) {
                return kind + (method.equals("device") ? " (of any kind)" : "") + " named \"" + value.value() + "\"";
            }
            first = t.get(arg + 1); // None: as if no name were given
        }
        if (first.is(")")) {
            return method.equals("device") ? kind : kind.replaceFirst("^an? ", "exactly one ")
                    .replace(" (or USB)", "") + " (it's found with no name, so there must be only one"
                    + (KINDS.get(method).contains("(or USB)") ? "; with none, a USB is used" : "") + ")";
        }
        if (first.is("key") && arg + 2 < t.size() && t.get(arg + 1).is("=")) {
            first = t.get(arg + 2);
            arg += 2;
        }
        if (first.type() == Type.STRING && first.value() != null && arg + 1 < t.size()
                && (t.get(arg + 1).is(")") || t.get(arg + 1).is(","))) {
            return kind + (method.equals("device") ? " (of any kind)" : "") + " named \"" + first.value() + "\"";
        }
        return kind + ", by a name the program works out as it runs";
    }

    private static String storage(List<Token> t, int arg) {
        List<String> names = new ArrayList<>();
        boolean worked = false;
        int depth = 1;
        for (int i = arg; i < t.size() && depth > 0; i++) {
            Token token = t.get(i);
            if (token.is("(") || token.is("[")) {
                depth++;
            } else if (token.is(")") || token.is("]")) {
                depth--;
            } else if (token.type() == Type.STRING && token.value() != null) {
                names.add("\"" + token.value() + "\"");
            } else if (!token.is(",")) {
                worked = true;
            }
        }
        String found = names.isEmpty() ? "" : " matching " + String.join(", ", names);
        return "Interfaces or Item Serial Buses" + found + (worked ? (found.isEmpty() ? "" : ",") + " and others the program names as it runs" : "");
    }

    private static int nextLine(List<Token> t, int from) {
        int i = from;
        while (i < t.size() && t.get(i).type() != Type.NEWLINE) {
            i++;
        }
        return i + 1;
    }
}
