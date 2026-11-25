package qbotics.block;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import qbotics.QboticsContent;

/**
 * What a picked-up Fluid, Energy or Experience Interface carries as an item, and the lines its
 * tooltip gives for it. Fluid and energy travel in the item's custom data ({@code fluid0},
 * {@code amount0} and on; {@code energy}), as a Qbot's do.
 */
public final class Carried {
    private Carried() {
    }

    /** The fluid named under a key of an item's custom data, or EMPTY. */
    public static Fluid fluid(CompoundTag tag, String key) {
        Identifier id = Identifier.tryParse(tag.getStringOr(key, ""));
        return id != null && BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.getValue(id) : Fluids.EMPTY;
    }

    /** Adds to an item's tooltip what the interface holds, under its name. */
    public static void tooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(qbotics.Qbotics.MOD_ID)) {
            return;
        }
        int at = Math.min(1, lines.size());
        Long points = stack.get(QboticsContent.XP_POINTS.get());
        if (points != null && points > 0) {
            lines.add(at++, Component.translatable("tooltip.qbotics.carried.levels", String.format("%,d", Experience.levelAt(points)))
                    .withStyle(ChatFormatting.GRAY));
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();
        int energy = tag.getIntOr("energy", 0);
        if (energy > 0) {
            lines.add(at++, Component.translatable("tooltip.qbotics.carried.energy", String.format("%,d", energy)).withStyle(ChatFormatting.GRAY));
        }
        for (int tank = 0; tank < FluidInterfaceBlockEntity.TANKS; tank++) {
            Fluid fluid = fluid(tag, "fluid" + tank);
            int amount = tag.getIntOr("amount" + tank, 0);
            if (fluid != Fluids.EMPTY && amount > 0) {
                BlockState block = fluid.defaultFluidState().createLegacyBlock();
                Component name = block.is(Blocks.AIR) ? Component.literal(BuiltInRegistries.FLUID.getKey(fluid).toString()) : block.getBlock().getName();
                lines.add(at++, Component.translatable("tooltip.qbotics.carried.fluid", String.format("%,d", amount), name)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
