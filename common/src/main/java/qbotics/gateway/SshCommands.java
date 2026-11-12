package qbotics.gateway;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import qbotics.QboticsConfig;

/** What /qbotics ssh says and does: the gateway's state, and a player's keys. */
public final class SshCommands {
    private SshCommands() {
    }

    /** /qbotics ssh: whether the gateway is on, how to log in, and how many keys the player has. */
    public static List<String> status(MinecraftServer mc, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        if (SshGateway.running()) {
            out.add("The SSH gateway is on, at " + QboticsConfig.sshBindAddress + " port " + QboticsConfig.sshPort + ".");
            out.add("Log in as a computer: ssh <name>@<this server> -p " + QboticsConfig.sshPort + "   (or laptop@...)");
        } else {
            out.add("The SSH gateway is off. The server's admin turns it on: ssh_gateway.enabled in qbotics-server.toml.");
        }
        int keys = SshGateway.keys(mc).describe(player.getUUID()).size();
        out.add(keys == 0 ? "You have no keys yet: /qbotics ssh key add <the contents of your id_ed25519.pub>"
                : "You have " + keys + " key" + (keys == 1 ? "" : "s") + ": /qbotics ssh key list");
        return out;
    }

    public static String add(MinecraftServer mc, ServerPlayer player, String key) {
        String problem = SshGateway.keys(mc).add(player.getUUID(), key);
        return problem == null ? "Key added. Log in with: ssh <computer name>@<this server> -p " + QboticsConfig.sshPort : problem;
    }

    public static List<String> list(MinecraftServer mc, ServerPlayer player) {
        List<String> keys = SshGateway.keys(mc).describe(player.getUUID());
        List<String> out = new ArrayList<>();
        if (keys.isEmpty()) {
            out.add("You have no keys.");
        }
        for (int i = 0; i < keys.size(); i++) {
            out.add((i + 1) + ". " + keys.get(i));
        }
        return out;
    }

    public static String remove(MinecraftServer mc, ServerPlayer player, int number) {
        return SshGateway.keys(mc).remove(player.getUUID(), number) ? "Key " + number + " removed." : "You have no key " + number + ".";
    }
}
