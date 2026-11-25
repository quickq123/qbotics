package qbotics.block;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import qbotics.Qbotics;
import qbotics.QboticsContent;

/**
 * A Qbotics item alone in a crafting grid comes out reset, as a newly made one: a bus, an
 * interface or any other device picked up after it was named or filled, which wouldn't stack
 * with new ones. Its name goes, and so does the fluid, energy and experience stored in it,
 * which are destroyed. Items and files stay: a Qbot's load, a drive's files, a tablet's
 * pairing.
 */
public class ResetRecipe extends CustomRecipe {
    public static final ResetRecipe INSTANCE = new ResetRecipe();
    public static final MapCodec<ResetRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, ResetRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<ResetRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    /** What a reset takes off: the name, and the stored fluid and energy (custom data) and experience. */
    private static List<DataComponentType<?>> cleared() {
        return List.of(DataComponents.CUSTOM_NAME, DataComponents.CUSTOM_DATA, QboticsContent.XP_POINTS.get());
    }

    /** The one thing in the grid, if it is a Qbotics item with something to reset; otherwise nothing. */
    private static ItemStack resettable(CraftingInput input) {
        if (input.ingredientCount() != 1) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty()) {
                boolean ours = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(Qbotics.MOD_ID);
                return ours && cleared().stream().anyMatch(stack::has) ? stack : ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !resettable(input).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack stack = resettable(input);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack plain = stack.copyWithCount(1);
        for (DataComponentType<?> type : cleared()) {
            plain.remove(type);
        }
        return plain;
    }

    @Override
    public RecipeSerializer<ResetRecipe> getSerializer() {
        return SERIALIZER;
    }
}
