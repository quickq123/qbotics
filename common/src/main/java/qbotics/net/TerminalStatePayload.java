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
 * Server to client: one computer's terminal. The whole screen is sent each time; scroll-back
 * is sent only as the lines that are new, which the client adds to what it already has.
 *
 * @param open  true when the player just right-clicked the computer, so the client should
 *              show its screen; false for an update to a screen that is already showing
 * @param state the screen and the new scroll-back lines
 */
public record TerminalStatePayload(UUID deviceId, boolean open, Terminal.State state) implements CustomPacketPayload {
    public static final Type<TerminalStatePayload> TYPE = new Type<>(Qbotics.id("terminal_state"));

    private static final StreamCodec<ByteBuf, List<String>> SCROLLED =
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(Terminal.COLS), Terminal.MAX_SCROLLBACK);
    private static final StreamCodec<ByteBuf, List<String>> SCREEN =
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(Terminal.COLS), Terminal.ROWS);

    public static final StreamCodec<ByteBuf, TerminalStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                Terminal.State state = payload.state();
                UUIDUtil.STREAM_CODEC.encode(buffer, payload.deviceId());
                buffer.writeBoolean(payload.open());
                ByteBufCodecs.VAR_INT.encode(buffer, state.epoch());
                ByteBufCodecs.VAR_LONG.encode(buffer, state.scrolledStart());
                SCROLLED.encode(buffer, state.scrolled());
                SCREEN.encode(buffer, state.lines());
                ByteBufCodecs.VAR_INT.encode(buffer, state.cursorX());
                ByteBufCodecs.VAR_INT.encode(buffer, state.cursorY());
            },
            buffer -> new TerminalStatePayload(
                    UUIDUtil.STREAM_CODEC.decode(buffer),
                    buffer.readBoolean(),
                    new Terminal.State(
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.VAR_LONG.decode(buffer),
                            SCROLLED.decode(buffer),
                            SCREEN.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer),
                            ByteBufCodecs.VAR_INT.decode(buffer))));

    @Override
    public Type<TerminalStatePayload> type() {
        return TYPE;
    }
}
