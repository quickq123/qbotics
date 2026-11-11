package qbotics.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;
import qbotics.network.Addresses;

/** The Network Probe's messages: what a device is called and how it is addressed, and renaming it. */
public final class ProbePayloads {
    private static final int MAX_TEXT = 64;

    private ProbePayloads() {
    }

    /**
     * Server to client: the device a player probed. Opens (or refreshes) the probe's pop-up.
     *
     * @param pos      the device's block
     * @param side     for a plate on a cable, the side it is on (a Direction's number); -1 for a whole block
     * @param kind     "computer", "interface", "bus", ...
     * @param name     its name, or ""
     * @param external "external IP:port" if its network has an antenna, otherwise ""
     */
    public record Info(BlockPos pos, int side, String kind, String name, String ip, String mac, String external)
            implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(Qbotics.id("probe_info"));
        /** The three addresses travel as one piece of text, a line each. */
        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Info::pos,
                ByteBufCodecs.VAR_INT, Info::side,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Info::kind,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Info::name,
                ByteBufCodecs.stringUtf8(MAX_TEXT * 3), info -> info.ip + "\n" + info.mac + "\n" + info.external,
                (pos, side, kind, name, addresses) -> {
                    String[] lines = (addresses + "\n\n").split("\n", -1);
                    return new Info(pos, side, kind, name, lines[0], lines[1], lines[2]);
                });

        @Override
        public Type<Info> type() {
            return TYPE;
        }

        /** What a program writes to get hold of this device: its name if it has one, otherwise its IP. */
        public String key() {
            return name.isEmpty() ? ip : name;
        }

        /** A line of Python that reaches this device. */
        public String python() {
            if (kind.equals("computer")) {
                return "network.send(\"" + key() + "\", data)";
            }
            // "bus" -> net.bus("x"); "fluid_input" -> net.fluid_input("x")
            boolean logistics = kind.endsWith("interface") || kind.endsWith("input") || kind.endsWith("output") || kind.endsWith("bus");
            return "net." + (logistics ? kind : "device") + "(\"" + key() + "\")";
        }
    }

    /** Client to server: give the probed device a name ("" removes it). */
    public record Rename(BlockPos pos, int side, String name) implements CustomPacketPayload {
        public static final Type<Rename> TYPE = new Type<>(Qbotics.id("probe_rename"));
        public static final StreamCodec<ByteBuf, Rename> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Rename::pos,
                ByteBufCodecs.VAR_INT, Rename::side,
                ByteBufCodecs.stringUtf8(Addresses.MAX_NAME_LENGTH * 2), Rename::name,
                Rename::new);

        @Override
        public Type<Rename> type() {
            return TYPE;
        }
    }
}
