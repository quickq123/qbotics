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

    public static final class State extends BlockEntityRenderState {
        boolean origin;
        Direction facing = Direction.NORTH;
        /** Turns the block's frame so x runs along the screen's rows, y up its columns and z out of its face. */
        org.joml.Quaternionf turn = new org.joml.Quaternionf();
        int width;
        int height;
        @Nullable Object view;
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
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.origin || state.view == null) {
            return;
        }
        poseStack.pushPose();
        // Turn so that x runs to the screen's right, y up, and z out of its face.
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(state.turn);
        poseStack.translate(-0.5 + INSET, 0.5 - INSET, 0.5 + 0.003);
        drawPicture(state.view, state.width - 2 * INSET, state.height - 2 * INSET, poseStack, collector, font, items);
        poseStack.popPose();
    }

    /**
     * Draws what a screen shows (a text, panel or web view), w by h, with the pose at its
     * top-left corner: x to the right, y up and z out of it. For display blocks and tablets.
     */
    public static void drawPicture(Object view, float w, float h, PoseStack poseStack, SubmitNodeCollector collector, Font font,
            net.minecraft.client.renderer.item.ItemModelResolver items) {
        if (view instanceof ClientWebPanels.WebView web) {
            drawWeb(web, w, h, poseStack, collector, items);
            return;
        }
        int virtualW;
        int virtualH;
        boolean text = view instanceof ClientDisplays.TextView;
        if (view instanceof ClientDisplays.TextView textView) {
            virtualW = DisplayDrawing.textWidth(textView);
            virtualH = DisplayDrawing.textHeight(textView);
        } else if (view instanceof ClientDisplays.PanelView panelView) {
            virtualW = DisplayDrawing.panelWidth(panelView);
            virtualH = DisplayDrawing.panelHeight(panelView);
        } else {
            return;
        }
        poseStack.pushPose();
        float sx = w / virtualW;
        float sy = h / virtualH;
        // y downwards from here, as on a screen (like a sign's text).
        poseStack.scale(sx, -sy, sx);

        WorldCanvas canvas = new WorldCanvas(font, text ? 0 : 0.02f);
        if (view instanceof ClientDisplays.TextView textView) {
            DisplayDrawing.drawText(canvas, textView);
        } else {
            DisplayDrawing.drawPanel(canvas, (ClientDisplays.PanelView) view);
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

    /** A Web Panel's page: its picture over the whole face, and the item icons over that. */
    private static void drawWeb(ClientWebPanels.WebView web, float w, float h, PoseStack poseStack, SubmitNodeCollector collector,
            net.minecraft.client.renderer.item.ItemModelResolver items) {
        net.minecraft.resources.Identifier texture = ClientWebPanels.texture(web);
        if (texture == null) {
            return;
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.text(texture), (pose, buffer) -> {
            buffer.addVertex(pose, 0, 0, 0).setColor(-1).setUv(0, 0).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, 0, -h, 0).setColor(-1).setUv(0, 1).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, w, -h, 0).setColor(-1).setUv(1, 1).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, w, 0, 0).setColor(-1).setUv(1, 0).setLight(FULL_BRIGHT);
        });
        // Item icons, as the inventory draws them, flattened onto the face.
        float unit = web.width() > 0 ? w / web.width() : 0;
        for (qbotics.web.WebPainter.Overlay overlay : web.overlays()) {
            net.minecraft.world.item.ItemStack stack = WebItems.stack(overlay.url());
            if (stack.isEmpty()) {
                continue;
            }
            var item = new net.minecraft.client.renderer.item.ItemStackRenderState();
            items.updateForTopItem(item, stack, net.minecraft.world.item.ItemDisplayContext.GUI, net.minecraft.client.Minecraft.getInstance().level, null, 0);
            poseStack.pushPose();
            poseStack.translate((overlay.x() + overlay.width() / 2) * unit, -(overlay.y() + overlay.height() / 2) * unit, 0.01f);
            poseStack.scale(overlay.width() * unit, overlay.height() * unit, 0.01f);
            item.submit(poseStack, collector, FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
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
