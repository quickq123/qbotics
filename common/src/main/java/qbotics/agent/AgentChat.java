package qbotics.agent;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * How the AI agent's questions and answers look in chat: the same for one player and, when the
 * server shares them (ai.share_replies), for everyone. Code in ``` blocks is set apart in its
 * own colour; the fences themselves aren't shown.
 */
public final class AgentChat {
    private AgentChat() {
    }

    public static List<Component> question(String player, String text) {
        return List.of(Component.literal("[AI] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(player + " asked: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    public static List<Component> answer(String player, String text) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("[AI → " + player + "]").withStyle(ChatFormatting.DARK_AQUA));
        boolean code = false;
        for (String line : text.replace("\r", "").split("\n")) {
            if (line.strip().startsWith("```")) {
                code = !code;
                continue;
            }
            MutableComponent shown = Component.literal(code ? "  " + line : line);
            lines.add(shown.withStyle(code ? ChatFormatting.AQUA : ChatFormatting.WHITE));
        }
        return lines;
    }
}
