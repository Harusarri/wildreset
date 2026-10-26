package kr.harusarri.wildreset.mixin;

import kr.harusarri.wildreset.QuarantineAndFreezeManager;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces:
 * 1. In-Zone Packet Freeze: drops movement, block interaction, item use, and container clicks
 *    while KeepAlive packets continue flowing normally.
 * 2. Outer +10-Chunk Quarantine & Protected-Zone Boundary: blocks players from walking into
 *    resetting wilderness chunks during the reset swap.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketFreezeMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void teleport(double x, double y, double z, float yRot, float xRot);

    private boolean wildreset$isMainThreadInOverworld() {
        return player != null
            && player.level() != null
            && player.level().getServer() != null
            && player.level().getServer().isSameThread()
            && player.level() == player.level().getServer().overworld();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void wildreset$onHandleMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!wildreset$isMainThreadInOverworld()) return;

        if (QuarantineAndFreezeManager.isPlayerFrozen(player.getUUID())) {
            ci.cancel();
            return;
        }

        if (QuarantineAndFreezeManager.isQuarantineActive()) {
            double toX = packet.getX(player.getX());
            double toY = packet.getY(player.getY());
            double toZ = packet.getZ(player.getZ());
            if (QuarantineAndFreezeManager.shouldBlockMovementTo(player, toX, toY, toZ)) {
                this.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                ci.cancel();
            }
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void wildreset$onHandlePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (wildreset$isMainThreadInOverworld() && QuarantineAndFreezeManager.isPlayerFrozen(player.getUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void wildreset$onHandleUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (wildreset$isMainThreadInOverworld() && QuarantineAndFreezeManager.isPlayerFrozen(player.getUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void wildreset$onHandleUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (wildreset$isMainThreadInOverworld() && QuarantineAndFreezeManager.isPlayerFrozen(player.getUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void wildreset$onHandleContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (wildreset$isMainThreadInOverworld() && QuarantineAndFreezeManager.isPlayerFrozen(player.getUUID())) {
            ci.cancel();
        }
    }
}
