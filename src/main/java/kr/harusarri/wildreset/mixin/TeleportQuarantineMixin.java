package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.QuarantineAndFreezeManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Blocks Portals, Chorus Fruit, /home, /warp, and /tp into resetting wilderness chunks
 * while the +10-Chunk Quarantine is active (whilst allowing the engine's vertical Y-snap
 * via ALLOW_INTERNAL_TELEPORT).
 */
@Mixin(ServerPlayer.class)
public abstract class TeleportQuarantineMixin {

    @Inject(
        method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void wildreset$onTeleportTransition(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        if (transition == null) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        Vec3 pos = transition.position();
        if (QuarantineAndFreezeManager.shouldBlockTeleportTo(transition.newLevel(), pos.x, pos.y, pos.z, self)) {
            cir.setReturnValue(self);
        }
    }

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void wildreset$onTeleportTo(
        ServerLevel level,
        double x,
        double y,
        double z,
        Set<Relative> relatives,
        float yRot,
        float xRot,
        boolean resetCamera,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (QuarantineAndFreezeManager.shouldBlockTeleportTo(level, x, y, z, self)) {
            cir.setReturnValue(false);
        }
    }
}
