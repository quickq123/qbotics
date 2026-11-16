package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** The Block Sensor's screen: its box as six offsets [minX, minY, minZ, maxX, maxY, maxZ], and a player changing it. */
public final class BoxPayloads {
    private BoxPayloads() {
    }

    private static final StreamCodec<ByteBuf, List<Integer>> BOX = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(6));

    /** Server to client: opens (or refreshes) its screen. */
    public record Info(BlockPos pos, List<Integer> box, boolean mayChange) implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(Qbotics.id("box_info"));
        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Info::pos,
                BOX, Info::box,
                ByteBufCodecs.BOOL, Info::mayChange,
                Info::new);

        @Override
        public Type<Info> type() {
            return TYPE;
        }
    }

    /** Client to server: the box wanted. */
    public record Action(BlockPos pos, List<Integer> box) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Qbotics.id("box_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Action::pos,
                BOX, Action::box,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }
}
