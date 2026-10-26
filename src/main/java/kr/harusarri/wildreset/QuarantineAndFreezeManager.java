package kr.harusarri.wildreset;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QuarantineAndFreezeManager {
    public static final ThreadLocal<Boolean> ALLOW_INTERNAL_TELEPORT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Set<UUID> FROZEN_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_QUARANTINE_WARN_MS = new ConcurrentHashMap<>();

    private static volatile boolean quarantineActive = false;
    private static volatile YawpProtectionIndex activeIndex = null;
    private static ServerBossEvent bossBarEn = null;
    private static ServerBossEvent bossBarKo = null;
    private static int hideBossBarTicksRemaining = 0;

    private QuarantineAndFreezeManager() {}

    public static boolean isQuarantineActive() {
        return quarantineActive && activeIndex != null;
    }

    public static YawpProtectionIndex getActiveIndex() {
        return activeIndex;
    }

    public static boolean isPlayerFrozen(UUID playerUuid) {
        return playerUuid != null && FROZEN_PLAYERS.contains(playerUuid);
    }

    public static void activateQuarantine(MinecraftServer server, ServerLevel level, YawpProtectionIndex index) {
        activeIndex = index;
        quarantineActive = true;

        WildResetConfig cfg = WildResetConfig.get();
        for (ServerPlayer player : level.players()) {
            int cx = player.chunkPosition().x();
            int cz = player.chunkPosition().z();
            if (index.isChunkResetTarget(cx, cz)) {
                freezePlayer(player, cfg);
            }
        }
    }

    public static void freezePlayer(ServerPlayer player, WildResetConfig cfg) {
        if (player == null) return;
        // 1. Force-close any open container GUI so chest item dupe is 100% impossible
        try {
            player.closeContainer();
        } catch (Throwable ignored) {}

        // 2. Mark player as frozen in the inbound packet gate
        FROZEN_PLAYERS.add(player.getUUID());

        // 3. Send vanilla 26.3 ClientboundTickingStatePacket(20.0f, true) to freeze client physics/gravity in place
        player.connection.send(new ClientboundTickingStatePacket(20.0f, true));
        String tpl = WildResetI18n.resolvePlayerTemplate(
            player,
            "msg.freeze.in_zone.actionbar",
            cfg.messages.inZoneFreezeActionBar,
            WildResetI18n.DEFAULT_KO_MESSAGES.inZoneFreezeActionBar
        );
        MiniText.sendActionBar(player, tpl, Map.of());
    }

    public static void unfreezePlayerAfterChunkSwap(ServerPlayer player, int newSurfaceY, WildResetConfig cfg) {
        if (player == null) return;
        FROZEN_PLAYERS.remove(player.getUUID());
        player.connection.send(new ClientboundTickingStatePacket(20.0f, false));
        String tpl = WildResetI18n.resolvePlayerTemplate(
            player,
            "msg.freeze.resumed.chat",
            cfg.messages.inZoneResumedChat,
            WildResetI18n.DEFAULT_KO_MESSAGES.inZoneResumedChat
        );
        MiniText.sendChat(player, tpl, Map.of(
            "y", String.valueOf(newSurfaceY),
            "hours", String.valueOf(cfg.chestProtectionHours)
        ));
    }

    public static void deactivateQuarantine(MinecraftServer server) {
        quarantineActive = false;
        activeIndex = null;
        if (server != null) {
            for (UUID uid : Set.copyOf(FROZEN_PLAYERS)) {
                ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                if (sp != null) {
                    sp.connection.send(new ClientboundTickingStatePacket(20.0f, false));
                }
            }
        }
        FROZEN_PLAYERS.clear();
    }

    public static boolean shouldBlockMovementTo(ServerPlayer player, double targetX, double targetY, double targetZ) {
        if (!isQuarantineActive() || player == null) return false;
        YawpProtectionIndex idx = activeIndex;
        if (idx == null) return false;
        if (!player.level().dimension().identifier().toString().equals(idx.dimensionId())) {
            return false;
        }
        if (isPlayerFrozen(player.getUUID())) {
            return true;
        }
        int curCx = player.chunkPosition().x();
        int curCz = player.chunkPosition().z();
        int dstCx = ((int) Math.floor(targetX)) >> 4;
        int dstCz = ((int) Math.floor(targetZ)) >> 4;

        if (curCx == dstCx && curCz == dstCz) {
            return false;
        }

        // Case 1: Resident inside a YAWP Protected / Halo chunk trying to walk out into a Reset Target chunk
        if (idx.isChunkProtectedOrHalo(curCx, curCz)) {
            if (idx.isChunkResetTarget(dstCx, dstCz)) {
                warnQuarantineBlocked(player);
                return true;
            }
            return false;
        }

        // Case 2: External player trying to enter the Quarantine (+10 chunks) or Reset Target envelope
        boolean currentlyOutside = !idx.isChunkInQuarantineEnvelope(curCx, curCz);
        boolean destInsideQuarantine = idx.isChunkInQuarantineEnvelope(dstCx, dstCz) || idx.isChunkResetTarget(dstCx, dstCz);
        if (currentlyOutside && destInsideQuarantine) {
            warnQuarantineBlocked(player);
            return true;
        }
        if (idx.isChunkResetTarget(dstCx, dstCz)) {
            warnQuarantineBlocked(player);
            return true;
        }
        return false;
    }

    public static boolean shouldBlockTeleportTo(ServerLevel targetLevel, double targetX, double targetY, double targetZ, ServerPlayer player) {
        if (Boolean.TRUE.equals(ALLOW_INTERNAL_TELEPORT.get())) {
            return false;
        }
        if (!isQuarantineActive() || targetLevel == null) return false;
        YawpProtectionIndex idx = activeIndex;
        if (idx == null) return false;
        if (!targetLevel.dimension().identifier().toString().equals(idx.dimensionId())) {
            return false;
        }
        int dstCx = ((int) Math.floor(targetX)) >> 4;
        int dstCz = ((int) Math.floor(targetZ)) >> 4;
        // Allow teleporting into a YAWP protected region, block teleporting into Reset Target or Quarantine buffer
        if (idx.isChunkProtectedOrHalo(dstCx, dstCz)) {
            return false;
        }
        if (idx.isChunkInQuarantineEnvelope(dstCx, dstCz) || idx.isChunkResetTarget(dstCx, dstCz)) {
            if (player != null) {
                warnQuarantineBlocked(player);
            }
            return true;
        }
        return false;
    }

    public static void warnQuarantineBlocked(ServerPlayer player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        long last = LAST_QUARANTINE_WARN_MS.getOrDefault(player.getUUID(), 0L);
        if (now - last >= 1000L) {
            LAST_QUARANTINE_WARN_MS.put(player.getUUID(), now);
            String tpl = WildResetI18n.resolvePlayerTemplate(
                player,
                "msg.quarantine.blocked.actionbar",
                WildResetConfig.get().messages.quarantineBlockedActionBar,
                WildResetI18n.DEFAULT_KO_MESSAGES.quarantineBlockedActionBar
            );
            MiniText.sendActionBar(player, tpl, Map.of());
        }
    }

    public static void updateBossBar(
        MinecraftServer server,
        String i18nKey,
        String configTemplate,
        String defaultKoTemplate,
        Map<String, String> placeholders,
        float progress,
        BossEvent.BossBarColor color
    ) {
        if (server == null) return;
        String tplEn = WildResetI18n.resolveLangTemplate("en_us", i18nKey, configTemplate, defaultKoTemplate);
        String tplKo = WildResetI18n.resolveLangTemplate("ko_kr", i18nKey, configTemplate, defaultKoTemplate);
        Component titleEn = MiniText.format(tplEn, placeholders, server.registryAccess());
        Component titleKo = MiniText.format(tplKo, placeholders, server.registryAccess());

        if (bossBarEn == null) {
            bossBarEn = new ServerBossEvent(
                UUID.randomUUID(),
                titleEn,
                color,
                BossEvent.BossBarOverlay.PROGRESS
            );
            bossBarEn.setVisible(true);
        } else {
            bossBarEn.setName(titleEn);
            bossBarEn.setColor(color);
            bossBarEn.setVisible(true);
        }
        if (bossBarKo == null) {
            bossBarKo = new ServerBossEvent(
                UUID.randomUUID(),
                titleKo,
                color,
                BossEvent.BossBarOverlay.PROGRESS
            );
            bossBarKo.setVisible(true);
        } else {
            bossBarKo.setName(titleKo);
            bossBarKo.setColor(color);
            bossBarKo.setVisible(true);
        }

        float clamped = Math.clamp(progress, 0.0f, 1.0f);
        bossBarEn.setProgress(clamped);
        bossBarKo.setProgress(clamped);
        syncBossBarPlayers(server);
        hideBossBarTicksRemaining = 0;
    }

    private static void syncBossBarPlayers(MinecraftServer server) {
        if (server == null || bossBarEn == null || bossBarKo == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if ("ko_kr".equals(WildResetI18n.resolveLang(player))) {
                bossBarEn.removePlayer(player);
                bossBarKo.addPlayer(player);
            } else {
                bossBarKo.removePlayer(player);
                bossBarEn.addPlayer(player);
            }
        }
    }

    public static void scheduleHideBossBar(int ticks) {
        hideBossBarTicksRemaining = Math.max(1, ticks);
    }

    public static void onServerTick(MinecraftServer server) {
        if (hideBossBarTicksRemaining > 0) {
            hideBossBarTicksRemaining--;
            if (hideBossBarTicksRemaining == 0) {
                if (bossBarEn != null) {
                    bossBarEn.removeAllPlayers();
                    bossBarEn.setVisible(false);
                }
                if (bossBarKo != null) {
                    bossBarKo.removeAllPlayers();
                    bossBarKo.setVisible(false);
                }
            }
        } else if (bossBarEn != null && bossBarEn.isVisible() && server != null) {
            syncBossBarPlayers(server);
        }
    }
}
