package qbotics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.qbot.QbotBlockEntity;
import qbotics.qbot.QbotPartBlock;
import qbotics.qbot.Qbots;

/**
 * Draws a Qbot: its body and two arms, from the part models. It hovers with a slight bob, so
 * its steps read as gliding with a small hop rather than sliding; it turns smoothly, and swings
 * its arms when it digs, places, uses or attacks.
 */
public class QbotRenderer implements BlockEntityRenderer<QbotBlockEntity, QbotRenderer.State> {
    private static final BlockDisplayContext CONTEXT = BlockDisplayContext.create();
    /** How long an arm's swing lasts, in ticks. */
    private static final float SWING_TICKS = 6;
    private final BlockModelResolver models;

    public QbotRenderer(BlockEntityRendererProvider.Context context) {
        this.models = context.blockModelResolver();
    }

    public static final class State extends BlockEntityRenderState {
        final BlockModelRenderState body = new BlockModelRenderState();
        final BlockModelRenderState arm = new BlockModelRenderState();
        float yaw;
        Vec3 offset = Vec3.ZERO;
        float lift;
        float swing;
        boolean swingDown;
        boolean swingUp;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /** How far the model turns for a facing: it is drawn facing north. */
    private static float yawOf(Direction facing) {
        return -(facing.toYRot() + 180);
    }

    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }

    @Override
    public void extractRenderState(QbotBlockEntity qbot, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(qbot, state, partialTicks, cameraPosition, breakProgress);
        QbotPartBlock parts = QboticsContent.QBOT_PART_BLOCK.get();
        models.update(state.body, parts.part(QbotPartBlock.Part.IRON_BODY), CONTEXT);
        models.update(state.arm, parts.part(QbotPartBlock.Part.IRON_ARM), CONTEXT);
        float time = qbot.getLevel() == null ? 0 : qbot.getLevel().getGameTime() + partialTicks;
        // Stepping: from the block it came from, with a small hop.
        Direction from = qbot.stepFrom();
        float step = Mth.clamp((time - qbot.stepAt()) / Qbots.STEP_TICKS, 0, 1);
        if (from != null && step < 1) {
            float left = 1 - ease(step);
            state.offset = new Vec3(from.getStepX() * left, from.getStepY() * left, from.getStepZ() * left);
            state.lift = Mth.sin(step * Mth.PI) * 0.12f;
        } else {
            state.offset = Vec3.ZERO;
            state.lift = 0;
        }
        // Turning: the shorter way round.
        Direction facing = qbot.facing();
        float to = yawOf(facing);
        Direction turned = qbot.turnFrom();
        float turn = Mth.clamp((time - qbot.turnAt()) / Qbots.TURN_TICKS, 0, 1);
        if (turned != null && turn < 1) {
            float was = yawOf(turned);
            state.yaw = was + Mth.wrapDegrees(to - was) * ease(turn);
        } else {
            state.yaw = to;
        }
        // Hovering: a slow bob.
        state.lift += 0.02f + Mth.sin(time * 0.12f) * 0.015f;
        // Swinging its arms at what it works on.
        float swing = (time - qbot.swingAt()) / SWING_TICKS;        state.swing = swing >= 0 && swing < 1 ? Mth.sin(swing * Mth.PI) : 0;
        state.swingDown = qbot.swingSide() == Direction.DOWN;
        state.swingUp = qbot.swingSide() == Direction.UP;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(state.offset.x, state.offset.y + state.lift, state.offset.z);
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(state.yaw));
        poseStack.translate(-0.5, 0, -0.5);
        state.body.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        // Arms hang from shoulders at each side; a swing raises them forwards (or up, or down).
        float angle = state.swing * (state.swingUp ? 150 : state.swingDown ? 30 : 80);
        for (float x : new float[] {0.5f / 16, 13.5f / 16}) {
            poseStack.pushPose();
            poseStack.translate(x + 1 / 16f, 10.5f / 16, 8 / 16f);
            poseStack.mulPose(Axis.XP.rotationDegrees(angle));
            poseStack.translate(-1 / 16f, -10.5f / 16, -8 / 16f);
            state.arm.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true; // while it steps, it is drawn a block away from where it is
    }
}
