package qbotics.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** The Experience Interface's messages: what it holds, and a player storing, taking or locking. */
public final class XpPayloads {
    private XpPayloads() {
    }

    /**
     * Server to client: opens (or refreshes) the block's screen.
     *
     * @param points        experience held, in points
     * @param lock          who may use it: the number of a RadioNetwork.Access (private, team, public)
     * @param mayUse        whether this player may store and take
     * @param mayChangeLock whether this player may change the lock (the owner)
     */
    public record Info(BlockPos pos, long points, int lock, boolean mayUse, boolean mayChangeLock) implements CustomPacketPayload {
        public static final Type<Info> TYPE = new Type<>(Qbotics.id("xp_info"));
        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Info::pos,
                ByteBufCodecs.VAR_LONG, Info::points,
                ByteBufCodecs.VAR_INT, Info::lock,
                ByteBufCodecs.BOOL, Info::mayUse,
                ByteBufCodecs.BOOL, Info::mayChangeLock,
                Info::new);

        @Override
        public Type<Info> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: something pressed on the screen.
     *
     * @param what   STORE, TAKE or LOCK
     * @param amount for STORE and TAKE, a number of levels; for LOCK, the number of the new setting
     */
    public record Action(BlockPos pos, int what, int amount) implements CustomPacketPayload {
        public static final int STORE = 0;
        public static final int TAKE = 1;
        public static final int LOCK = 2;

        public static final Type<Action> TYPE = new Type<>(Qbotics.id("xp_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Action::pos,
                ByteBufCodecs.VAR_INT, Action::what,
                ByteBufCodecs.VAR_INT, Action::amount,
                Action::new);

        @Override
        public Type<Action> type() {
            return TYPE;
        }
    }
}
