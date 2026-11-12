package qbotics.client.display;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Items' icons as pictures, for a page's canvas (drawImage of item:minecraft:diamond): the
 * item's own texture, or for a block its side's. (On the page itself, item icons are drawn by
 * the game's item renderer instead, in 3D for blocks.) Safe to use from any thread.
 */
final class WebItemImages {
    private WebItemImages() {
    }

    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private static final Map<String, BufferedImage> ICONS = new ConcurrentHashMap<>();

    /** An item's icon by id ("minecraft:diamond", or "diamond"), or null if there is none. */
    static BufferedImage icon(String id) {
        BufferedImage icon = ICONS.computeIfAbsent(id.strip(), WebItemImages::load);
        return icon == NONE ? null : icon;
    }

    private static BufferedImage load(String id) {
        Identifier name = Identifier.tryParse(id);
        if (name == null) {
            return NONE;
        }
        for (String path : new String[] {"item/" + name.getPath(), "block/" + name.getPath(), "block/" + name.getPath() + "_side",
                "block/" + name.getPath() + "_front", "block/" + name.getPath() + "_top"}) {
            BufferedImage found = read(Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + path + ".png"));
            if (found != null) {
                return found;
            }
        }
        return NONE;
    }

    /** A texture's first frame (a square from its top). */
    private static BufferedImage read(Identifier file) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int size = image.getWidth();
            BufferedImage out = new BufferedImage(size, Math.min(size, image.getHeight()), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < out.getHeight(); y++) {
                for (int x = 0; x < size; x++) {
                    out.setRGB(x, y, image.getPixel(x, y));
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
