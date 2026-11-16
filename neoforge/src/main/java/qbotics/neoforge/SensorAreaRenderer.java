package qbotics.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.block.AreaBlockEntity;
import qbotics.client.SensorScreen;

/**
 * Outlines the box an Entity Sensor or a Collector works while its screen's Show area is on: the twelve edges
 * of its area, in cyan, with a fainter mark at each block along the top edges to count by.
 */
final class SensorAreaRenderer<T extends AreaBlockEntity> implements BlockEntityRenderer<T, SensorAreaRenderer.State> {
    private static final int EDGE = 0xFF40E0FF;
    private static final int TICK = 0x8040E0FF;

    SensorAreaRenderer(BlockEntityRendererProvider.Context context) {
    }

    static final class State extends BlockEntityRenderState {
        boolean shown;
        int radius;
        int height;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T sensor, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(sensor, state, partialTicks, cameraPosition, breakProgress);
        state.shown = SensorScreen.shown(sensor.getBlockPos());
        state.radius = sensor.radius();
        state.height = sensor.height();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.shown) {
            return;
        }
        // The pose is at the sensor block's corner: the box runs from radius (height) out on each side.
        float x0 = -state.radius;
        float x1 = state.radius + 1;
        float y0 = -state.height;
        float y1 = state.height + 1;
        float z0 = -state.radius;
        float z1 = state.radius + 1;
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            float[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] edge : edges) {
                line(buffer, pose, corners[edge[0]], corners[edge[1]], EDGE, 3.0f);
            }
            // Short marks hanging from the top edges at every block, to count the size by (the
            // bottom is often underground).
            for (float x = x0 + 1; x < x1; x++) {
                line(buffer, pose, new float[] {x, y1, z0}, new float[] {x, y1 - 0.5f, z0}, TICK, 1.5f);
                line(buffer, pose, new float[] {x, y1, z1}, new float[] {x, y1 - 0.5f, z1}, TICK, 1.5f);
            }
            for (float z = z0 + 1; z < z1; z++) {
                line(buffer, pose, new float[] {x0, y1, z}, new float[] {x0, y1 - 0.5f, z}, TICK, 1.5f);
                line(buffer, pose, new float[] {x1, y1, z}, new float[] {x1, y1 - 0.5f, z}, TICK, 1.5f);
            }
        });
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, float[] a, float[] b, int colour, float width) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float dz = b[2] - a[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        buffer.addVertex(pose, a[0], a[1], a[2]).setColor(colour).setNormal(pose, dx / length, dy / length, dz / length).setLineWidth(width);
        buffer.addVertex(pose, b[0], b[1], b[2]).setColor(colour).setNormal(pose, dx / length, dy / length, dz / length).setLineWidth(width);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public AABB getRenderBoundingBox(T sensor) {
        return sensor.area();
    }
}
