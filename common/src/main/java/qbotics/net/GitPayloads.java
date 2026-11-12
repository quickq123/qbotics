package qbotics.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** The git token screen's messages (design doc §17.5). The token only ever travels from the player to the server. */
public final class GitPayloads {
    static final int MAX_TOKEN = 256;

    private GitPayloads() {
    }

    /** Server to client: open the token screen. Says whether a token is set and whether the server lets players push. */
    public record OpenToken(boolean hasToken, boolean pushAllowed) implements CustomPacketPayload {
        public static final Type<OpenToken> TYPE = new Type<>(Qbotics.id("git_open_token"));
        public static final StreamCodec<ByteBuf, OpenToken> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, OpenToken::hasToken, ByteBufCodecs.BOOL, OpenToken::pushAllowed, OpenToken::new);

        @Override
        public Type<OpenToken> type() {
            return TYPE;
        }
    }

    /** Client to server: the player's token ("" removes it). */
    public record SetToken(String token) implements CustomPacketPayload {
        public static final Type<SetToken> TYPE = new Type<>(Qbotics.id("git_set_token"));
        public static final StreamCodec<ByteBuf, SetToken> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_TOKEN), SetToken::token, SetToken::new);

        @Override
        public Type<SetToken> type() {
            return TYPE;
        }
    }
}
