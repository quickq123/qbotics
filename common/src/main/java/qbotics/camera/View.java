package qbotics.camera;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A Qbot's view (qbot.view()): the blocks it can see a face of, and the creatures in sight, as
 * {@link ConeView} lists them. Each player's game draws it (qbotics.client.display.ConeRenderer).
 *
 * @param origin the Qbot's block: positions are counted from it, and colours that depend on the
 *               place (grass, water) are taken there
 */
public record View(int width, int height, BlockPos origin, Direction facing, float pitch, List<ConeView.Seen> blocks,
        List<ConeView.Creature> creatures) implements Shot {
}
