package qbotics.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qbotics.block.BlockSensorBlockEntity;

/**
 * Hears every real block change on the server (the game tells its villager workstations the
 * same way), for Block Sensors that a program is watching. With none watched, it does nothing.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelBlockChangeMixin {
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
    private void qbotics$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo info) {
        BlockSensorBlockEntity.blockChanged((ServerLevel) (Object) this, pos, oldState, newState);
        qbotics.network.CableNetwork.blockChanged((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
