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

    public DisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    public static final class State extends BlockEntityRenderState {
        boolean origin;
        Direction facing = Direction.NORTH;
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
        state.width = display.screenWidth();
        state.height = display.screenHeight();
        state.view = state.origin ? ClientDisplays.view(display.getBlockPos()) : null;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.origin || state.view == null) {
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
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
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
