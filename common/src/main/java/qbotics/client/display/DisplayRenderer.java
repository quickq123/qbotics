package qbotics.client.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.block.DisplayBlockEntity;

/**
 * Draws what a Text Monitor or a Control Panel shows on its face. The whole screen is drawn
 * from its top-left block; the other blocks of it draw nothing. Screens light themselves, so
 * they read the same by day and night.
 */
public class DisplayRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    /** The frame left around the picture, in blocks. */
    private static final float INSET = 1 / 32f;
    private final Font font;
    private final net.minecraft.client.renderer.item.ItemModelResolver items;

    public DisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
        this.items = context.itemModelResolver();
    }

    /** An item icon over a Web Panel's page: where (CSS pixels) and the item, ready to draw. */
    record WebItem(float x, float y, float width, float height, net.minecraft.client.renderer.item.ItemStackRenderState item) {
    }

    public static final class State extends BlockEntityRenderState {
        boolean origin;
        Direction facing = Direction.NORTH;
        /** Turns the block's frame so x runs along the screen's rows, y up its columns and z out of its face. */
        org.joml.Quaternionf turn = new org.joml.Quaternionf();
        int width;
        int height;
        @Nullable Object view;
        /** A Web Panel's picture, its width in CSS pixels, and the item icons over it. */
        net.minecraft.resources.@Nullable Identifier texture;
        int pageWidth;
        List<WebItem> webItems = List.of();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DisplayBlockEntity display, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(display, state, partialTicks, cameraPosition, breakProgress);
        state.origin = display.isOrigin();
        state.facing = display.facing();
        if (display.getBlockState().getBlock() instanceof qbotics.block.DisplayBlock) {
            Direction right = qbotics.block.DisplayBlock.right(display.getBlockState());
            Direction up = qbotics.block.DisplayBlock.down(display.getBlockState()).getOpposite();
            Direction out = state.facing;
            org.joml.Matrix3f basis = new org.joml.Matrix3f(
                    right.getStepX(), right.getStepY(), right.getStepZ(),
                    up.getStepX(), up.getStepY(), up.getStepZ(),
                    out.getStepX(), out.getStepY(), out.getStepZ());
            state.turn = new org.joml.Quaternionf().setFromNormalized(basis);
        }
        state.width = display.screenWidth();
        state.height = display.screenHeight();
        state.view = state.origin ? ClientDisplays.view(display.getBlockPos()) : null;
        state.texture = state.view instanceof ClientWebPanels.WebView web ? ClientWebPanels.texture(web) : null;
        state.webItems = List.of();
        if (state.view instanceof ClientWebPanels.WebView web && !web.overlays().isEmpty()) {
            state.pageWidth = web.width();
            List<WebItem> found = new ArrayList<>();
            for (qbotics.web.WebPainter.Overlay overlay : web.overlays()) {
                net.minecraft.world.item.ItemStack stack = WebItems.stack(overlay.url());
                if (!stack.isEmpty()) {
                    var item = new net.minecraft.client.renderer.item.ItemStackRenderState();
                    items.updateForTopItem(item, stack, net.minecraft.world.item.ItemDisplayContext.GUI, display.getLevel(), null, 0);
                    found.add(new WebItem(overlay.x(), overlay.y(), overlay.width(), overlay.height(), item));
                }
            }
            state.webItems = found;
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.origin || state.view == null) {
            return;
        }
        if (state.view instanceof ClientWebPanels.WebView) {
            submitWeb(state, poseStack, collector);
            return;
        }
        int virtualW;
        int virtualH;
        boolean text = state.view instanceof ClientDisplays.TextView;
        if (state.view instanceof ClientDisplays.TextView view) {
            virtualW = DisplayDrawing.textWidth(view);
            virtualH = DisplayDrawing.textHeight(view);
        } else if (state.view instanceof ClientDisplays.PanelView view) {
            virtualW = DisplayDrawing.panelWidth(view);
            virtualH = DisplayDrawing.panelHeight(view);
        } else {
            return;
        }
        poseStack.pushPose();
        // Turn so that x runs to the screen's right, y up, and z out of its face.
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(state.turn);
        poseStack.translate(-0.5 + INSET, 0.5 - INSET, 0.5 + 0.003);
        float sx = (state.width - 2 * INSET) / virtualW;
        float sy = (state.height - 2 * INSET) / virtualH;
        // y downwards from here, as on a screen (like a sign's text).
        poseStack.scale(sx, -sy, sx);

        WorldCanvas canvas = new WorldCanvas(font, text ? 0 : 0.02f);
        if (state.view instanceof ClientDisplays.TextView view) {
            DisplayDrawing.drawText(canvas, view);
        } else {
            DisplayDrawing.drawPanel(canvas, (ClientDisplays.PanelView) state.view);
        }
        List<WorldCanvas.Fill> fills = canvas.fills;
        collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, buffer) -> {
            for (WorldCanvas.Fill f : fills) {
                buffer.addVertex(pose, f.x0(), f.y0(), f.z()).setColor(f.color()).setLight(FULL_BRIGHT);
                buffer.addVertex(pose, f.x0(), f.y1(), f.z()).setColor(f.color()).setLight(FULL_BRIGHT);
                buffer.addVertex(pose, f.x1(), f.y1(), f.z()).setColor(f.color()).setLight(FULL_BRIGHT);
                buffer.addVertex(pose, f.x1(), f.y0(), f.z()).setColor(f.color()).setLight(FULL_BRIGHT);
            }
        });
        // Item displays: the item renderer, flat on the face.
        for (WorldCanvas.Item item : canvas.items) {
            net.minecraft.world.item.ItemStack stack = WebItems.stack(item.id());
            if (stack.isEmpty()) {
                continue;
            }
            var render = new net.minecraft.client.renderer.item.ItemStackRenderState();
            items.updateForTopItem(render, stack, net.minecraft.world.item.ItemDisplayContext.GUI, net.minecraft.client.Minecraft.getInstance().level, null, 0);
            poseStack.pushPose();
            poseStack.translate(item.x() + item.size() / 2f, item.y() + item.size() / 2f, canvas.layer + 0.3f);
            poseStack.scale(item.size(), -item.size(), item.size() * 0.02f);
            render.submit(poseStack, collector, FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        float textZ = canvas.layer + 0.1f;
        for (WorldCanvas.Text t : canvas.texts) {
            poseStack.pushPose();
            poseStack.translate(t.x(), t.y(), textZ);
            if (t.size() != 1) {
                poseStack.scale(t.size(), t.size(), 1);
            }
            collector.submitText(poseStack, 0, 0, FormattedCharSequence.forward(t.text(), Style.EMPTY), false,
                    Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, t.color(), 0, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** A Web Panel: its page's picture over the whole face. */
    private static void submitWeb(State state, PoseStack poseStack, SubmitNodeCollector collector) {
        if (state.texture == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(state.turn);
        poseStack.translate(-0.5 + INSET, 0.5 - INSET, 0.5 + 0.003);
        float w = state.width - 2 * INSET;
        float h = state.height - 2 * INSET;
        collector.submitCustomGeometry(poseStack, RenderTypes.text(state.texture), (pose, buffer) -> {
            buffer.addVertex(pose, 0, 0, 0).setColor(-1).setUv(0, 0).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, 0, -h, 0).setColor(-1).setUv(0, 1).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, w, -h, 0).setColor(-1).setUv(1, 1).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, w, 0, 0).setColor(-1).setUv(1, 0).setLight(FULL_BRIGHT);
        });
        // Item icons, as the inventory draws them, flattened onto the face.
        float unit = state.pageWidth > 0 ? w / state.pageWidth : 0;
        for (WebItem item : state.webItems) {
            poseStack.pushPose();
            poseStack.translate((item.x() + item.width() / 2) * unit, -(item.y() + item.height() / 2) * unit, 0.01f);
            poseStack.scale(item.width() * unit, item.height() * unit, 0.01f);
            item.item().submit(poseStack, collector, FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true; // a big screen is drawn from one corner, which may be out of sight
    }

    /** Collects what to draw; it is sent to the game's renderer afterwards. */
    static final class WorldCanvas implements DisplayDrawing.Canvas {
        record Text(String text, float x, float y, int color, float size) {
        }

        record Fill(float x0, float y0, float x1, float y1, float z, int color) {
        }

        record Item(String id, int x, int y, int size) {
        }

        final List<Item> items = new ArrayList<>();

        @Override
        public void item(String id, int x, int y, int size) {
            items.add(new Item(id, x, y, size));
        }

        final Font font;
        final List<Fill> fills = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();
        /** Each fill is a little in front of the one before, so later ones cover earlier ones. */
        final float step;
        float layer;

        WorldCanvas(Font font, float step) {
            this.font = font;
            this.step = step;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills.add(new Fill(x0, y0, x1, y1, layer, argb));
            layer += step;
        }

        @Override
        public void text(String text, float x, float y, int argb, float size) {
            if (!text.isEmpty()) {
                texts.add(new Text(text, x, y, argb, size));
            }
        }

        @Override
        public int width(String text) {
            return font.width(text);
        }
    }
}
