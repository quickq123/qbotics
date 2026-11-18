package qbotics.client.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import qbotics.ide.WorldHints;

/** Every item and item tag this game knows (every mod's), for the editor's suggestions in item strings. */
final class ItemList {
    private ItemList() {
    }

    /** The names, each item with how many the network holds (from held, by "minecraft:coal"). */
    static List<WorldHints.ItemName> names(Map<String, Long> held) {
        List<WorldHints.ItemName> names = new ArrayList<>();
        for (var key : BuiltInRegistries.ITEM.keySet()) {
            String id = key.toString();
            if (!id.equals("minecraft:air")) {
                names.add(new WorldHints.ItemName(id, held.getOrDefault(id, 0L)));
            }
        }
        BuiltInRegistries.ITEM.getTags().forEach(tag -> names.add(new WorldHints.ItemName("#" + tag.key().location(), 0)));
        return List.copyOf(names);
    }
}
