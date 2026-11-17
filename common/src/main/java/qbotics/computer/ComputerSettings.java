package qbotics.computer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.ComputerBlockEntity;
import qbotics.network.Addresses;
import qbotics.network.CableNetwork;
import qbotics.network.RadioNetwork;

/**
 * A computer's settings page (design doc §12). It is shown in the editor as a JSON file,
 * the way code editors show their settings, but it isn't a file on the drive: reading it
 * writes out the computer's current settings, and saving it applies whatever was changed.
 * So programs running on the computer can't read the password out of it.
 */
public final class ComputerSettings {
    /** Where the editor finds the page. No real file or folder can have this path. */
    public static final String PATH = "/.computer/settings.json";
    /** What stands in for a password that is set: it is never sent back to anyone. */
    public static final String MASK = "********";

    private ComputerSettings() {
    }

    /** True for the settings page and anything else under its made-up folder. */
    public static boolean covers(String path) {
        return path.equals(PATH) || path.equals("/.computer") || path.startsWith("/.computer/");
    }

    /** The page as text, for the editor. Keys that start with _ are notes for the reader. */
    public static String read(ServerLevel level, ComputerBlockEntity computer) {
        CableNetwork network = CableNetwork.at(level, computer.getBlockPos());
        JsonObject page = new JsonObject();
        page.addProperty("_how", "Change a value and save (Ctrl+S). Lines whose name starts with _ are notes and are ignored.");
        page.addProperty("name", computer.deviceName());
        page.addProperty("_name", "What this computer is called on its network. Letters, digits, _ - and . (up to "
                + Addresses.MAX_NAME_LENGTH + ").");
        page.addProperty("ip", computer.ip());
        page.addProperty("_ip", "Given by the network; it can't be changed here.");

        page.addProperty("open", computer.opens() == RadioNetwork.Access.PRIVATE ? "owner" : computer.opens().word);
        page.addProperty("_open", "Who may open this computer by right-clicking it: \"owner\" (only you), \"team\" (your team: "
                + "FTB Teams, or the game's /team), or \"public\" (anyone). Whoever opens it works as you: your files, your devices.");

        JsonObject remote = new JsonObject();
        remote.addProperty("allow", computer.remoteLogin().word);
        remote.addProperty("_allow", "Who may log in with ssh: \"owner\" (only the owner's own computers), "
                + "\"password\" (anyone who knows the password), or \"off\" (nobody).");
        remote.addProperty("password", computer.hasPassword() ? MASK : "");
        remote.addProperty("_password", "Type a new password to set it. " + MASK + " means one is set; make it empty to remove it.");
        page.add("remote_login", remote);

        AntennaBlockEntity antenna = network.gateway();
        if (antenna == null) {
            page.addProperty("_antenna", "No antenna is cabled to this computer, so it can't be reached from other networks.");
        } else {
            JsonObject radio = new JsonObject();
            radio.addProperty("access", antenna.access().word);
            radio.addProperty("_access", "Who may reach this network by radio: \"private\" (only the antenna owner's devices), "
                    + "\"team\" (the owner's team), or \"public\" (anyone on the same frequency and in range).");
            radio.addProperty("frequency", antenna.frequency());
            radio.addProperty("_frequency", "Only antennas on the same frequency hear each other.");
            radio.addProperty("external_ip", antenna.externalIp());
            radio.addProperty("_external_ip", "The address other networks reach this one by; it can't be changed here.");
            page.add("antenna", radio);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(page) + "\n";
    }

    private static @Nullable String text(JsonObject from, String key) {
        JsonElement value = from.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * Applies an edited page. Everything is checked before anything is changed.
     *
     * @return null if it was saved, otherwise what is wrong, in words for the player
     */
    public static @Nullable String write(ServerLevel level, ComputerBlockEntity computer, String edited) {
        JsonObject page;
        try {
            page = JsonParser.parseString(edited).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return "The settings aren't valid JSON, so nothing was changed.";
        }
        String name = text(page, "name");
        if (name != null && !name.isBlank() && Addresses.cleanName(name).isEmpty()) {
            return "The name can use letters, digits, _ - and . only.";
        }

        String open = text(page, "open");
        RadioNetwork.Access opens = open == null ? computer.opens()
                : open.equals("owner") ? RadioNetwork.Access.PRIVATE : RadioNetwork.Access.parse(open);
        if (opens == null) {
            return "\"open\" must be \"owner\", \"team\" or \"public\", not \"" + open + "\".";
        }

        JsonObject remote = page.has("remote_login") && page.get("remote_login").isJsonObject() ? page.getAsJsonObject("remote_login") : new JsonObject();
        String allow = text(remote, "allow");
        ComputerBlockEntity.RemoteLogin login = allow == null ? computer.remoteLogin() : ComputerBlockEntity.RemoteLogin.parse(allow);
        if (login == null) {
            return "\"allow\" must be \"owner\", \"password\" or \"off\", not \"" + allow + "\".";
        }
        String password = text(remote, "password");
        boolean keeps = password == null || password.equals(MASK);
        boolean willHavePassword = keeps ? computer.hasPassword() : !password.isEmpty();
        if (password != null && password.length() > 64) {
            return "The password can be at most 64 characters.";
        }
        if (login == ComputerBlockEntity.RemoteLogin.PASSWORD && !willHavePassword) {
            return "To allow login by password, set a password too.";
        }

        AntennaBlockEntity antenna = CableNetwork.at(level, computer.getBlockPos()).gateway();
        JsonObject radio = page.has("antenna") && page.get("antenna").isJsonObject() ? page.getAsJsonObject("antenna") : null;
        RadioNetwork.Access access = null;
        String frequency = null;
        if (radio != null && antenna != null) {
            String wanted = text(radio, "access");
            access = wanted == null ? antenna.access() : RadioNetwork.Access.parse(wanted);
            if (access == null) {
                return "The antenna's \"access\" must be \"private\", \"team\" or \"public\", not \"" + wanted + "\".";
            }
            frequency = text(radio, "frequency");
            if (frequency != null && Addresses.cleanName(frequency).isEmpty()) {
                return "The frequency can use letters, digits, _ - and . only, and can't be empty.";
            }
        }

        // Everything checks out: apply it.
        if (name != null) {
            computer.setDeviceName(name);
        }
        computer.setRemoteLogin(login);
        computer.setOpens(opens);
        if (!keeps) {
            computer.setPassword(password);
        }
        if (antenna != null && access != null) {
            antenna.setAccess(access);
            if (frequency != null) {
                antenna.tune(frequency);
            }
        }
        return null;
    }
}
