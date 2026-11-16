package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import qbotics.Qbotics;

/**
 * A stand-in's swing, made the swing a player's would be. A stand-in is never ticked, so two
 * things a player gets from ticking are done here by hand: the weapon in its hand adds its
 * damage and speed (the game adds those when it notices a change of hand, on a tick), and the
 * cooldown since the last swing recovers. Used by Entity Sensors and Qbots, which each remember
 * when they last swung.
 */
final class Swings {
    private Swings() {
    }

    /** What arm() put on a stand-in, to take off again. */
    record Armed(ServerPlayer player, List<Map.Entry<AttributeInstance, AttributeModifier>> added) {
        void disarm() {
            added.forEach(entry -> entry.getKey().removeModifier(entry.getValue()));
        }
    }

    /** Puts the weapon in the stand-in's hand and its damage and speed on the stand-in. */
    static Armed arm(ServerPlayer player, ItemStack weapon) {
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, weapon);
        List<Map.Entry<AttributeInstance, AttributeModifier>> added = new ArrayList<>();
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null && !instance.hasModifier(modifier.id())) {
                instance.addTransientModifier(modifier);
                added.add(Map.entry(instance, modifier));
            }
        });
        return new Armed(player, added);
    }

    /** Ticks since the last swing, as the cooldown counts them (capped). */
    static int since(long now, long lastSwing) {
        return (int) Math.min(Math.max(0, now - lastSwing), 10_000);
    }

    /** How many more ticks a full swing with what is in hand needs; 0 if it is ready. */
    static int waitFor(ServerPlayer player, int since) {
        float delay = player.getCurrentItemAttackStrengthDelay();
        return since >= delay ? 0 : (int) Math.ceil(delay - since);
    }

    /** Swings at a target as hard as the cooldown since the last swing allows. */
    static void swing(ServerPlayer player, net.minecraft.world.entity.Entity target, int since) {
        Qbotics.platform().chargeAttack(player, since);
        player.attack(target);
    }
}
