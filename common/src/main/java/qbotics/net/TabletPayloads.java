package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** What tablets send between the server and the game of the player holding one (design doc §10.6). */
public final class TabletPayloads {
    private TabletPayloads() {
    }

    /**
     * Server to client: a tablet's tabs (one per paired network), which is selected (-1 for
     * none), and the made-up position its selected screen is sent under (see Tablets.KEY_Y).
     */
    public record State(UUID tablet, List<String> tabs, int selected, BlockPos screen) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(Qbotics.id("tablet_state"));
        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, State::tablet,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(256), 16), state -> new ArrayList<>(state.tabs()),
                ByteBufCodecs.VAR_INT, State::selected,
                BlockPos.STREAM_CODEC, State::screen,
                State::new);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    /** Server to client: open the tablet's view (right-click with it). */
    public record Open(UUID tablet) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Qbotics.id("tablet_open"));
        public static final StreamCodec<ByteBuf, Open> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Open::tablet, Open::new);

        @Override
        public Type<Open> type() {
            return TYPE;
        }
    }

    /** Client to server: the player picked a tab. */
    public record Select(UUID tablet, int index) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(Qbotics.id("tablet_select"));
        public static final StreamCodec<ByteBuf, Select> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Select::tablet, ByteBufCodecs.VAR_INT, Select::index, Select::new);

        @Override
        public Type<Select> type() {
            return TYPE;
        }
    }
}
