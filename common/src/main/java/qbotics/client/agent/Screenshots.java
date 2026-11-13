package qbotics.client.agent;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * A picture of the player's view for the AI agent's model, as a PNG data URL. Made smaller
 * (at most 1024 pixels wide) so a local model reads it quickly.
 */
final class Screenshots {
    private static final int MAX_WIDTH = 1024;

    private Screenshots() {
    }

    static CompletableFuture<String> capture() {
        CompletableFuture<String> done = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
                    try (image) {
                        done.complete(encode(image));
                    } catch (Exception e) {
                        done.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    private static String encode(NativeImage image) throws java.io.IOException {
        int step = Math.max(1, (image.getWidth() + MAX_WIDTH - 1) / MAX_WIDTH);
        Path file = Files.createTempFile("qbotics-ai-view", ".png");
        try {
            if (step == 1) {
                image.writeToFile(file);
            } else {
                try (NativeImage small = new NativeImage(image.getWidth() / step, image.getHeight() / step, false)) {
                    for (int y = 0; y < small.getHeight(); y++) {
                        for (int x = 0; x < small.getWidth(); x++) {
                            small.setPixel(x, y, image.getPixel(x * step, y * step));
                        }
                    }
                    small.writeToFile(file);
                }
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
