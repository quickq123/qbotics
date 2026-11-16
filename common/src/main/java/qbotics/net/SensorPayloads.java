package qbotics.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** The Entity Sensor's screen: its size, and a player changing it. */
public final class SensorPayloads {
    private SensorPayloads() {
    }

    /** Server to client: opens (or refreshes) its screen. */
    public record Info(BlockPos pos, int radius, int height, boolean mayChange) implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(Qbotics.id("sensor_info"));
        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Info::pos,
                ByteBufCodecs.VAR_INT, Info::radius,
                ByteBufCodecs.VAR_INT, Info::height,
                ByteBufCodecs.BOOL, Info::mayChange,
                Info::new);

        @Override
        public Type<Info> type() {
            return TYPE;
        }
    }

    /** Client to server: the size wanted. */
    public record Action(BlockPos pos, int radius, int height) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Qbotics.id("sensor_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Action::pos,
                ByteBufCodecs.VAR_INT, Action::radius,
                ByteBufCodecs.VAR_INT, Action::height,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }
}
