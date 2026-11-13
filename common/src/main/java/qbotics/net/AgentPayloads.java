package qbotics.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/**
 * The in-game AI agent's messages (design doc §17.8). The agent itself runs in the player's
 * game, talking to the player's own model; what it does in the world it asks the server to
 * do, as that player, one tool call at a time.
 */
public final class AgentPayloads {
    private AgentPayloads() {
    }

    /** The largest tool result sent back (a file, a listing): kept well under a packet's size. */
    public static final int MAX_TEXT = 60_000;

    /** Client to server: run one of the agent's tools (qbotics.agent.AgentTools) with JSON arguments. */
    public record ToolCall(int id, String tool, String args) implements CustomPacketPayload {
        public static final Type<ToolCall> TYPE = new Type<>(Qbotics.id("agent_tool_call"));
        public static final StreamCodec<ByteBuf, ToolCall> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ToolCall::id,
                ByteBufCodecs.stringUtf8(64), ToolCall::tool,
                ByteBufCodecs.stringUtf8(MAX_TEXT), ToolCall::args,
                ToolCall::new);

        @Override
        public Type<ToolCall> type() {
            return TYPE;
        }
    }

    /** Server to client: what a tool call gave, as text for the model. */
    public record ToolResult(int id, String text) implements CustomPacketPayload {
        public static final Type<ToolResult> TYPE = new Type<>(Qbotics.id("agent_tool_result"));
        public static final StreamCodec<ByteBuf, ToolResult> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ToolResult::id,
                ByteBufCodecs.stringUtf8(MAX_TEXT), ToolResult::text,
                ToolResult::new);

        @Override
        public Type<ToolResult> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: a question asked of the agent, or its answer, for everyone to see when
     * the server shares AI replies (ai.share_replies: teacher and student sessions).
     *
     * @param answer false for the question, true for the answer
     */
    public record Share(boolean answer, String text) implements CustomPacketPayload {
        public static final Type<Share> TYPE = new Type<>(Qbotics.id("agent_share"));
        public static final StreamCodec<ByteBuf, Share> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Share::answer,
                ByteBufCodecs.stringUtf8(8_000), Share::text,
                Share::new);

        @Override
        public Type<Share> type() {
            return TYPE;
        }
    }

    /** Server to client, on joining: whether the agent may use the world, and whether replies are shared. */
    public record Settings(boolean enabled, boolean shared) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = new Type<>(Qbotics.id("agent_settings"));
        public static final StreamCodec<ByteBuf, Settings> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Settings::enabled,
                ByteBufCodecs.BOOL, Settings::shared,
                Settings::new);

        @Override
        public Type<Settings> type() {
            return TYPE;
        }
    }
}
