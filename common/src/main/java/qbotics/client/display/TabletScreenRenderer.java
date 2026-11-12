package qbotics.client.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;

/**
 * Draws a held tablet's selected screen on its glass (design doc §10.6), in the model's own
 * space: the tablet stands in the block's middle, its screen facing south (+z).
 */
public final class TabletScreenRenderer implements SpecialModelRenderer<UUID> {
    // The glass, in the model's pixels (see the tablet model in make_assets.py): 14 wide, 3:2.
    private static final float LEFT = 1 / 16f;
    private static final float WIDTH = 14 / 16f;
    private static final float HEIGHT = WIDTH * 2 / 3;
    private static final float TOP = 8.5f / 16 + HEIGHT / 2;
    private static final float FACE = 8.5f / 16 + 0.002f;

    @Override
    public @Nullable UUID extractArgument(ItemStack stack) {
        return stack.get(QboticsContent.TABLET_ID.get());
    }

    @Override
    public void submit(@Nullable UUID tablet, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
        BlockPos screen = ClientTablets.screen(tablet);
        Object view = screen == null ? null : ClientDisplays.view(screen);
        if (view == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(LEFT, TOP, FACE);
        DisplayRenderer.drawPicture(view, WIDTH, HEIGHT, poseStack, collector, minecraft.font, minecraft.getItemModelResolver());
        poseStack.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(LEFT, TOP, FACE));
        output.accept(new Vector3f(LEFT + WIDTH, TOP - HEIGHT, FACE));
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked<UUID> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public SpecialModelRenderer<UUID> bake(SpecialModelRenderer.BakingContext context) {
            return new TabletScreenRenderer();
        }

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }
    }
}
