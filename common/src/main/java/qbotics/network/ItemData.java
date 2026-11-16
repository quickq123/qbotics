package qbotics.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * What makes one stack more than its kind: its data components, said three ways for
 * programs and the probe (docs/filters.md): as data, as the game's own item syntax that a
 * filter can name exactly, and as the short filter words that hold for it.
 */
public final class ItemData {
    private ItemData() {
    }

    /** Its components that differ from its kind's, as JSON: {"minecraft:damage": 20, ...}. Empty for a plain stack. */
    public static JsonObject data(ItemStack stack, HolderLookup.Provider registries) {
        JsonObject out = new JsonObject();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            String key = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()));
            if (entry.getValue().isEmpty()) {
                out.add("!" + key, com.google.gson.JsonNull.INSTANCE); // taken away from what its kind has
                continue;
            }
            JsonElement value = encode(entry.getKey(), entry.getValue().get(), registries.createSerializationContext(JsonOps.INSTANCE));
            if (value != null) {
                out.add(key, value);
            }
        }
        return out;
    }

    /**
     * A filter naming exactly this stack, in the game's item syntax:
     * minecraft:potion[minecraft:potion_contents={potion:"minecraft:strong_strength"}]. A plain stack is its name alone.
     */
    public static String exactFilter(ItemStack stack, HolderLookup.Provider registries) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return id;
        }
        List<String> parts = new ArrayList<>();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            String key = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()));
            if (entry.getValue().isEmpty()) {
                parts.add("!" + key);
                continue;
            }
            Tag value = encode(entry.getKey(), entry.getValue().get(), ops);
            if (value != null) {
                parts.add(key + "=" + value);
            }
        }
        parts.sort(null);
        return parts.isEmpty() ? id : id + "[" + String.join(",", parts) + "]";
    }

    /** The short filter words that hold for this stack: enchant=..., effect=..., damaged, enchanted, named. */
    public static List<String> words(ItemStack stack) {
        List<String> words = new ArrayList<>();
        for (ItemEnchantments held : List.of(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY),
                stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY))) {
            held.keySet().forEach(holder -> holder.unwrapKey().ifPresent(key -> words.add("enchant=" + shortName(key.identifier().toString()))));
        }
        boolean enchanted = !words.isEmpty();
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null) {
            for (net.minecraft.world.effect.MobEffectInstance effect : potion.getAllEffects()) {
                effect.getEffect().unwrapKey().ifPresent(key -> {
                    String word = "effect=" + shortName(key.identifier().toString());
                    if (!words.contains(word)) {
                        words.add(word);
                    }
                });
            }
        }
        if (stack.isDamaged()) {
            words.add("damaged");
        }
        if (enchanted) {
            words.add("enchanted");
        }
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            words.add("named");
        }
        return words;
    }

    private static String shortName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    @SuppressWarnings("unchecked")
    private static <T, O> O encode(DataComponentType<T> type, Object value, com.mojang.serialization.DynamicOps<O> ops) {
        Codec<T> codec = type.codec();
        return codec == null ? null : codec.encodeStart(ops, (T) value).result().orElse(null);
    }
}
