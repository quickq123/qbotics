package qbotics.neoforge.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the player's game sends a screen-button click to the server. The game has no event
 * for this, and it is the one place every such click passes, whichever screen or mod it comes
 * from, so the Network Probe's Slots mode can say which number was pressed.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class ButtonClickMixin {
    @Inject(method = "handleInventoryButtonClick", at = @At("HEAD"))
    private void qbotics$showButton(int containerId, int buttonId, CallbackInfo info) {
        qbotics.client.SlotLabels.buttonPressed(buttonId);
    }
}
