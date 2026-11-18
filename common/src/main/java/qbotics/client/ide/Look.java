package qbotics.client.ide;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/** Something Ctrl+clicked in the code, shown over the editor: a device (DeviceLook) or a storage (StorageLook). */
interface Look {
    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);

    void resize(int width, int height);

    /** What it says, for the screenshots and tests. */
    List<String> text();

    /** A click on something in it that opens another look: {kind, address}; null if the click closes it. */
    default String @Nullable [] clicked(double mouseX, double mouseY) {
        return null;
    }
}
