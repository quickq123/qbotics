package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;
import qbotics.computer.Terminal;

/**
 * Server to client: the whole screen of one computer.
 *
 * @param open true when the player just right-clicked the computer, so the client should
 *             show the terminal; false for an update to a terminal that is already showing
 */
public record TerminalStatePayload(UUID deviceId, boolean open, List<String> lines, int cursorX, int cursorY)
        implements CustomPacketPayload {
    public static final Type<TerminalStatePayload> TYPE = new Type<>(Qbotics.id("terminal_state"));

    public static final StreamCodec<ByteBuf, TerminalStatePayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TerminalStatePayload::deviceId,
            ByteBufCodecs.BOOL, TerminalStatePayload::open,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(Terminal.COLS), Terminal.ROWS), TerminalStatePayload::lines,
            ByteBufCodecs.VAR_INT, TerminalStatePayload::cursorX,
            ByteBufCodecs.VAR_INT, TerminalStatePayload::cursorY,
            TerminalStatePayload::new);

    public static TerminalStatePayload of(UUID deviceId, boolean open, Terminal.Snapshot snapshot) {
        return new TerminalStatePayload(deviceId, open, snapshot.lines(), snapshot.cursorX(), snapshot.cursorY());
    }

    @Override
    public Type<TerminalStatePayload> type() {
        return TYPE;
    }
}
