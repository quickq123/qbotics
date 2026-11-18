package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import qbotics.Qbotics;
import qbotics.computer.ComputerManager;

/**
 * Client to server: something the player did in an open terminal.
 *
 * @param line the entered line for {@link #LINE}; empty otherwise
 */
public record TerminalInputPayload(UUID deviceId, byte action, String line) implements CustomPacketPayload {
    public static final byte LINE = 0;
    public static final byte INTERRUPT = 1;
    public static final byte CLOSE = 2;
    /** The editor's Run button; line is the file's path. */
    public static final byte RUN_FILE = 3;
    /** Ctrl+Z: pause what is running, or carry on if it is paused. */
    public static final byte PAUSE = 4;
    /** F8: run to the next line of the player's code and hold there. */
    public static final byte STEP = 5;
    /** The editor's Dry run (Shift+F5): line is the file, run changing nothing in the world. */
    public static final byte DRY_RUN = 6;

    public static final int MAX_LINE_LENGTH = 256;

    public static final Type<TerminalInputPayload> TYPE = new Type<>(Qbotics.id("terminal_input"));

    public static final StreamCodec<ByteBuf, TerminalInputPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, TerminalInputPayload::deviceId,
            ByteBufCodecs.BYTE, TerminalInputPayload::action,
            ByteBufCodecs.stringUtf8(MAX_LINE_LENGTH), TerminalInputPayload::line,
            TerminalInputPayload::new);

    @Override
    public Type<TerminalInputPayload> type() {
        return TYPE;
    }

    /** Runs on the server thread. */
    public void handle(ServerPlayer player) {
        ComputerManager manager = ComputerManager.get(player.level().getServer());
        switch (action) {
            case LINE -> manager.onInputLine(player, deviceId, line);
            case INTERRUPT -> manager.onInterrupt(player, deviceId);
            case CLOSE -> manager.closeTerminal(player);
            case RUN_FILE -> manager.onRunFile(player, deviceId, line);
            case DRY_RUN -> manager.onDryRunFile(player, deviceId, line);
            case PAUSE -> manager.onPause(player, deviceId);
            case STEP -> manager.onStep(player, deviceId, line); // line: the file the editor has open
            default -> {
            }
        }
    }
}
