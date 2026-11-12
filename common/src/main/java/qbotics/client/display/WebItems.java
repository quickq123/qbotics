package qbotics.client.display;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Items named in Web Panel pages ({@code item:minecraft:iron_ingot}), for drawing their icons. */
final class WebItems {
    private WebItems() {
    }

    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    /** The item named (namespace optional: "iron_ingot" is minecraft's), or an empty stack if there is none. */
    static ItemStack stack(String id) {
        return STACKS.computeIfAbsent(id, key -> {
            Identifier name = Identifier.tryParse(key.strip());
            if (name == null) {
                return ItemStack.EMPTY;
            }
            return BuiltInRegistries.ITEM.getOptional(name).map(ItemStack::new).orElse(ItemStack.EMPTY);
        });
    }
}
