package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;
import qbotics.display.TextScreen;

/** What Text Monitors and Control Panels send between the server and players' games (design doc §10.4). */
public final class DisplayPayloads {
    /** The most characters in one row of a text screen: 8 blocks at the smallest scale. */
    public static final int MAX_COLS = 8 * TextScreen.COLS_PER_BLOCK * 2;
    public static final int MAX_ROWS = 6 * TextScreen.ROWS_PER_BLOCK * 2;

    private DisplayPayloads() {
    }

    private static final StreamCodec<ByteBuf, TextScreen.Row> ROW = StreamCodec.of(
            (buffer, row) -> {
                ByteBufCodecs.VAR_INT.encode(buffer, row.index());
                ByteBufCodecs.stringUtf8(MAX_COLS * 3).encode(buffer, row.text());
                ByteBufCodecs.BYTE_ARRAY.encode(buffer, row.colors());
            },
            buffer -> new TextScreen.Row(ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.stringUtf8(MAX_COLS * 3).decode(buffer),
                    ByteBufCodecs.BYTE_ARRAY.decode(buffer)));

    /**
     * Server to client: a text screen's size and scale, and rows of it. With {@code full}, the
     * rows are all of them and replace whatever the player had; otherwise only the rows that changed.
     *
     * @param origin the screen's top-left block
     */
    public record Text(BlockPos origin, int cols, int rows, float scale, boolean full, List<TextScreen.Row> lines)
            implements CustomPacketPayload {
        public static final Type<Text> TYPE = new Type<>(Qbotics.id("display_text"));
        public static final StreamCodec<ByteBuf, Text> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    BlockPos.STREAM_CODEC.encode(buffer, payload.origin());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.cols());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.rows());
                    buffer.writeFloat(payload.scale());
                    buffer.writeBoolean(payload.full());
                    ByteBufCodecs.collection(ArrayList::new, ROW, MAX_ROWS).encode(buffer, new ArrayList<>(payload.lines()));
                },
                buffer -> new Text(BlockPos.STREAM_CODEC.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                        buffer.readFloat(), buffer.readBoolean(), ByteBufCodecs.collection(ArrayList::new, ROW, MAX_ROWS).decode(buffer)));

        @Override
        public Type<Text> type() {
            return TYPE;
        }
    }

    /** Server to client: a control panel's widgets and their values, as JSON. */
    public record Panel(BlockPos origin, String json) implements CustomPacketPayload {
        public static final Type<Panel> TYPE = new Type<>(Qbotics.id("display_panel"));
        public static final StreamCodec<ByteBuf, Panel> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Panel::origin,
                ByteBufCodecs.stringUtf8(1 << 20), Panel::json,
                Panel::new);

        @Override
        public Type<Panel> type() {
            return TYPE;
        }
    }

    /** Server to client: open this screen close up (sneak and right-click). */
    /**
     * Server to client: a Web Panel's page and state. The page is sent only when it has
     * changed (hasPage); its state every time it changes. Width and height in CSS pixels.
     */
    public record Web(BlockPos origin, int width, int height, boolean hasPage, String html, String state) implements CustomPacketPayload {
        public static final Type<Web> TYPE = new Type<>(Qbotics.id("display_web"));
        public static final StreamCodec<ByteBuf, Web> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    BlockPos.STREAM_CODEC.encode(buffer, payload.origin());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.width());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.height());
                    ByteBufCodecs.BOOL.encode(buffer, payload.hasPage());
                    ByteBufCodecs.stringUtf8(1 << 20).encode(buffer, payload.html());
                    ByteBufCodecs.stringUtf8(1 << 18).encode(buffer, payload.state());
                },
                buffer -> new Web(BlockPos.STREAM_CODEC.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                        ByteBufCodecs.BOOL.decode(buffer), ByteBufCodecs.stringUtf8(1 << 20).decode(buffer), ByteBufCodecs.stringUtf8(1 << 18).decode(buffer)));

        @Override
        public Type<Web> type() {
            return TYPE;
        }
    }

    /** Client to server: a player clicked an element of a Web Panel's page (what the page says it was, as JSON). */
    public record WebClick(BlockPos origin, String element) implements CustomPacketPayload {
        public static final Type<WebClick> TYPE = new Type<>(Qbotics.id("display_web_click"));
        public static final StreamCodec<ByteBuf, WebClick> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, WebClick::origin,
                ByteBufCodecs.stringUtf8(4096), WebClick::element,
                WebClick::new);

        @Override
        public Type<WebClick> type() {
            return TYPE;
        }
    }

    public record OpenCloseUp(BlockPos origin) implements CustomPacketPayload {
        public static final Type<OpenCloseUp> TYPE = new Type<>(Qbotics.id("display_close_up"));
        public static final StreamCodec<ByteBuf, OpenCloseUp> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenCloseUp::origin, OpenCloseUp::new);

        @Override
        public Type<OpenCloseUp> type() {
            return TYPE;
        }
    }

    /** Client to server: a touch in the close-up view, as fractions of the screen's width and height. */
    public record Touch(BlockPos origin, float x, float y) implements CustomPacketPayload {
        public static final Type<Touch> TYPE = new Type<>(Qbotics.id("display_touch"));
        public static final StreamCodec<ByteBuf, Touch> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Touch::origin,
                ByteBufCodecs.FLOAT, Touch::x,
                ByteBufCodecs.FLOAT, Touch::y,
                Touch::new);

        @Override
        public Type<Touch> type() {
            return TYPE;
        }
    }

    /** Every payload here, for {@code payload_codecs}. */
    public static List<CustomPacketPayload.Type<?>> types() {
        return List.of(Text.TYPE, Panel.TYPE, OpenCloseUp.TYPE, Touch.TYPE);
    }
}
