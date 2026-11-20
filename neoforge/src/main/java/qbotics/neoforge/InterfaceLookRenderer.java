package qbotics.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import qbotics.block.EnergyInterfaceBlockEntity;
import qbotics.block.Experience;
import qbotics.block.ExperienceInterfaceBlockEntity;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.network.FluidPort;

/**
 * Draws what an Interface holds into the windows on its four sides: the Interface's nine
 * slot lights, the Fluid Interface's four tanks (in each fluid's own picture and colour),
 * the Energy Interface's charge and the Experience Interface's bottle with its levels. The
 * windows are the cells drawn in tools/make_assets.py; the cells here must match them.
 */
final class InterfaceLookRenderer<T extends BlockEntity> implements BlockEntityRenderer<T, InterfaceLookRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    /** Just in front of the face, so the drawing is not lost in it. */
    private static final float PROUD = 0.0015f;

    /** The Interface's slot lights: open and empty, holding something, full. */
    private static final int[] SLOT_COLOURS = {0, 0xFF2C4A34, 0xFF50E660, 0xFFFFB030};
    private static final int CHARGE = 0xFFFF4830;
    private static final int XP = 0xFF80E040;
    /** A bottle shows itself full at this many levels; the number tells the rest. */
    private static final int XP_FULL_AT = 30;

    // The windows, in the side texture's pixels: x0, y0, x1, y1 of the frame (the inside is one pixel in).
    private static final int[][] TANKS = {{1, 1, 8, 8}, {8, 1, 15, 8}, {1, 8, 8, 15}, {8, 8, 15, 15}};
    private static final int[][] CELLS = {{3, 2, 6, 14}, {6, 2, 10, 14}, {10, 2, 13, 14}};
    private static final int[] BOTTLE_NECK = {5, 2, 11, 5};
    private static final int[] BOTTLE = {3, 5, 13, 14};

    private final Font font;

    InterfaceLookRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    enum Kind { NONE, ITEMS, FLUIDS, ENERGY, EXPERIENCE }

    record TankLook(TextureAtlasSprite sprite, int colour, float fill, boolean glows) {
    }

    static final class State extends BlockEntityRenderState {
        Kind kind = Kind.NONE;
        /** The light in front of each side, in SIDES' order. */
        final int[] light = new int[4];
        final boolean[] hidden = new boolean[4];
        final int[] slots = new int[InterfaceBlockEntity.SLOTS];
        final @Nullable TankLook[] tanks = new TankLook[FluidInterfaceBlockEntity.TANKS];
        float fill;
        String label = "";
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T entity, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
        Level level = entity.getLevel();
        BlockPos pos = entity.getBlockPos();
        for (int i = 0; i < SIDES.length; i++) {
            BlockPos front = pos.relative(SIDES[i]);
            state.hidden[i] = level != null && level.getBlockState(front).isSolidRender();
            state.light[i] = level != null ? LevelRenderer.getLightCoords(level, front) : FULL_BRIGHT;
        }
        state.kind = Kind.NONE;
        switch (entity) {
            case InterfaceBlockEntity items -> {
                state.kind = Kind.ITEMS;
                for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
                    state.slots[slot] = items.slotLight(slot);
                }
            }
            case FluidInterfaceBlockEntity fluids -> {
                state.kind = Kind.FLUIDS;
                java.util.List<FluidPort.Tank> tanks = fluids.tanks();
                for (int i = 0; i < state.tanks.length; i++) {
                    FluidPort.Tank tank = i < tanks.size() ? tanks.get(i) : null;
                    state.tanks[i] = tank == null || tank.amount() <= 0 ? null
                            : tankLook(tank.fluid(), Math.min(1f, tank.amount() / (float) tank.capacity()), level, pos);
                }
            }
            case EnergyInterfaceBlockEntity energy -> {
                state.kind = Kind.ENERGY;
                state.fill = energy.stored() <= 0 ? 0 : Math.max(0.04f, energy.stored() / (float) energy.capacity());
            }
            case ExperienceInterfaceBlockEntity experience -> {
                state.kind = Kind.EXPERIENCE;
                long points = experience.points();
                long levels = Experience.levelAt(points);
                state.fill = points <= 0 ? 0 : Math.max(0.08f, Math.min(1f, levels / (float) XP_FULL_AT));
                state.label = points <= 0 ? "" : shortNumber(levels);
            }
            default -> {
            }
        }
    }

    /** How a fluid looks in the game: its still picture, tinted as it is where the block stands. */
    private static @Nullable TankLook tankLook(Fluid fluid, float fill, @Nullable Level level, BlockPos pos) {
        try {
            FluidState fluidState = fluid.defaultFluidState();
            var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
            TextureAtlasSprite sprite = model.stillMaterial().sprite();
            int colour = -1;
            if (model.fluidTintSource() != null) {
                colour = level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter world
                        ? model.fluidTintSource().colorInWorld(fluidState, fluidState.createLegacyBlock(), world, pos)
                        : model.fluidTintSource().color(fluidState);
            }
            boolean glows = fluidState.createLegacyBlock().getLightEmission() > 0;
            return new TankLook(sprite, colour | 0xFF000000, fill, glows);
        } catch (RuntimeException e) {
            return null; // a fluid some mod left without a look: the tank shows empty
        }
    }

    /** 12, 345, 6789, 12k, 3.4M: what fits in the bottle. */
    static String shortNumber(long n) {
        if (n < 10_000) {
            return Long.toString(n);
        }
        String[] units = {"k", "M", "G"};
        double value = n;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return (value < 10 ? String.format(java.util.Locale.ROOT, "%.1f", value) : Long.toString((long) value)) + units[unit];
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.kind == Kind.NONE) {
            return;
        }
        for (int i = 0; i < SIDES.length; i++) {
            if (state.hidden[i]) {
                continue;
            }
            poseStack.pushPose();
            // Turn so that x runs to the right of someone facing this side, y up and z out of it,
            // then to the face's top-left corner, in pixels with y downwards (as in the texture).
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(-SIDES[i].toYRot()));
            poseStack.translate(-0.5, 0.5, 0.5 + PROUD);
            poseStack.scale(1 / 16f, -1 / 16f, 1 / 16f);
            drawSide(state, state.light[i], poseStack, collector);
            poseStack.popPose();
        }
    }

    private void drawSide(State state, int light, PoseStack poseStack, SubmitNodeCollector collector) {
        switch (state.kind) {
            case ITEMS -> collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, buffer) -> {
                for (int slot = 0; slot < state.slots.length; slot++) {
                    int colour = SLOT_COLOURS[state.slots[slot]];
                    if (colour != 0) {
                        float x = 3 + slot % 3 * 4;
                        float y = 3 + slot / 3 * 4;
                        flat(buffer, pose, x, y, x + 2, y + 2, colour, FULL_BRIGHT);
                    }
                }
            });
            case FLUIDS -> {
                for (int i = 0; i < state.tanks.length; i++) {
                    TankLook tank = state.tanks[i];
                    if (tank == null) {
                        continue;
                    }
                    int[] cell = TANKS[i];
                    float x0 = cell[0] + 1;
                    float x1 = cell[2] - 1;
                    float bottom = cell[3] - 1;
                    float top = bottom - tank.fill() * (bottom - (cell[1] + 1));
                    int lit = tank.glows() ? FULL_BRIGHT : light;
                    TextureAtlasSprite sprite = tank.sprite();
                    // The fluid's picture at the block's own scale: each pixel of the face shows its pixel of it.
                    float u0 = sprite.getU(x0 / 16);
                    float u1 = sprite.getU(x1 / 16);
                    float v0 = sprite.getV(top / 16);
                    float v1 = sprite.getV(bottom / 16);
                    collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(sprite.atlasLocation()), (pose, buffer) -> {
                        textured(buffer, pose, x0, top, u0, v0, tank.colour(), lit);
                        textured(buffer, pose, x0, bottom, u0, v1, tank.colour(), lit);
                        textured(buffer, pose, x1, bottom, u1, v1, tank.colour(), lit);
                        textured(buffer, pose, x1, top, u1, v0, tank.colour(), lit);
                    });
                }
            }
            case ENERGY -> {
                if (state.fill > 0) {
                    collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, buffer) -> {
                        for (int[] cell : CELLS) {
                            fillFromBottom(buffer, pose, cell, state.fill, CHARGE);
                        }
                    });
                }
            }
            case EXPERIENCE -> {
                if (state.fill > 0) {
                    collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, buffer) -> {
                        fillFromBottom(buffer, pose, BOTTLE, state.fill, XP);
                        if (state.fill >= 1) {
                            fillFromBottom(buffer, pose, BOTTLE_NECK, 1, XP);
                        }
                    });
                }
                if (!state.label.isEmpty()) {
                    drawLabel(state.label, poseStack, collector);
                }
            }
            default -> {
            }
        }
    }

    /** The bottle's number, as big as fits across it, in the middle of it. */
    private void drawLabel(String label, PoseStack poseStack, SubmitNodeCollector collector) {
        float roomWide = BOTTLE[2] - BOTTLE[0] - 2;
        float roomHigh = 5;
        int width = font.width(label);
        float scale = Math.min(roomHigh / 8f, roomWide / width);
        float centreX = (BOTTLE[0] + BOTTLE[2]) / 2f;
        float centreY = (BOTTLE[1] + 1 + BOTTLE[3] - 1) / 2f;
        poseStack.pushPose();
        poseStack.translate(centreX - width * scale / 2, centreY - 7 * scale / 2, 0.02f);
        poseStack.scale(scale, scale, scale);
        collector.submitText(poseStack, 0, 0, FormattedCharSequence.forward(label, Style.EMPTY), false,
                Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, 0xFFFFFFFF, 0, 0xFF1C3410);
        poseStack.popPose();
    }

    private static void fillFromBottom(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose, int[] cell, float fill, int colour) {
        float bottom = cell[3] - 1;
        float top = bottom - fill * (bottom - (cell[1] + 1));
        flat(buffer, pose, cell[0] + 1, top, cell[2] - 1, bottom, colour, FULL_BRIGHT);
    }

    /** A plain rectangle, in pixels with y downwards. */
    private static void flat(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose,
            float x0, float y0, float x1, float y1, int colour, int light) {
        buffer.addVertex(pose, x0, y0, 0).setColor(colour).setLight(light);
        buffer.addVertex(pose, x0, y1, 0).setColor(colour).setLight(light);
        buffer.addVertex(pose, x1, y1, 0).setColor(colour).setLight(light);
        buffer.addVertex(pose, x1, y0, 0).setColor(colour).setLight(light);
    }

    private static void textured(com.mojang.blaze3d.vertex.VertexConsumer buffer, PoseStack.Pose pose,
            float x, float y, float u, float v, int colour, int light) {
        // The pose turns y over, but the face still looks out along +z.
        buffer.addVertex(pose, x, y, 0).setColor(colour).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, 0, 0, 1);
    }
}
