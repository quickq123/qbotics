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
    public record Web(BlockPos origin, int width, int height, boolean hasPage, String html, String state,
            java.util.@org.jspecify.annotations.Nullable Map<String, qbotics.camera.Shot> images) implements CustomPacketPayload {
        public static final Type<Web> TYPE = new Type<>(Qbotics.id("display_web"));
        public static final StreamCodec<ByteBuf, Web> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    BlockPos.STREAM_CODEC.encode(buffer, payload.origin());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.width());
                    ByteBufCodecs.VAR_INT.encode(buffer, payload.height());
                    ByteBufCodecs.BOOL.encode(buffer, payload.hasPage());
                    ByteBufCodecs.stringUtf8(1 << 20).encode(buffer, payload.html());
                    ByteBufCodecs.stringUtf8(1 << 18).encode(buffer, payload.state());
                    // The pictures, only when they changed (-1: unchanged).
                    if (payload.images() == null) {
                        ByteBufCodecs.VAR_INT.encode(buffer, -1);
                    } else {
                        ByteBufCodecs.VAR_INT.encode(buffer, payload.images().size());
                        payload.images().forEach((name, shot) -> {
                            ByteBufCodecs.stringUtf8(64).encode(buffer, name);
                            if (shot instanceof qbotics.camera.Picture picture) {
                                buffer.writeByte(0);
                                ByteBufCodecs.VAR_INT.encode(buffer, picture.width());
                                ByteBufCodecs.VAR_INT.encode(buffer, picture.height());
                                BlockPos.STREAM_CODEC.encode(buffer, picture.origin());
                                ByteBufCodecs.BYTE_ARRAY.encode(buffer, picture.data());
                            } else if (shot instanceof qbotics.camera.View view) {
                                buffer.writeByte(1);
                                writeView(buffer, view);
                            }
                        });
                    }
                },
                buffer -> {
                    BlockPos origin = BlockPos.STREAM_CODEC.decode(buffer);
                    int width = ByteBufCodecs.VAR_INT.decode(buffer);
                    int height = ByteBufCodecs.VAR_INT.decode(buffer);
                    boolean hasPage = ByteBufCodecs.BOOL.decode(buffer);
                    String html = ByteBufCodecs.stringUtf8(1 << 20).decode(buffer);
                    String state = ByteBufCodecs.stringUtf8(1 << 18).decode(buffer);
                    int count = ByteBufCodecs.VAR_INT.decode(buffer);
                    java.util.Map<String, qbotics.camera.Shot> images = null;
                    if (count >= 0) {
                        images = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < count; i++) {
                            String name = ByteBufCodecs.stringUtf8(64).decode(buffer);
                            if (buffer.readByte() == 1) {
                                qbotics.camera.View view = readView(buffer);
                                if (i < 16 && view != null) {
                                    images.put(name, view);
                                }
                                continue;
                            }
                            int w = ByteBufCodecs.VAR_INT.decode(buffer);
                            int h = ByteBufCodecs.VAR_INT.decode(buffer);
                            BlockPos at = BlockPos.STREAM_CODEC.decode(buffer);
                            byte[] data = ByteBufCodecs.BYTE_ARRAY.decode(buffer);
                            if (i < 16 && w > 0 && h > 0 && data.length == w * h * qbotics.camera.Picture.BYTES_PER_PIXEL) {
                                images.put(name, new qbotics.camera.Picture(w, h, at, data));
                            }
                        }
                    }
                    return new Web(origin, width, height, hasPage, html, state, images);
                });

        @Override
        public Type<Web> type() {
            return TYPE;
        }
    }

    // A Qbot's view: its size, where and which way, then each block it sees (where, as small
    // signed numbers, its state and which faces) and each creature (its box and kind).

    private static final int MAX_VIEW_BLOCKS = 1 << 16;

    private static void signed(ByteBuf buffer, int n) {
        ByteBufCodecs.VAR_INT.encode(buffer, (n << 1) ^ (n >> 31));
    }

    private static int signed(ByteBuf buffer) {
        int n = ByteBufCodecs.VAR_INT.decode(buffer);
        return (n >>> 1) ^ -(n & 1);
    }

    static void writeView(ByteBuf buffer, qbotics.camera.View view) {
        ByteBufCodecs.VAR_INT.encode(buffer, view.width());
        ByteBufCodecs.VAR_INT.encode(buffer, view.height());
        BlockPos.STREAM_CODEC.encode(buffer, view.origin());
        buffer.writeByte(view.facing().get3DDataValue());
        buffer.writeFloat(view.pitch());
        ByteBufCodecs.VAR_INT.encode(buffer, view.blocks().size());
        for (qbotics.camera.ConeView.Seen s : view.blocks()) {
            signed(buffer, s.dx());
            signed(buffer, s.dy());
            signed(buffer, s.dz());
            ByteBufCodecs.VAR_INT.encode(buffer, s.state());
            buffer.writeByte(s.faces());
        }
        ByteBufCodecs.VAR_INT.encode(buffer, view.creatures().size());
        for (qbotics.camera.ConeView.Creature c : view.creatures()) {
            buffer.writeFloat(c.x());
            buffer.writeFloat(c.y());
            buffer.writeFloat(c.z());
            buffer.writeFloat(c.width());
            buffer.writeFloat(c.height());
            buffer.writeFloat(c.depth());
            buffer.writeByte(c.kind());
        }
    }

    /** A view, or null if it isn't one that could have been sent (it is read through either way). */
    static qbotics.camera.@org.jspecify.annotations.Nullable View readView(ByteBuf buffer) {
        int w = ByteBufCodecs.VAR_INT.decode(buffer);
        int h = ByteBufCodecs.VAR_INT.decode(buffer);
        BlockPos at = BlockPos.STREAM_CODEC.decode(buffer);
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.from3DDataValue(buffer.readByte());
        float pitch = buffer.readFloat();
        int blocks = ByteBufCodecs.VAR_INT.decode(buffer);
        if (blocks < 0 || blocks > MAX_VIEW_BLOCKS) {
            throw new IllegalArgumentException("A view of " + blocks + " blocks");
        }
        java.util.List<qbotics.camera.ConeView.Seen> seen = new java.util.ArrayList<>(blocks);
        for (int i = 0; i < blocks; i++) {
            seen.add(new qbotics.camera.ConeView.Seen(signed(buffer), signed(buffer), signed(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                    buffer.readByte() & 0x3F));
        }
        int creatures = ByteBufCodecs.VAR_INT.decode(buffer);
        if (creatures < 0 || creatures > 256) {
            throw new IllegalArgumentException("A view of " + creatures + " creatures");
        }
        java.util.List<qbotics.camera.ConeView.Creature> list = new java.util.ArrayList<>(creatures);
        for (int i = 0; i < creatures; i++) {
            list.add(new qbotics.camera.ConeView.Creature(buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readByte()));
        }
        if (w < 1 || h < 1 || w > qbotics.camera.ConeView.MAX_WIDTH || h > qbotics.camera.ConeView.MAX_HEIGHT || facing.getAxis().isVertical()) {
            return null;
        }
        return new qbotics.camera.View(w, h, at, facing, pitch, seen, list);
    }

    /** Client to server: a player clicked an element of a Web Panel's page (what the page says it was, as JSON). */
    public record WebClick(BlockPos origin, String element) implements CustomPacketPayload {
        public static final Type<WebClick> TYPE = new Type<>(Qbotics.id("display_web_click"));
        public static final StreamCodec<ByteBuf, WebClick> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, WebClick::origin,
                ByteBufCodecs.stringUtf8(8192), WebClick::element,
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
