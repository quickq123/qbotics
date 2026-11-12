package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/**
 * Server to client: where the program of a computer the player has open is, so its editor can
 * mark the line: the file and line of the player's own code it is on (line 0: none known), and
 * whether it is paused there. Sent when it changes.
 */
public record TracePayload(UUID deviceId, String file, int line, boolean paused) implements CustomPacketPayload {
    public static final Type<TracePayload> TYPE = new Type<>(Qbotics.id("trace"));

    public static final StreamCodec<ByteBuf, TracePayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TracePayload::deviceId,
            ByteBufCodecs.stringUtf8(256), TracePayload::file,
            ByteBufCodecs.VAR_INT, TracePayload::line,
            ByteBufCodecs.BOOL, TracePayload::paused,
            TracePayload::new);

    @Override
    public Type<TracePayload> type() {
        return TYPE;
    }
}
