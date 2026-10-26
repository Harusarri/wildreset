package kr.harusarri.wildreset;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import eu.pb4.polymer.virtualentity.impl.HolderHolder;
import net.fabricmc.fabric.impl.event.lifecycle.LoadedChunksCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core two-stage wilderness chunk reset engine + Season On-Load Lazy Chunk Reset:
 * - Countdown with MiniMessage BossBar + Chat milestones
 * - +10-Chunk Quarantine + In-Zone Packet-Freeze
 * - Stage 1: Multi-Threaded Worker Pool (Container evacuation, Double->Single chest conversion,
 *   3-day lazy timestamp, Y < 16 Lapis/Redstone -> Diamond randomizer, True Ground surface placement)
 * - Stage 2: Main-Thread Tick-Budgeted Commit (<= 12ms/tick: Tamed Wolf/Cat surface evacuation,
 *   Player Y-snap FIRST -> LevelChunk swap & Synchronous ScalableLux Starlight relight SECOND -> Unfreeze THIRD)
 * - On-Load Season Reset: Unloaded chunks within the reset radius are automatically reset on CHUNK_LOAD
 *   before being sent to players, eliminating the render-distance-only limitation with zero lag.
 */
public final class WildResetEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("wildreset");

    public enum Phase {
        IDLE,
        COUNTDOWN,
        STAGE1_WORKER_PREP,
        STAGE2_MAIN_COMMIT
    }

    public record EvacuatedContainerSpec(
        BlockPos surfacePos,
        BlockState blockState,
        CompoundTag blockEntityTag,
        String ownerUuid,
        String ownerName,
        long unlockAtMs,
        int season
    ) {}

    public record PreparedChunk(
        ChunkPos pos,
        SerializableChunkData parsedData,
        CompoundTag serializedTag,
        int[] safeSurfaceYMap,
        List<EvacuatedContainerSpec> evacuatedContainers,
        List<CompoundTag> templateBlockEntities,
        List<CompoundTag> templateEntities,
        int randomizedDiamonds,
        int season
    ) {
        public int getSafeSurfaceY(int lx, int lz) {
            int idx = ((lz & 15) << 4) | (lx & 15);
            return safeSurfaceYMap[idx];
        }
    }

    public static final class PersistedSeasonState {
        public int activeResetSeason = 0;
        public long unlockAtMs = 0L;
        public List<Long> resetChunks = new ArrayList<>();
        public List<Long> entitySweptChunks = new ArrayList<>();
    }

    private static volatile Phase currentPhase = Phase.IDLE;
    private static MinecraftServer activeServer;
    private static ServerLevel activeLevel;
    private static YawpProtectionIndex activeIndex;
    private static volatile YawpProtectionIndex lazyProtectionIndex;
    private static ThreadPoolExecutor stage1Pool;
    private static ThreadPoolExecutor lazyPool;

    public static final ThreadLocal<Boolean> IS_RELIGHTING_ON_MAIN_THREAD =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static int countdownTicksRemaining = 0;
    private static int activeRunRadiusOverride = 0;
    private static List<ChunkPos> targetChunks = new ArrayList<>();
    private static final ConcurrentLinkedQueue<PreparedChunk> preparedQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PreparedChunk> lazyPreparedQueue = new ConcurrentLinkedQueue<>();
    private static final Map<Long, int[]> committedSurfaceHeights = new ConcurrentHashMap<>();
    private static final AtomicInteger stage1CompletedCount = new AtomicInteger(0);
    private static int totalTargetChunks = 0;
    private static int stage2CommittedCount = 0;
    private static int totalEvacuatedChests = 0;
    private static int totalEvacuatedPets = 0;
    private static int totalExpiredChestsRemoved = 0;
    private static int totalRandomizedDiamonds = 0;
    private static long startTimeMs = 0L;

    // Season-based full-radius lazy reset tracking
    private static volatile int activeResetSeason = 0;
    private static volatile long activeSeasonUnlockAtMs = 0L;
    private static final Set<Long> resetChunksInCurrentSeason = ConcurrentHashMap.newKeySet();
    private static final Set<Long> entitySweptChunksInCurrentSeason = ConcurrentHashMap.newKeySet();
    private static final Set<Long> inFlightResetChunks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> lazyPendingSendHoldChunks = ConcurrentHashMap.newKeySet();
    private static final Set<Long> sessionRelitChunks = ConcurrentHashMap.newKeySet();
    private static final Map<Long, CompletableFuture<Void>> lazySendGates = new ConcurrentHashMap<>();
    private static final AtomicInteger totalLazyResetChunks = new AtomicInteger(0);
    private static int dirtySeasonSaveCounter = 0;
    private static int playerWindowScanTickCounter = 0;

    private WildResetEngine() {}

    public static Phase getCurrentPhase() {
        return currentPhase;
    }

    public static int getActiveResetSeason() {
        return activeResetSeason;
    }

    public static boolean isChunkResetInActiveSeason(int cx, int cz) {
        return activeResetSeason > 0 && resetChunksInCurrentSeason.contains(ChunkPos.pack(cx, cz));
    }

    public static int getLazyResetCount() {
        return totalLazyResetChunks.get();
    }

    public static void invalidateProtectionIndex() {
        lazyProtectionIndex = null;
        WildResetConfig cfg = WildResetConfig.get();
        if (!cfg.enabled || !cfg.lazyOnLoadResetEnabled) {
            if (lazyPool != null) {
                lazyPool.getQueue().clear();
            }
            lazyPreparedQueue.clear();
            inFlightResetChunks.clear();
            lazyPendingSendHoldChunks.clear();
            for (CompletableFuture<Void> gate : lazySendGates.values()) {
                gate.complete(null);
            }
            lazySendGates.clear();
        }
        CleanChunkCache.clearTransientGenerationCaches();
    }

    public static String getStatusSummary() {
        return "Phase=" + currentPhase
            + ", activeSeason=" + activeResetSeason
            + ", seasonResetChunks=" + resetChunksInCurrentSeason.size()
            + ", lazyOnLoadResets=" + totalLazyResetChunks.get()
            + ", targets=" + totalTargetChunks
            + ", stage1Prepared=" + stage1CompletedCount.get()
            + ", stage2Committed=" + stage2CommittedCount
            + ", evacuatedChests=" + totalEvacuatedChests
            + ", evacuatedPets=" + totalEvacuatedPets
            + ", expiredChestsSkipped=" + totalExpiredChestsRemoved
            + ", randomizedDiamonds=" + totalRandomizedDiamonds;
    }

    private static synchronized ThreadPoolExecutor ensureStage1Pool(WildResetConfig cfg) {
        int threads = Math.max(1, cfg.workerThreads);
        if (stage1Pool == null || stage1Pool.isShutdown()) {
            stage1Pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "WildReset-Stage1-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        return stage1Pool;
    }

    private static synchronized ThreadPoolExecutor ensureLazyPool(WildResetConfig cfg) {
        int threads = Math.max(1, cfg.workerThreads);
        if (lazyPool == null || lazyPool.isShutdown()) {
            lazyPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "WildReset-Lazy-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        return lazyPool;
    }

    private static YawpProtectionIndex getOrCreateLazyIndex(ServerLevel level) {
        YawpProtectionIndex idx = lazyProtectionIndex;
        if (idx == null || idx.regionSignature() != YawpProtectionIndex.computeRegionSignature(level)) {
            idx = YawpProtectionIndex.fromConfig(level);
            lazyProtectionIndex = idx;
        }
        return idx;
    }

    public static void loadSeasonState(ServerLevel overworld, Path cacheDir) {
        activeServer = overworld.getServer();
        activeLevel = overworld;
        lazyProtectionIndex = YawpProtectionIndex.fromConfig(overworld);
        Path file = cacheDir.resolve("season_state.json");
        if (!Files.exists(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            PersistedSeasonState state = WildResetConfig.GSON.fromJson(raw, PersistedSeasonState.class);
            if (state != null) {
                activeResetSeason = state.activeResetSeason;
                activeSeasonUnlockAtMs = state.unlockAtMs;
                resetChunksInCurrentSeason.clear();
                entitySweptChunksInCurrentSeason.clear();
                if (state.resetChunks != null) {
                    resetChunksInCurrentSeason.addAll(state.resetChunks);
                }
                if (state.entitySweptChunks != null) {
                    entitySweptChunksInCurrentSeason.addAll(state.entitySweptChunks);
                }
                LOGGER.info("[WildReset] Loaded season_state.json: activeSeason={}, resetChunks={}",
                    activeResetSeason, resetChunksInCurrentSeason.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[WildReset] Failed to load season_state.json", e);
        }
    }

    public static void saveSeasonState(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            PersistedSeasonState state = new PersistedSeasonState();
            state.activeResetSeason = activeResetSeason;
            state.unlockAtMs = activeSeasonUnlockAtMs;
            state.resetChunks = new ArrayList<>(resetChunksInCurrentSeason);
            state.entitySweptChunks = new ArrayList<>(entitySweptChunksInCurrentSeason);
            Path file = cacheDir.resolve("season_state.json");
            Files.writeString(file, WildResetConfig.GSON.toJson(state) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[WildReset] Failed to save season_state.json", e);
        }
    }

    /**
     * Starts a wilderness chunk reset cycle.
     * @param radiusChunksOverride if > 0, overrides the Spawn Cuboid radius in chunks for targeted testing;
     *                             if <= 0, uses the full configured cuboidRadiusBlocks and activates lazy on-load reset for all unloaded chunks.
     */
    public static synchronized boolean startReset(MinecraftServer server, int radiusChunksOverride) {
        if (currentPhase != Phase.IDLE) {
            return false;
        }
        WildResetConfig cfg = WildResetConfig.get();
        ServerLevel overworld = server.overworld();
        int radiusBlocks = radiusChunksOverride > 0 ? (radiusChunksOverride << 4) : cfg.cuboidRadiusBlocks;
        YawpProtectionIndex index = new YawpProtectionIndex(
            overworld,
            cfg.spawnCenterX,
            cfg.spawnCenterZ,
            radiusBlocks,
            cfg.protectedBufferHaloChunks,
            cfg.quarantineOuterBufferChunks
        );
        if (radiusChunksOverride <= 0) {
            lazyProtectionIndex = index;
        }

        int centerCx = cfg.spawnCenterX >> 4;
        int centerCz = cfg.spawnCenterZ >> 4;

        Set<ChunkPos> targetSet = new LinkedHashSet<>();
        if (radiusChunksOverride > 0) {
            for (int cx = centerCx - radiusChunksOverride; cx <= centerCx + radiusChunksOverride; cx++) {
                for (int cz = centerCz - radiusChunksOverride; cz <= centerCz + radiusChunksOverride; cz++) {
                    if (index.isChunkResetTarget(cx, cz)) {
                        targetSet.add(new ChunkPos(cx, cz));
                    }
                }
            }
        } else {
            targetSet.addAll(collectLoadedTargetChunks(overworld, index));
        }

        List<ChunkPos> targets = new ArrayList<>(targetSet);
        if (targets.isEmpty() && radiusChunksOverride > 0) {
            LOGGER.warn("[WildReset] No target chunks found for targeted reset.");
            return false;
        }

        activeServer = server;
        activeLevel = overworld;
        activeIndex = index;
        activeRunRadiusOverride = radiusChunksOverride;
        targetChunks = targets;
        totalTargetChunks = targets.size();
        stage1CompletedCount.set(0);
        stage2CommittedCount = 0;
        totalEvacuatedChests = 0;
        totalEvacuatedPets = 0;
        totalExpiredChestsRemoved = 0;
        totalRandomizedDiamonds = 0;
        preparedQueue.clear();
        committedSurfaceHeights.clear();
        startTimeMs = System.currentTimeMillis();

        countdownTicksRemaining = Math.max(1, cfg.countdownSeconds) * 20;
        currentPhase = Phase.COUNTDOWN;

        Map<String, String> startPlaceholders = Map.of(
            "seconds", String.valueOf(cfg.countdownSeconds),
            "radius", String.valueOf(radiusBlocks),
            "buffer", String.valueOf(cfg.quarantineOuterBufferChunks)
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String tpl = WildResetI18n.resolvePlayerTemplate(
                player,
                "msg.chat.countdown_start",
                cfg.messages.chatCountdownStart,
                WildResetI18n.DEFAULT_KO_MESSAGES.chatCountdownStart
            );
            MiniText.sendChat(player, tpl, startPlaceholders);
        }

        LOGGER.info("[WildReset] Reset started: {} loaded target chunks (full-radius lazy on-load enabled={}, protected+halo excluded={}), countdown={}s",
            totalTargetChunks, radiusChunksOverride <= 0, index.keepChunkCount(), cfg.countdownSeconds);
        return true;
    }

    /**
     * Resolves an in-memory LevelChunk even if C2ME or c2me-notickvd currently holds it at
     * ChunkStatus.FULL / FullChunkStatus.INACCESSIBLE wrapped inside an ImposterProtoChunk.
     */
    public static LevelChunk resolveInMemoryLevelChunk(ServerLevel level, int cx, int cz) {
        LevelChunk lc = level.getChunkSource().getChunkNow(cx, cz);
        if (lc != null) {
            return lc;
        }
        long packed = ChunkPos.pack(cx, cz);
        LevelChunk toSend = level.getChunkSource().chunkMap.getChunkToSend(packed);
        if (toSend != null) {
            return toSend;
        }
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(packed);
        if (holder != null) {
            ChunkAccess latest = holder.getLatestChunk();
            if (latest instanceof LevelChunk levelChunk) {
                return levelChunk;
            }
            if (latest instanceof ImposterProtoChunk imposter) {
                return imposter.getWrapped();
            }
            ChunkAccess full = holder.getChunkIfPresentUnchecked(ChunkStatus.FULL);
            if (full instanceof LevelChunk levelChunk) {
                return levelChunk;
            }
            if (full instanceof ImposterProtoChunk imposter) {
                return imposter.getWrapped();
            }
        }
        return null;
    }

    private static Set<ChunkPos> collectLoadedTargetChunks(ServerLevel overworld, YawpProtectionIndex index) {
        Set<ChunkPos> targetSet = new LinkedHashSet<>();
        List<ServerPlayer> realPlayers = new ArrayList<>();
        for (ServerPlayer player : overworld.players()) {
            if (player.getClass() == ServerPlayer.class) {
                realPlayers.add(player);
            }
        }

        if (!realPlayers.isEmpty()) {
            // Focus synchronous Stage 1 quarantine reset on the active view window around real players
            // so scattered fake benchmark bots or stale C2ME ItemHolder cache entries far away do not inflate Stage 1;
            // all remaining chunks in the 5000-block radius reset seamlessly on-load / via proximity sweep.
            for (ServerPlayer player : realPlayers) {
                ChunkPos ppos = player.chunkPosition();
                for (int dx = -16; dx <= 16; dx++) {
                    for (int dz = -16; dz <= 16; dz++) {
                        int cx = ppos.x() + dx;
                        int cz = ppos.z() + dz;
                        if (index.isChunkResetTarget(cx, cz) && resolveInMemoryLevelChunk(overworld, cx, cz) != null) {
                            targetSet.add(new ChunkPos(cx, cz));
                        }
                    }
                }
            }
            for ( long forcedPacked : overworld.getForceLoadedChunks()) {
                int cx = ChunkPos.getX(forcedPacked);
                int cz = ChunkPos.getZ(forcedPacked);
                if (index.isChunkResetTarget(cx, cz) && resolveInMemoryLevelChunk(overworld, cx, cz) != null) {
                    targetSet.add(new ChunkPos(cx, cz));
                }
            }
            return targetSet;
        }

        if (overworld instanceof LoadedChunksCache loadedCache) {
            for (LevelChunk lc : new ArrayList<>(loadedCache.fabric_getLoadedChunks())) {
                if (lc == null) continue;
                ChunkPos cpos = lc.getPos();
                if (index.isChunkResetTarget(cpos.x(), cpos.z())) {
                    targetSet.add(cpos);
                }
            }
        }
        try {
            overworld.getChunkSource().chunkMap.forEachReadyToSendChunk(lc -> {
                if (lc != null) {
                    ChunkPos cpos = lc.getPos();
                    if (index.isChunkResetTarget(cpos.x(), cpos.z())) {
                        targetSet.add(cpos);
                    }
                }
            });
        } catch (Throwable ignored) {}
        return targetSet;
    }

    public static synchronized boolean startTargetedChunkReset(MinecraftServer server, List<ChunkPos> customTargets) {
        if (currentPhase != Phase.IDLE || customTargets == null || customTargets.isEmpty()) {
            return false;
        }
        WildResetConfig cfg = WildResetConfig.get();
        ServerLevel overworld = server.overworld();
        YawpProtectionIndex index = YawpProtectionIndex.fromConfig(overworld);

        activeServer = server;
        activeLevel = overworld;
        activeIndex = index;
        activeRunRadiusOverride = 1;
        targetChunks = new ArrayList<>(customTargets);
        totalTargetChunks = targetChunks.size();
        stage1CompletedCount.set(0);
        stage2CommittedCount = 0;
        totalEvacuatedChests = 0;
        totalEvacuatedPets = 0;
        totalExpiredChestsRemoved = 0;
        totalRandomizedDiamonds = 0;
        preparedQueue.clear();
        committedSurfaceHeights.clear();
        startTimeMs = System.currentTimeMillis();

        countdownTicksRemaining = 20;
        currentPhase = Phase.COUNTDOWN;
        LOGGER.info("[WildReset] Targeted chunk reset started for {} chunks: {}", totalTargetChunks, targetChunks);
        return true;
    }

    public static void onStartServerTick(MinecraftServer server) {
        if (!lazyPreparedQueue.isEmpty() && server.overworld() != null) {
            tickLazyChunkCommits(server, server.overworld());
        }
    }

    public static void onEndServerTick(MinecraftServer server) {
        QuarantineAndFreezeManager.onServerTick(server);

        // Commit any lazy on-load reset chunks that finished worker preparation
        if (!lazyPreparedQueue.isEmpty() && server.overworld() != null) {
            tickLazyChunkCommits(server, server.overworld());
        }

        if (currentPhase == Phase.IDLE) {
            WildResetConfig cfg = WildResetConfig.get();
            if (cfg.enabled && cfg.lazyOnLoadResetEnabled && activeResetSeason > 0 && server.overworld() != null && ++playerWindowScanTickCounter >= 40) {
                playerWindowScanTickCounter = 0;
                scanPlayerWindowForUnresetChunks(server.overworld());
            }
            return;
        }
        if (server != activeServer) {
            return;
        }
        try {
            switch (currentPhase) {
                case COUNTDOWN -> tickCountdown(server);
                case STAGE1_WORKER_PREP -> tickStage1WorkerPrep(server);
                case STAGE2_MAIN_COMMIT -> tickStage2MainCommit(server);
                default -> {}
            }
        } catch (Exception e) {
            LOGGER.error("[WildReset] Error during reset tick in phase {}", currentPhase, e);
            abortReset();
        }
    }

    private static void scanPlayerWindowForUnresetChunks(ServerLevel overworld) {
        WildResetConfig cfg = WildResetConfig.get();
        if (!cfg.enabled || !cfg.lazyOnLoadResetEnabled || activeResetSeason <= 0) {
            return;
        }
        List<ServerPlayer> players = overworld.players();
        if (players.isEmpty()) {
            return;
        }
        YawpProtectionIndex idx = getOrCreateLazyIndex(overworld);
        Set<Long> scannedChunks = new HashSet<>();
        for (ServerPlayer player : players) {
            if (player.getClass() != ServerPlayer.class) {
                // Ignore Carpet fake benchmark bots (EntityPlayerMPFake)
                continue;
            }
            ChunkPos ppos = player.chunkPosition();
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    int cx = ppos.x() + dx;
                    int cz = ppos.z() + dz;
                    if (!idx.isChunkResetTarget(cx, cz)) {
                        continue;
                    }
                    long packed = ChunkPos.pack(cx, cz);
                    if (!scannedChunks.add(packed)) {
                        continue;
                    }
                    if (resetChunksInCurrentSeason.contains(packed) || inFlightResetChunks.contains(packed)) {
                        continue;
                    }
                    LevelChunk lc = resolveInMemoryLevelChunk(overworld, cx, cz);
                    if (lc != null) {
                        onChunkLoad(overworld, lc, false);
                    }
                }
            }
        }
    }

    private static void tickCountdown(MinecraftServer server) {
        WildResetConfig cfg = WildResetConfig.get();
        int totalTicks = Math.max(1, cfg.countdownSeconds * 20);
        if (countdownTicksRemaining % 20 == 0) {
            int sec = countdownTicksRemaining / 20;
            float progress = Math.clamp((float) countdownTicksRemaining / (float) totalTicks, 0.0f, 1.0f);
            QuarantineAndFreezeManager.updateBossBar(
                server,
                "msg.bossbar.countdown",
                cfg.messages.bossBarCountdown,
                WildResetI18n.DEFAULT_KO_MESSAGES.bossBarCountdown,
                Map.of(
                    "seconds", String.valueOf(sec),
                    "buffer", String.valueOf(cfg.quarantineOuterBufferChunks)
                ),
                progress,
                BossEvent.BossBarColor.RED
            );
        }
        countdownTicksRemaining--;
        if (countdownTicksRemaining <= 0) {
            beginQuarantineAndStage1(server);
        }
    }

    private static void beginQuarantineAndStage1(MinecraftServer server) {
        WildResetConfig cfg = WildResetConfig.get();
        currentPhase = Phase.STAGE1_WORKER_PREP;
        int season = EvacuatedChestRegistry.nextSeason();
        long nowMs = System.currentTimeMillis();
        long unlockAtMs = nowMs + cfg.chestProtectionHours * 3600_000L;

        // Activate new season for full-radius lazy on-load chunk reset as well
        activeResetSeason = season;
        activeSeasonUnlockAtMs = unlockAtMs;
        if (activeRunRadiusOverride <= 0) {
            // Re-collect currently loaded Overworld chunks at the end of the countdown;
            // any chunks that unloaded during the countdown will be reset lazily on-load.
            targetChunks = new ArrayList<>(collectLoadedTargetChunks(activeLevel, activeIndex));
            totalTargetChunks = targetChunks.size();

            if (lazyPool != null) {
                lazyPool.getQueue().clear();
            }
            resetChunksInCurrentSeason.clear();
            entitySweptChunksInCurrentSeason.clear();
            inFlightResetChunks.clear();
            lazyPendingSendHoldChunks.clear();
            sessionRelitChunks.clear();
            for (CompletableFuture<Void> gate : lazySendGates.values()) {
                gate.complete(null);
            }
            lazySendGates.clear();
            lazyPreparedQueue.clear();
        }

        // 1. Activate +10-Chunk Quarantine & In-Zone Packet Freeze
        QuarantineAndFreezeManager.activateQuarantine(server, activeLevel, activeIndex);
        QuarantineAndFreezeManager.updateBossBar(
            server,
            "msg.bossbar.staging",
            cfg.messages.bossBarStaging,
            WildResetI18n.DEFAULT_KO_MESSAGES.bossBarStaging,
            Map.of("progress", "0", "done", "0", "total", String.valueOf(totalTargetChunks)),
            0.0f,
            BossEvent.BossBarColor.BLUE
        );

        ThreadPoolExecutor pool = ensureStage1Pool(cfg);

        for (ChunkPos pos : targetChunks) {
            long packed = pos.pack();
            inFlightResetChunks.add(packed);
            List<CompoundTag> loadedBlockEntities = snapshotLoadedBlockEntitiesOnMainThread(activeLevel, pos);

            // Generate/load clean chunk tag and read unloaded disk block_entities INSIDE stage1Pool so main thread never stalls
            pool.submit(() -> {
                try {
                    List<CompoundTag> oldBlockEntities = loadedBlockEntities != null
                        ? loadedBlockEntities
                        : snapshotDiskBlockEntitiesInWorker(activeLevel, pos);
                    CompoundTag cleanTag = CleanChunkCache.getOrGenerateCleanChunkTag(activeLevel, pos);
                    PreparedChunk prepared = prepareChunkInWorker(
                        activeLevel, pos, cleanTag, oldBlockEntities, unlockAtMs, season, cfg
                    );
                    if (prepared != null) {
                        preparedQueue.add(prepared);
                    } else {
                        inFlightResetChunks.remove(packed);
                    }
                } catch (Throwable e) {
                    inFlightResetChunks.remove(packed);
                    LOGGER.error("[WildReset] Worker failed to prepare chunk {}", pos, e);
                } finally {
                    stage1CompletedCount.incrementAndGet();
                }
            });
        }
    }

    /**
     * Hooked to ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE:
     * In C2ME, chunks that were previously visited earlier in the server session remain in C2ME's
     * ItemHolder cache wrapped in an ImposterProtoChunk with isLoadedToWorld() == true, so C2ME
     * skips ServerChunkEvents.CHUNK_LOAD when re-upgrading them to FullChunkStatus.FULL, but ALWAYS
     * fires FULL_CHUNK_STATUS_CHANGE.
     */
    public static void onFullChunkStatusChange(
        ServerLevel level,
        LevelChunk chunk,
        FullChunkStatus oldStatus,
        FullChunkStatus newStatus
    ) {
        if (chunk == null) {
            return;
        }
        if (newStatus != FullChunkStatus.INACCESSIBLE) {
            onChunkLoad(level, chunk, false);
        } else {
            onChunkUnload(level, chunk);
        }
    }

    /**
     * Hooked to ServerChunkEvents.CHUNK_LOAD:
     * When any unloaded chunk within cuboidRadiusBlocks loads from disk (generated == false) and its
     * season is older than activeResetSeason, queues it for immediate worker reset before sending to players.
     */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        WildResetConfig cfg = WildResetConfig.get();
        if (!cfg.enabled || !cfg.lazyOnLoadResetEnabled || level != level.getServer().overworld()) {
            return;
        }
        if (activeResetSeason <= 0) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        boolean nearRealPlayer = false;
        for (ServerPlayer p : level.players()) {
            if (p.getClass() == ServerPlayer.class) {
                ChunkPos pp = p.chunkPosition();
                if (Math.abs(pp.x() - pos.x()) <= 16 && Math.abs(pp.z() - pos.z()) <= 16) {
                    nearRealPlayer = true;
                    break;
                }
            }
        }
        if (!nearRealPlayer) {
            return;
        }
        long packed = pos.pack();

        if (generated) {
            resetChunksInCurrentSeason.add(packed);
            entitySweptChunksInCurrentSeason.add(packed);
            return;
        }

        if (resetChunksInCurrentSeason.contains(packed) || inFlightResetChunks.contains(packed)) {
            return;
        }

        YawpProtectionIndex idx = getOrCreateLazyIndex(level);
        if (!idx.isChunkResetTarget(pos.x(), pos.z())) {
            return;
        }

        if (!inFlightResetChunks.add(packed)) {
            return;
        }
        lazyPendingSendHoldChunks.add(packed);

        // Register a send dependency on ChunkHolder so C2ME's ServerAccessibleChunkSending and Vanilla
        // PlayerChunkSender wait until our lazy reset commit finishes before sending the chunk to players
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(packed);
        if (holder != null) {
            CompletableFuture<Void> sendGate = new CompletableFuture<>();
            lazySendGates.put(packed, sendGate);
            holder.addSendDependency(sendGate);
        }

        // Materialize pending postLoad block entities on the main thread before snapshotting chests
        // and clear postLoad so C2ME's ServerAccessible does not re-run it after section swap
        if (level.getServer().isSameThread()) {
            try {
                chunk.runPostLoad();
            } catch (Throwable ignored) {}
        }

        List<CompoundTag> oldBlockEntities = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            oldBlockEntities.add(be.saveWithFullMetadata(level.registryAccess()));
        }

        int season = activeResetSeason;
        long unlockAtMs = activeSeasonUnlockAtMs > 0
            ? activeSeasonUnlockAtMs
            : (System.currentTimeMillis() + cfg.chestProtectionHours * 3600_000L);

        ensureLazyPool(cfg).submit(() -> {
            try {
                CompoundTag cleanTag = CleanChunkCache.getOrGenerateCleanChunkTag(level, pos);
                PreparedChunk prepared = prepareChunkInWorker(
                    level, pos, cleanTag, oldBlockEntities, unlockAtMs, season, cfg
                );
                if (prepared != null) {
                    lazyPreparedQueue.add(prepared);
                } else {
                    inFlightResetChunks.remove(packed);
                    lazyPendingSendHoldChunks.remove(packed);
                    CompletableFuture<Void> gate = lazySendGates.remove(packed);
                    if (gate != null) gate.complete(null);
                }
            } catch (Throwable e) {
                inFlightResetChunks.remove(packed);
                lazyPendingSendHoldChunks.remove(packed);
                CompletableFuture<Void> gate = lazySendGates.remove(packed);
                if (gate != null) gate.complete(null);
                LOGGER.warn("[WildReset] Failed lazy on-load preparation for chunk {}", pos, e);
            }
        });
    }

    public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (level != level.getServer().overworld() || activeResetSeason <= 0) {
            return;
        }
        long packed = chunk.getPos().pack();
        if (resetChunksInCurrentSeason.contains(packed) && !inFlightResetChunks.contains(packed)) {
            entitySweptChunksInCurrentSeason.add(packed);
        }
    }

    /**
     * Hooked to ServerEntityEvents.ALLOW_LOAD:
     * Prevents stale pre-reset wild entities (monsters, boats, items, displays) in lazy-reset chunks
     * from loading from world/dimensions/.../entities/*.mca, while allowing tamed Wolves/Cats to load
     * so onEntityLoaded can evacuate them to True Ground.
     */
    public static boolean onAllowEntityLoad(Entity entity, ServerLevel level, EntitySpawnReason spawnReason, boolean loadedFromDisk) {
        WildResetConfig cfg = WildResetConfig.get();
        if (!cfg.enabled || !cfg.lazyOnLoadResetEnabled || !loadedFromDisk || entity instanceof ServerPlayer || activeResetSeason <= 0) {
            return true;
        }
        if (level != level.getServer().overworld()) {
            return true;
        }
        String seasonTag = "wildreset_season_" + activeResetSeason;
        if (entity.entityTags().contains(seasonTag)) {
            return true;
        }
        ChunkPos pos = entity.chunkPosition();
        long packed = pos.pack();
        boolean isResetTarget = resetChunksInCurrentSeason.contains(packed)
            || inFlightResetChunks.contains(packed)
            || getOrCreateLazyIndex(level).isChunkResetTarget(pos.x(), pos.z());
        if (!isResetTarget) {
            return true;
        }
        if (isEvacuableTamedPet(entity, WildResetConfig.get())) {
            return true;
        }
        return false;
    }

    /**
     * Hooked to ServerEntityEvents.ENTITY_LOAD:
     * Evacuates tamed Wolves/Cats loaded from disk in a lazy-reset chunk onto True Ground,
     * and tags any newly spawned entities in the active season so they persist across chunk reloads.
     */
    public static void onEntityLoaded(Entity entity, ServerLevel level) {
        if (entity instanceof ServerPlayer || activeResetSeason <= 0 || level != level.getServer().overworld()) {
            return;
        }
        String seasonTag = "wildreset_season_" + activeResetSeason;
        if (entity.entityTags().contains(seasonTag)) {
            return;
        }
        WildResetConfig cfg = WildResetConfig.get();
        if (!isEvacuableTamedPet(entity, cfg)) {
            entity.addTag(seasonTag);
            return;
        }
        ChunkPos pos = entity.chunkPosition();
        long packed = pos.pack();
        if (!resetChunksInCurrentSeason.contains(packed) && !inFlightResetChunks.contains(packed)) {
            return;
        }
        if (entitySweptChunksInCurrentSeason.contains(packed)) {
            return;
        }

        entity.addTag(seasonTag);
        int lx = entity.getBlockX() & 15;
        int lz = entity.getBlockZ() & 15;
        int targetY = resolveLoadedChunkTrueGroundY(level, pos, lx, lz);

        TamableAnimal pet = (TamableAnimal) entity;
        entity.fallDistance = 0f;
        entity.setDeltaMovement(Vec3.ZERO);
        pet.setOrderedToSit(true);
        pet.setInSittingPose(true);
        entity.teleportTo((pos.x() << 4) + lx + 0.5, targetY, (pos.z() << 4) + lz + 0.5);
        totalEvacuatedPets++;
    }

    private static int resolveLoadedChunkTrueGroundY(ServerLevel level, ChunkPos pos, int lx, int lz) {
        int[] surfaceMap = committedSurfaceHeights.get(pos.pack());
        if (surfaceMap != null) {
            return surfaceMap[((lz & 15) << 4) | (lx & 15)];
        }
        LevelChunk lc = resolveInMemoryLevelChunk(level, pos.x(), pos.z());
        if (lc != null) {
            return lc.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (pos.x() << 4) + lx, (pos.z() << 4) + lz) + 1;
        }
        return 70;
    }

    private static void tickLazyChunkCommits(MinecraftServer server, ServerLevel overworld) {
        WildResetConfig cfg = WildResetConfig.get();
        long deadlineNs = System.nanoTime() + (long) (cfg.mainThreadTickBudgetMs * 1_000_000L);
        List<LevelChunk> committedChunks = new ArrayList<>();

        while (!lazyPreparedQueue.isEmpty() && System.nanoTime() < deadlineNs) {
            PreparedChunk prepared = lazyPreparedQueue.poll();
            if (prepared == null) break;

            long packed = prepared.pos().pack();
            LevelChunk live = commitPreparedChunkOnMainThread(overworld, prepared, cfg, false);
            if (live != null) {
                committedChunks.add(live);
                resetChunksInCurrentSeason.add(packed);
                committedSurfaceHeights.put(packed, prepared.safeSurfaceYMap());
                totalEvacuatedChests += prepared.evacuatedContainers().size();
                totalRandomizedDiamonds += prepared.randomizedDiamonds();
                totalLazyResetChunks.incrementAndGet();
                dirtySeasonSaveCounter++;
            } else {
                CompletableFuture<Void> gate = lazySendGates.remove(packed);
                if (gate != null) gate.complete(null);
            }
            inFlightResetChunks.remove(packed);
            lazyPendingSendHoldChunks.remove(packed);
        }

        if (!committedChunks.isEmpty()) {
            relightAndBroadcastChunksOnMainThread(overworld, committedChunks);
        }

        if (dirtySeasonSaveCounter >= 32 || (lazyPreparedQueue.isEmpty() && dirtySeasonSaveCounter > 0)) {
            dirtySeasonSaveCounter = 0;
            Path cacheDir = CleanChunkCache.getCacheRootDir(overworld);
            EvacuatedChestRegistry.save(cacheDir);
            saveSeasonState(cacheDir);
            if (lazyPreparedQueue.isEmpty()) {
                CleanChunkCache.clearTransientGenerationCaches();
            }
        }
    }

    private static List<CompoundTag> snapshotLoadedBlockEntitiesOnMainThread(ServerLevel level, ChunkPos pos) {
        LevelChunk loaded = resolveInMemoryLevelChunk(level, pos.x(), pos.z());
        if (loaded == null) {
            return null;
        }
        List<CompoundTag> result = new ArrayList<>();
        if (level.getServer().isSameThread()) {
            try {
                loaded.runPostLoad();
            } catch (Throwable ignored) {}
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : loaded.getBlockEntities().entrySet()) {
            CompoundTag tag = entry.getValue().saveWithFullMetadata(level.registryAccess());
            result.add(tag);
        }
        return result;
    }

    private static List<CompoundTag> snapshotDiskBlockEntitiesInWorker(ServerLevel level, ChunkPos pos) {
        List<CompoundTag> result = new ArrayList<>();
        try {
            Optional<CompoundTag> diskOpt = level.getChunkSource().chunkMap.read(pos).join();
            if (diskOpt.isPresent()) {
                ListTag beList = diskOpt.get().getListOrEmpty("block_entities");
                for (int i = 0; i < beList.size(); i++) {
                    beList.getCompound(i).ifPresent(result::add);
                }
            }
        } catch (Throwable e) {
            LOGGER.warn("[WildReset] Failed to read disk block_entities for {}", pos, e);
        }
        return result;
    }

    private static PreparedChunk prepareChunkInWorker(
        ServerLevel level,
        ChunkPos pos,
        CompoundTag cleanTag,
        List<CompoundTag> oldBlockEntities,
        long unlockAtMs,
        int season,
        WildResetConfig cfg
    ) {
        if (cleanTag == null) {
            return null;
        }
        SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), cleanTag.copy());
        if (parsed == null) {
            return null;
        }

        Map<Integer, LevelChunkSection> sectionMap = new HashMap<>();
        for (SerializableChunkData.SectionData sd : parsed.sectionData()) {
            if (sd.chunkSection() != null) {
                sectionMap.put(sd.y(), sd.chunkSection());
            }
        }

        // 0. Sanitize any non-vanilla (Polymer/modded) blocks from cached template
        for (LevelChunkSection section : sectionMap.values()) {
            if (section.hasOnlyAir()) continue;
            boolean hasModdedBlock = section.maybeHas(st ->
                !"minecraft".equals(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace())
            );
            if (hasModdedBlock) {
                PalettedContainer<BlockState> states = section.getStates();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = states.get(x, y, z);
                            if (!"minecraft".equals(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace())) {
                                states.set(x, y, z, Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                }
                section.recalcBlockCounts();
            }
        }

        // 1. Underground (Y < 16) Ore Permutation Randomizer
        int randomizedDiamonds = 0;
        if (cfg.oreRandomizerEnabled) {
            long seed = level.getSeed() ^ (pos.pack() * 0x9E3779B97F4A7C15L) ^ System.nanoTime();
            SplittableRandom rng = new SplittableRandom(seed);
            int maxSectionY = (cfg.oreRandomizerMaxY >> 4);

            for (Map.Entry<Integer, LevelChunkSection> entry : sectionMap.entrySet()) {
                int secY = entry.getKey();
                if (secY >= maxSectionY) continue;
                LevelChunkSection section = entry.getValue();
                if (section.hasOnlyAir()) continue;

                boolean hasTargetOres = section.maybeHas(state ->
                    state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                    || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                    || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                );
                if (!hasTargetOres) continue;

                PalettedContainer<BlockState> states = section.getStates();
                for (int y = 0; y < 16; y++) {
                    int worldY = (secY << 4) + y;
                    if (worldY >= cfg.oreRandomizerMaxY) continue;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = states.get(x, y, z);
                            if (st.is(Blocks.DIAMOND_ORE)) {
                                states.set(x, y, z, Blocks.STONE.defaultBlockState());
                            } else if (st.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                                states.set(x, y, z, Blocks.DEEPSLATE.defaultBlockState());
                            } else if (st.is(Blocks.LAPIS_ORE)) {
                                if (rng.nextDouble() < cfg.lapisToDiamondChance) {
                                    states.set(x, y, z, Blocks.DIAMOND_ORE.defaultBlockState());
                                    randomizedDiamonds++;
                                }
                            } else if (st.is(Blocks.DEEPSLATE_LAPIS_ORE)) {
                                if (rng.nextDouble() < cfg.lapisToDiamondChance) {
                                    states.set(x, y, z, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                                    randomizedDiamonds++;
                                }
                            } else if (st.is(Blocks.REDSTONE_ORE)) {
                                if (rng.nextDouble() < cfg.redstoneToDiamondChance) {
                                    states.set(x, y, z, Blocks.DIAMOND_ORE.defaultBlockState());
                                    randomizedDiamonds++;
                                }
                            } else if (st.is(Blocks.DEEPSLATE_REDSTONE_ORE)) {
                                if (rng.nextDouble() < cfg.redstoneToDiamondChance) {
                                    states.set(x, y, z, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                                    randomizedDiamonds++;
                                }
                            }
                        }
                    }
                }
                section.recalcBlockCounts();
            }
        }

        // 2. Compute True Ground Y (!Tree && !Leaves && Floor != Air && 2-block Air headroom)
        int minY = level.getMinY();
        int maxY = minY + level.getHeight();
        int[] safeSurfaceYMap = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                safeSurfaceYMap[(lz << 4) | lx] = findTrueGroundY(sectionMap, lx, lz, minY, maxY);
            }
        }

        // 2b. Preserve structure block entities (village chests/bells/smokers/blast furnaces/spawners)
        //     and structure/biome entities (villagers, iron golems, cats, animals) from the clean chunk template
        List<CompoundTag> templateBlockEntities = new ArrayList<>();
        for (CompoundTag beTag : parsed.blockEntities()) {
            templateBlockEntities.add(beTag.copy());
        }
        List<CompoundTag> templateEntities = new ArrayList<>();
        ListTag rawEntities = cleanTag.getListOrEmpty(CleanChunkCache.NBT_WILDRESET_ENTITIES);
        if (rawEntities.isEmpty()) {
            rawEntities = cleanTag.getListOrEmpty("entities");
        }
        for (int i = 0; i < rawEntities.size(); i++) {
            rawEntities.getCompound(i).ifPresent(t -> templateEntities.add(t.copy()));
        }

        // 3. Extract & Evacuate Non-Empty Player Containers onto True Ground
        List<EvacuatedContainerSpec> evacuatedContainers = new ArrayList<>();
        Set<Integer> occupiedColumns = new HashSet<>();
        Set<BlockPos> templateBePositions = new HashSet<>();
        for (CompoundTag structBe : templateBlockEntities) {
            int sx = structBe.getIntOr("x", pos.getMinBlockX());
            int sy = structBe.getIntOr("y", 64);
            int sz = structBe.getIntOr("z", pos.getMinBlockZ());
            templateBePositions.add(new BlockPos(sx, sy, sz));
            occupiedColumns.add(((sz & 15) << 4) | (sx & 15));
        }
        String dimStr = level.dimension().identifier().toString();

        for (CompoundTag beTag : oldBlockEntities) {
            String id = beTag.getStringOr("id", "");
            if (!EvacuatedChestRegistry.isContainerId(id)) continue;

            ListTag items = beTag.getListOrEmpty("Items");
            if (items.isEmpty()) continue;

            int oldX = beTag.getIntOr("x", pos.getMinBlockX());
            int oldY = beTag.getIntOr("y", 64);
            int oldZ = beTag.getIntOr("z", pos.getMinBlockZ());

            CompoundTag components = beTag.getCompoundOrEmpty("components");
            CompoundTag customData = components.getCompoundOrEmpty("minecraft:custom_data");
            byte isEvacuated = customData.getByteOr(EvacuatedChestRegistry.NBT_EVACUATED, (byte) 0);
            if (isEvacuated == 1) {
                int chestSeason = customData.getIntOr(EvacuatedChestRegistry.NBT_SEASON, 0);
                if (cfg.expireUnclaimedChestsNextSeason && chestSeason < season) {
                    totalExpiredChestsRemoved++;
                    continue;
                }
            } else if (templateBePositions.contains(new BlockPos(oldX, oldY, oldZ))
                && EvacuatedChestRegistry.getPlacedOwner(dimStr, oldX, oldY, oldZ) == null) {
                // Skip natural structure loot chests that are already restored via templateBlockEntities
                continue;
            }

            int startLx = oldX & 15;
            int startLz = oldZ & 15;
            int chosenCol = chooseUnoccupiedSurfaceColumn(startLx, startLz, occupiedColumns);
            int targetLx = chosenCol & 15;
            int targetLz = (chosenCol >> 4) & 15;
            occupiedColumns.add(chosenCol);

            int targetY = safeSurfaceYMap[(targetLz << 4) | targetLx];
            int targetX = (pos.x() << 4) + targetLx;
            int targetZ = (pos.z() << 4) + targetLz;
            BlockPos surfacePos = new BlockPos(targetX, targetY, targetZ);

            // Convert Double Chest -> Single Chest (ChestType.SINGLE)
            BlockState targetState = resolveSingleContainerState(id);
            setBlockStateInSections(sectionMap, targetLx, targetY, targetLz, targetState);

            BlockState floorState = getBlockStateInSections(sectionMap, targetLx, targetY - 1, targetLz);
            if (floorState.isAir() || !floorState.getFluidState().isEmpty()) {
                setBlockStateInSections(sectionMap, targetLx, targetY - 1, targetLz, Blocks.DIRT.defaultBlockState());
            }

            // Resolve owner from CustomData or placed registry
            String ownerUuid = customData.getStringOr(EvacuatedChestRegistry.NBT_OWNER_UUID, "");
            String ownerName = customData.getStringOr(EvacuatedChestRegistry.NBT_OWNER_NAME, "Player");
            EvacuatedChestRegistry.PathOwner placedOwner = EvacuatedChestRegistry.getPlacedOwner(dimStr, oldX, oldY, oldZ);
            if (placedOwner != null) {
                ownerUuid = placedOwner.ownerUuid();
                ownerName = placedOwner.ownerName();
            }

            CompoundTag newBeTag = beTag.copy();
            newBeTag.putString("id", resolveBlockEntityId(id));
            newBeTag.putInt("x", targetX);
            newBeTag.putInt("y", targetY);
            newBeTag.putInt("z", targetZ);

            CompoundTag newComponents = newBeTag.getCompoundOrEmpty("components").copy();
            CompoundTag newCustomData = newComponents.getCompoundOrEmpty("minecraft:custom_data").copy();
            newCustomData.putByte(EvacuatedChestRegistry.NBT_EVACUATED, (byte) 1);
            newCustomData.putLong(EvacuatedChestRegistry.NBT_UNLOCK_AT, unlockAtMs);
            newCustomData.putString(EvacuatedChestRegistry.NBT_OWNER_UUID, ownerUuid);
            newCustomData.putString(EvacuatedChestRegistry.NBT_OWNER_NAME, ownerName);
            newCustomData.putInt(EvacuatedChestRegistry.NBT_SEASON, season);
            newComponents.put("minecraft:custom_data", newCustomData);
            newBeTag.put("components", newComponents);

            evacuatedContainers.add(new EvacuatedContainerSpec(
                surfacePos, targetState, newBeTag, ownerUuid, ownerName, unlockAtMs, season
            ));
        }

        CompoundTag finalTag = parsed.write();
        finalTag.putBoolean("isLightOn", false);
        if (!evacuatedContainers.isEmpty()) {
            ListTag beList = finalTag.getListOrEmpty("block_entities").copy();
            for (EvacuatedContainerSpec spec : evacuatedContainers) {
                beList.add(spec.blockEntityTag());
            }
            finalTag.put("block_entities", beList);
        }
        return new PreparedChunk(
            pos,
            parsed,
            finalTag,
            safeSurfaceYMap,
            evacuatedContainers,
            templateBlockEntities,
            templateEntities,
            randomizedDiamonds,
            season
        );
    }

    private static String resolveBlockEntityId(String id) {
        return "minecraft:chest";
    }

    private static BlockState resolveSingleContainerState(String id) {
        return Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.SINGLE)
            .setValue(ChestBlock.FACING, Direction.NORTH);
    }

    private static int chooseUnoccupiedSurfaceColumn(int startLx, int startLz, Set<Integer> occupied) {
        for (int radius = 0; radius < 16; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int lx = (startLx + dx) & 15;
                    int lz = (startLz + dz) & 15;
                    int key = (lz << 4) | lx;
                    int north = (((lz - 1) & 15) << 4) | lx;
                    int south = (((lz + 1) & 15) << 4) | lx;
                    int west = (lz << 4) | ((lx - 1) & 15);
                    int east = (lz << 4) | ((lx + 1) & 15);
                    if (!occupied.contains(key) && !occupied.contains(north) && !occupied.contains(south)
                        && !occupied.contains(west) && !occupied.contains(east)) {
                        return key;
                    }
                }
            }
        }
        return (startLz << 4) | startLx;
    }

    private static int findTrueGroundY(Map<Integer, LevelChunkSection> sectionMap, int lx, int lz, int minY, int maxY) {
        for (int y = maxY - 3; y >= minY + 1; y--) {
            BlockState floor = getBlockStateInSections(sectionMap, lx, y - 1, lz);
            if (floor.isAir() || !floor.getFluidState().isEmpty()) continue;
            if (floor.is(BlockTags.LEAVES) || floor.is(BlockTags.LOGS) || floor.is(BlockTags.FENCES)) continue;
            if (floor.is(Blocks.CACTUS) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.FIRE)) continue;

            BlockState feet = getBlockStateInSections(sectionMap, lx, y, lz);
            BlockState head = getBlockStateInSections(sectionMap, lx, y + 1, lz);
            if (isPassableHeadroom(feet) && isPassableHeadroom(head)) {
                return y;
            }
        }
        return 64;
    }

    private static boolean isPassableHeadroom(BlockState state) {
        if (!state.getFluidState().isEmpty()) return false;
        return state.isAir() || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS);
    }

    private static BlockState getBlockStateInSections(Map<Integer, LevelChunkSection> sectionMap, int lx, int y, int lz) {
        LevelChunkSection sec = sectionMap.get(y >> 4);
        if (sec == null || sec.hasOnlyAir()) {
            return Blocks.AIR.defaultBlockState();
        }
        return sec.getBlockState(lx & 15, y & 15, lz & 15);
    }

    private static void setBlockStateInSections(Map<Integer, LevelChunkSection> sectionMap, int lx, int y, int lz, BlockState state) {
        LevelChunkSection sec = sectionMap.get(y >> 4);
        if (sec != null) {
            sec.setBlockState(lx & 15, y & 15, lz & 15, state, false);
        }
    }

    private static void tickStage1WorkerPrep(MinecraftServer server) {
        WildResetConfig cfg = WildResetConfig.get();
        int done = stage1CompletedCount.get();
        int pct = totalTargetChunks > 0 ? (done * 100 / totalTargetChunks) : 100;
        QuarantineAndFreezeManager.updateBossBar(
            server,
            "msg.bossbar.staging",
            cfg.messages.bossBarStaging,
            WildResetI18n.DEFAULT_KO_MESSAGES.bossBarStaging,
            Map.of(
                "progress", String.valueOf(pct),
                "done", String.valueOf(done),
                "total", String.valueOf(totalTargetChunks)
            ),
            Math.clamp((float) done / (float) Math.max(1, totalTargetChunks), 0.0f, 1.0f),
            BossEvent.BossBarColor.BLUE
        );
        if (done >= totalTargetChunks) {
            currentPhase = Phase.STAGE2_MAIN_COMMIT;
            LOGGER.info("[WildReset] Stage 1 worker prep complete ({} chunks). Starting Stage 2 main-thread commit.", done);
        }
    }

    private static void tickStage2MainCommit(MinecraftServer server) {
        WildResetConfig cfg = WildResetConfig.get();
        long deadlineNs = System.nanoTime() + (long) (cfg.mainThreadTickBudgetMs * 1_000_000L);
        List<LevelChunk> committedChunks = new ArrayList<>();

        while (!preparedQueue.isEmpty() && System.nanoTime() < deadlineNs) {
            PreparedChunk prepared = preparedQueue.poll();
            if (prepared == null) break;

            long packed = prepared.pos().pack();
            LevelChunk live = commitPreparedChunkOnMainThread(activeLevel, prepared, cfg, true);
            if (live != null) {
                committedChunks.add(live);
                resetChunksInCurrentSeason.add(packed);
            }
            stage2CommittedCount++;
            totalEvacuatedChests += prepared.evacuatedContainers().size();
            totalRandomizedDiamonds += prepared.randomizedDiamonds();
            committedSurfaceHeights.put(packed, prepared.safeSurfaceYMap());
            inFlightResetChunks.remove(packed);
        }

        if (!committedChunks.isEmpty()) {
            relightAndBroadcastChunksOnMainThread(activeLevel, committedChunks);
        }

        int pct = totalTargetChunks > 0 ? (stage2CommittedCount * 100 / totalTargetChunks) : 100;
        QuarantineAndFreezeManager.updateBossBar(
            server,
            "msg.bossbar.committing",
            cfg.messages.bossBarCommitting,
            WildResetI18n.DEFAULT_KO_MESSAGES.bossBarCommitting,
            Map.of(
                "progress", String.valueOf(pct),
                "done", String.valueOf(stage2CommittedCount),
                "total", String.valueOf(totalTargetChunks)
            ),
            Math.clamp((float) stage2CommittedCount / (float) Math.max(1, totalTargetChunks), 0.0f, 1.0f),
            BossEvent.BossBarColor.YELLOW
        );

        if (preparedQueue.isEmpty()) {
            finishReset(server);
        }
    }

    private static LevelChunk commitPreparedChunkOnMainThread(
        ServerLevel level,
        PreparedChunk prepared,
        WildResetConfig cfg,
        boolean markEntitySweptIfLoaded
    ) {
        ChunkPos pos = prepared.pos();
        LevelChunk liveChunk = resolveInMemoryLevelChunk(level, pos.x(), pos.z());
        String dimStr = level.dimension().identifier().toString();
        int maxY = level.getMinY() + level.getHeight();
        String seasonTag = "wildreset_season_" + prepared.season();

        if (liveChunk != null) {
            try {
                liveChunk.runPostLoad();
            } catch (Throwable ignored) {}

            // 1. Evacuate Tamed Wolves & Cats to True Ground; discard wild mobs, boats, dropped items, and 0x0 Display/Interaction entities
            AABB chunkBox = new AABB(
                pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, maxY, pos.getMaxBlockZ() + 1
            ).inflate(0.5);
            List<Entity> entities = level.getEntities(
                (Entity) null,
                chunkBox,
                e -> !(e instanceof ServerPlayer) && e.chunkPosition().equals(pos)
            );
            for (Entity entity : entities) {
                if (isEvacuableTamedPet(entity, cfg)) {
                    TamableAnimal pet = (TamableAnimal) entity;
                    int lx = entity.getBlockX() & 15;
                    int lz = entity.getBlockZ() & 15;
                    int[] safeCol = findNonChestColumnAndY(prepared, lx, lz);
                    entity.addTag(seasonTag);
                    entity.fallDistance = 0f;
                    entity.setDeltaMovement(Vec3.ZERO);
                    pet.setOrderedToSit(true);
                    pet.setInSittingPose(true);
                    entity.teleportTo(
                        (pos.x() << 4) + safeCol[0] + 0.5,
                        safeCol[2],
                        (pos.z() << 4) + safeCol[1] + 0.5
                    );
                    totalEvacuatedPets++;
                } else {
                    entity.discard();
                }
            }
            if (markEntitySweptIfLoaded && level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null) {
                entitySweptChunksInCurrentSeason.add(pos.pack());
            }

            // 1b. Destroy & detach all Polymer Virtual Entity attachments (BlockBoundAttachment / ChunkAttachment displays)
            destroyPolymerVirtualEntitiesInChunk(level, liveChunk, pos);

            // 2. Snap In-Zone Online Players Vertically FIRST (ClientboundPlayerPositionPacket)
            Map<ServerPlayer, Integer> snappedPlayers = new LinkedHashMap<>();
            for (ServerPlayer player : level.players()) {
                if (player.chunkPosition().equals(pos)) {
                    int lx = player.getBlockX() & 15;
                    int lz = player.getBlockZ() & 15;
                    int[] safeCol = findNonChestColumnAndY(prepared, lx, lz);
                    double targetX = (pos.x() << 4) + safeCol[0] + 0.5;
                    double targetY = safeCol[2];
                    double targetZ = (pos.z() << 4) + safeCol[1] + 0.5;

                    player.fallDistance = 0f;
                    player.setDeltaMovement(Vec3.ZERO);
                    QuarantineAndFreezeManager.ALLOW_INTERNAL_TELEPORT.set(Boolean.TRUE);
                    try {
                        player.teleportTo(
                            level,
                            targetX,
                            targetY,
                            targetZ,
                            Set.of(),
                            player.getYRot(),
                            player.getXRot(),
                            false
                        );
                    } finally {
                        QuarantineAndFreezeManager.ALLOW_INTERNAL_TELEPORT.set(Boolean.FALSE);
                    }
                    snappedPlayers.put(player, safeCol[2]);
                }
            }

            // 3. Swap LevelChunk Sections, Heightmaps, and BlockEntities SECOND
            liveChunk.clearAllBlockEntities();

            Map<Integer, LevelChunkSection> newSections = new HashMap<>();
            for (SerializableChunkData.SectionData sd : prepared.parsedData().sectionData()) {
                if (sd.chunkSection() != null) {
                    newSections.put(sd.y(), sd.chunkSection());
                }
            }

            LevelChunkSection[] liveSections = liveChunk.getSections();
            for (int i = 0; i < liveSections.length; i++) {
                int secY = liveChunk.getSectionYFromSectionIndex(i);
                LevelChunkSection replacement = newSections.get(secY);
                if (replacement != null) {
                    // Use the 2-arg public constructor + recalcBlockCounts() so Lithium's
                    // SectionData (RandomTickableBlocksByY byte[] and countsByFlag short[]) is initialized
                    LevelChunkSection freshSection = new LevelChunkSection(
                        replacement.getStates().copy(),
                        replacement.getBiomes().copy()
                    );
                    freshSection.recalcBlockCounts();
                    liveSections[i] = freshSection;
                    try {
                        level.getPoiManager().checkConsistencyWithBlocks(SectionPos.of(pos, secY), freshSection);
                    } catch (Throwable ignored) {}
                }
            }

            Heightmap.primeHeightmaps(liveChunk, EnumSet.allOf(Heightmap.Types.class));
            scheduleCaveAndSpringFluidTicks(level, liveChunk, pos);

            // 3b. Restore structure BlockEntities (village loot chests, bells, smokers, blast furnaces, spawners, etc.)
            for (CompoundTag structBeTag : prepared.templateBlockEntities()) {
                try {
                    BlockPos bePos = BlockEntity.getPosFromTag(pos, structBeTag);
                    BlockState beState = liveChunk.getBlockState(bePos);
                    if (beState.hasBlockEntity()) {
                        BlockEntity be = BlockEntity.loadStatic(bePos, beState, structBeTag, level.registryAccess());
                        if (be != null) {
                            liveChunk.addAndRegisterBlockEntity(be);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            for (EvacuatedContainerSpec spec : prepared.evacuatedContainers()) {
                BlockEntity be = BlockEntity.loadStatic(
                    spec.surfacePos(),
                    spec.blockState(),
                    spec.blockEntityTag(),
                    level.registryAccess()
                );
                if (be != null) {
                    liveChunk.addAndRegisterBlockEntity(be);
                }
                EvacuatedChestRegistry.registerEvacuatedChest(new EvacuatedChestRegistry.Entry(
                    dimStr,
                    spec.surfacePos().getX(),
                    spec.surfacePos().getY(),
                    spec.surfacePos().getZ(),
                    spec.ownerUuid(),
                    spec.ownerName(),
                    spec.unlockAtMs(),
                    spec.season()
                ));
            }

            // 3c. Spawn structure & natural chunk-generation entities (Villagers, Iron Golems, Cats, Sheep, Cows, etc.)
            if (!prepared.templateEntities().isEmpty()) {
                try (ProblemReporter.ScopedCollector collector = new ProblemReporter.ScopedCollector(liveChunk.problemPath(), LOGGER)) {
                    ValueInput.ValueInputList inputList = TagValueInput.create(
                        collector,
                        level.registryAccess(),
                        prepared.templateEntities()
                    );
                    EntityType.loadEntitiesRecursive(inputList, level, EntitySpawnReason.LOAD).forEach(entity -> {
                        assignFreshUuidAndSeasonRecursive(entity, level, seasonTag);
                        level.addFreshEntityWithPassengers(entity);
                    });
                } catch (Throwable t) {
                    LOGGER.warn("[WildReset] Failed to spawn clean chunk entities in {}", pos, t);
                }
            }

            // 4. Unfreeze In-Zone Players THIRD (ClientboundTickingStatePacket(20.0f, false))
            for (Map.Entry<ServerPlayer, Integer> entry : snappedPlayers.entrySet()) {
                QuarantineAndFreezeManager.unfreezePlayerAfterChunkSwap(entry.getKey(), entry.getValue(), cfg);
            }
            return liveChunk;
        } else {
            for (EvacuatedContainerSpec spec : prepared.evacuatedContainers()) {
                EvacuatedChestRegistry.registerEvacuatedChest(new EvacuatedChestRegistry.Entry(
                    dimStr,
                    spec.surfacePos().getX(),
                    spec.surfacePos().getY(),
                    spec.surfacePos().getZ(),
                    spec.ownerUuid(),
                    spec.ownerName(),
                    spec.unlockAtMs(),
                    spec.season()
                ));
            }
            level.getChunkSource().chunkMap.write(pos, prepared.serializedTag());
            return null;
        }
    }

    private static void assignFreshUuidAndSeasonRecursive(Entity entity, ServerLevel level, String seasonTag) {
        entity.setUUID(Mth.createInsecureUUID(level.getRandom()));
        entity.addTag(seasonTag);
        for (Entity passenger : entity.getPassengers()) {
            assignFreshUuidAndSeasonRecursive(passenger, level, seasonTag);
        }
    }

    private static final Direction[] FLUID_FLOW_DIRS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static void scheduleFluidTickSafe(
        ServerLevel level,
        LevelChunk targetChunk,
        BlockPos pos,
        net.minecraft.world.level.material.Fluid fluid,
        int delay
    ) {
        if (level.getChunkSource().isPositionTicking(targetChunk.getPos().pack())) {
            level.scheduleTick(pos, fluid, delay);
        } else {
            targetChunk.getFluidTicks().schedule(
                new net.minecraft.world.ticks.ScheduledTick<>(
                    fluid,
                    pos,
                    level.getGameTime() + delay,
                    net.minecraft.world.ticks.TickPriority.NORMAL,
                    0L
                )
            );
        }
    }

    private static void scheduleCaveAndSpringFluidTicks(ServerLevel level, LevelChunk liveChunk, ChunkPos pos) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int minY = level.getMinY();
        LevelChunkSection[] sections = liveChunk.getSections();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            LevelChunkSection sec = sections[sIdx];
            if (sec == null || sec.hasOnlyAir()) continue;
            int secMinY = SectionPos.sectionToBlockCoord(liveChunk.getSectionYFromSectionIndex(sIdx));

            for (int ly = 0; ly < 16; ly++) {
                int y = secMinY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = minZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = sec.getBlockState(lx, ly, lz);
                        FluidState fs = state.getFluidState();
                        int wx = minX + lx;
                        if (!fs.isEmpty()) {
                            boolean canSpread = false;
                            for (Direction dir : FLUID_FLOW_DIRS) {
                                int ny = y + dir.getStepY();
                                if (ny < minY) continue;
                                int nx = wx + dir.getStepX();
                                int nz = wz + dir.getStepZ();
                                BlockState nb;
                                if ((nx >> 4) == pos.x() && (nz >> 4) == pos.z()) {
                                    nb = liveChunk.getBlockState(npos.set(nx, ny, nz));
                                } else {
                                    LevelChunk neighborChunk = resolveInMemoryLevelChunk(level, nx >> 4, nz >> 4);
                                    if (neighborChunk == null) continue;
                                    nb = neighborChunk.getBlockState(npos.set(nx, ny, nz));
                                }
                                if (nb.isAir() || (nb.getFluidState().isEmpty() && nb.canBeReplaced())) {
                                    canSpread = true;
                                    break;
                                }
                            }
                            if (canSpread) {
                                BlockPos immutablePos = new BlockPos(wx, y, wz);
                                scheduleFluidTickSafe(level, liveChunk, immutablePos, fs.getType(), 2);
                            }
                        } else if ((lx == 0 || lx == 15 || lz == 0 || lz == 15)
                            && (state.isAir() || state.canBeReplaced())) {
                            // If this border block in liveChunk is now open air, wake up any adjacent
                            // fluid block on the neighbor chunk's border that was committed earlier.
                            for (Direction dir : Direction.Plane.HORIZONTAL) {
                                int nx = wx + dir.getStepX();
                                int nz = wz + dir.getStepZ();
                                if ((nx >> 4) == pos.x() && (nz >> 4) == pos.z()) continue;
                                LevelChunk neighborChunk = resolveInMemoryLevelChunk(level, nx >> 4, nz >> 4);
                                if (neighborChunk == null) continue;
                                mpos.set(nx, y, nz);
                                FluidState nfs = neighborChunk.getBlockState(mpos).getFluidState();
                                if (!nfs.isEmpty()) {
                                    scheduleFluidTickSafe(level, neighborChunk, mpos.immutable(), nfs.getType(), 2);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronously relights all committed chunks and their 3x3 loaded neighbors on the main server thread
     * using ScalableLux's StarLightInterface#relightChunks, eliminating both leftover block light from
     * removed light-emitting blocks and C2ME async chunk modification exceptions.
     */
    private static void relightAndBroadcastChunksOnMainThread(ServerLevel level, List<LevelChunk> committedChunks) {
        for (LevelChunk liveChunk : committedChunks) {
            try {
                sessionRelitChunks.add(liveChunk.getPos().pack());
                if (liveChunk instanceof ExtendedChunk extChunk) {
                    extChunk.scalablelux$setBlockNibbles(StarLightEngine.getFilledEmptyLight(level));
                    extChunk.scalablelux$setSkyNibbles(StarLightEngine.getFilledEmptyLight(level));
                    extChunk.scalablelux$setBlockEmptinessMap(null);
                    extChunk.scalablelux$setSkyEmptinessMap(null);
                }
                liveChunk.initializeLightSources();
                liveChunk.setLightCorrect(true);
            } catch (Exception ignored) {}
        }

        IS_RELIGHTING_ON_MAIN_THREAD.set(Boolean.TRUE);
        try {
            if (level.getChunkSource().getLightEngine() instanceof StarLightLightingProvider starLightProvider) {
                StarLightInterface starLight = starLightProvider.scalablelux$getLightEngine();
                if (starLight != null && starLight.getLightAccess() != null) {
                    var lightAccess = starLight.getLightAccess();
                    for (LevelChunk liveChunk : committedChunks) {
                        try {
                            starLight.lightChunk(liveChunk, StarLightEngine.getEmptySectionsForChunk(liveChunk));
                        } catch (Throwable ignored) {}
                    }
                    Set<ChunkPos> relightSet = new LinkedHashSet<>();
                    for (LevelChunk liveChunk : committedChunks) {
                        ChunkPos pos = liveChunk.getPos();
                        relightSet.add(pos);
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = pos.x() + dx;
                                int nz = pos.z() + dz;
                                LevelChunk nc = resolveInMemoryLevelChunk(level, nx, nz);
                                if (nc != null && nc.isLightCorrect() && lightAccess.getChunkForLighting(nx, nz) != null) {
                                    relightSet.add(new ChunkPos(nx, nz));
                                }
                            }
                        }
                    }

                    if (!relightSet.isEmpty()) {
                        List<ChunkPos> relitPositions = new ArrayList<>();
                        starLight.relightChunks(relightSet, relitPositions::add, null);

                        // 1. Mark reset chunks pending to send via vanilla PlayerChunkSender so Polymer's
                        //    PlayerContext is always present and clients receive fresh chunk + light packets
                        for (LevelChunk liveChunk : committedChunks) {
                            ChunkPos pos = liveChunk.getPos();
                            for (ServerPlayer tracker : level.players()) {
                                if (tracker.connection != null && tracker.getChunkTrackingView().contains(pos.x(), pos.z())) {
                                    tracker.connection.chunkSender.markChunkPendingToSend(liveChunk);
                                }
                            }
                        }

                        // 2. Send light update packets for all relit chunks + 3x3 neighbors so spilled border light is cleared immediately
                        for (ChunkPos relitPos : relitPositions) {
                            ClientboundLightUpdatePacket lightPacket = null;
                            for (ServerPlayer tracker : level.players()) {
                                if (tracker.connection != null && tracker.getChunkTrackingView().contains(relitPos.x(), relitPos.z())) {
                                    if (lightPacket == null) {
                                        lightPacket = new ClientboundLightUpdatePacket(
                                            relitPos,
                                            level.getChunkSource().getLightEngine(),
                                            null,
                                            null
                                        );
                                    }
                                    tracker.connection.send(lightPacket);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[WildReset] Error during ScalableLux synchronous chunk relight", t);
        } finally {
            IS_RELIGHTING_ON_MAIN_THREAD.set(Boolean.FALSE);
        }

        for (LevelChunk liveChunk : committedChunks) {
            liveChunk.markUnsaved();
            ChunkPos pos = liveChunk.getPos();
            CompletableFuture<Void> gate = lazySendGates.remove(pos.pack());
            if (gate != null) {
                gate.complete(null);
            }
        }
    }

    private static void destroyPolymerVirtualEntitiesInChunk(ServerLevel level, LevelChunk liveChunk, ChunkPos pos) {
        try {
            if (liveChunk instanceof HolderAttachmentHolder attachmentHolder) {
                List<HolderAttachment> attachments = new ArrayList<>(attachmentHolder.polymerVE$getHolders());
                for (HolderAttachment attachment : attachments) {
                    if (attachment == null) continue;
                    try {
                        if (attachment instanceof BlockBoundAttachment blockBound) {
                            attachmentHolder.polymerVE$removePosHolder(blockBound.getBlockPos());
                        }
                        ElementHolder holder = attachment.holder();
                        if (holder != null) {
                            holder.destroy();
                        }
                        attachment.destroy();
                        attachmentHolder.polymerVE$removeHolder(attachment);
                    } catch (Throwable ignored) {}
                }
            }

            for (ServerPlayer player : level.players()) {
                if (player.connection instanceof HolderHolder playerHolder) {
                    List<ElementHolder> elementHolders = new ArrayList<>(playerHolder.polymer$getHolders());
                    for (ElementHolder holder : elementHolders) {
                        if (holder != null && pos.equals(holder.getChunkPos())) {
                            HolderAttachment att = holder.getAttachment();
                            if (att == null || att instanceof ChunkAttachment) {
                                try {
                                    holder.destroy();
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[WildReset] Error cleaning Polymer virtual entities in {}", pos, t);
        }
    }

    public static boolean isEvacuableTamedPet(Entity entity, WildResetConfig cfg) {
        if (!cfg.evacuateTamedDogsAndCats) return false;
        if (entity instanceof Wolf wolf) {
            return wolf.isTame();
        }
        if (entity instanceof Cat cat) {
            return cat.isTame();
        }
        return false;
    }

    private static int[] findNonChestColumnAndY(PreparedChunk prepared, int startLx, int startLz) {
        Set<Long> chestColumns = new HashSet<>();
        for (EvacuatedContainerSpec spec : prepared.evacuatedContainers()) {
            chestColumns.add((((long) (spec.surfacePos().getZ() & 15)) << 4) | (spec.surfacePos().getX() & 15));
        }
        for (int r = 0; r < 16; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int lx = (startLx + dx) & 15;
                    int lz = (startLz + dz) & 15;
                    long key = (((long) lz) << 4) | lx;
                    if (!chestColumns.contains(key)) {
                        return new int[]{lx, lz, prepared.getSafeSurfaceY(lx, lz)};
                    }
                }
            }
        }
        return new int[]{startLx, startLz, prepared.getSafeSurfaceY(startLx, startLz)};
    }

    private static int syncOfflinePlayerDataSurfaceY(MinecraftServer server) {
        int updated = 0;
        try {
            Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            File dir = playerDataDir.toFile();
            if (!dir.exists() || !dir.isDirectory()) return 0;

            Set<UUID> onlineUuids = new HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                onlineUuids.add(p.getUUID());
            }

            File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
            if (files == null) return 0;

            for (File file : files) {
                String name = file.getName();
                String uuidStr = name.substring(0, name.length() - 4);
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    if (onlineUuids.contains(uuid)) continue;
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                try {
                    CompoundTag tag = NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
                    String dim = tag.getStringOr("Dimension", "minecraft:overworld");
                    if (!"minecraft:overworld".equals(dim)) continue;

                    ListTag posList = tag.getListOrEmpty("Pos");
                    if (posList.size() < 3) continue;
                    double px = posList.getDoubleOr(0, 0.0);
                    double pz = posList.getDoubleOr(2, 0.0);
                    int bx = (int) Math.floor(px);
                    int bz = (int) Math.floor(pz);
                    long chunkKey = ChunkPos.pack(bx >> 4, bz >> 4);

                    int[] surfaceMap = committedSurfaceHeights.get(chunkKey);
                    if (surfaceMap != null) {
                        int safeY = surfaceMap[((bz & 15) << 4) | (bx & 15)];
                        ListTag newPos = new ListTag();
                        newPos.add(DoubleTag.valueOf(bx + 0.5));
                        newPos.add(DoubleTag.valueOf(safeY));
                        newPos.add(DoubleTag.valueOf(bz + 0.5));
                        tag.put("Pos", newPos);
                        tag.putFloat("FallDistance", 0.0f);
                        NbtIo.writeCompressed(tag, file.toPath());
                        updated++;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[WildReset] Failed to update offline playerdata {}", file.getName(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[WildReset] Error scanning offline playerdata directory", e);
        }
        return updated;
    }

    private static void finishReset(MinecraftServer server) {
        WildResetConfig cfg = WildResetConfig.get();
        Path cacheDir = CleanChunkCache.getCacheRootDir(activeLevel);
        EvacuatedChestRegistry.save(cacheDir);
        saveSeasonState(cacheDir);
        CleanChunkCache.clearTransientGenerationCaches();
        QuarantineAndFreezeManager.deactivateQuarantine(server);

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        Map<String, String> placeholders = Map.of(
            "total", String.valueOf(stage2CommittedCount),
            "chests", String.valueOf(totalEvacuatedChests),
            "pets", String.valueOf(totalEvacuatedPets),
            "ores", String.valueOf(totalRandomizedDiamonds),
            "hours", String.valueOf(cfg.chestProtectionHours),
            "elapsed_ms", String.valueOf(elapsedMs)
        );
        QuarantineAndFreezeManager.updateBossBar(
            server,
            "msg.bossbar.complete",
            cfg.messages.bossBarComplete,
            WildResetI18n.DEFAULT_KO_MESSAGES.bossBarComplete,
            placeholders,
            1.0f,
            BossEvent.BossBarColor.GREEN
        );
        QuarantineAndFreezeManager.scheduleHideBossBar(100);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String tpl = WildResetI18n.resolvePlayerTemplate(
                player,
                "msg.chat.reset_complete",
                cfg.messages.chatResetComplete,
                WildResetI18n.DEFAULT_KO_MESSAGES.chatResetComplete
            );
            MiniText.sendChat(player, tpl, placeholders);
        }

        LOGGER.info(
            "[WildReset] Reset finished in {}ms: chunks={}, chests={}, pets={}, expiredChestsSkipped={}, randomizedDiamonds={}, activeSeason={}",
            elapsedMs, stage2CommittedCount, totalEvacuatedChests, totalEvacuatedPets,
            totalExpiredChestsRemoved, totalRandomizedDiamonds, activeResetSeason
        );
        currentPhase = Phase.IDLE;

        // Update offline playerdata/*.dat files asynchronously in stage1Pool so main thread is never blocked
        if (stage1Pool != null && !stage1Pool.isShutdown()) {
            stage1Pool.submit(() -> {
                int offlineUpdated = syncOfflinePlayerDataSurfaceY(server);
                LOGGER.info("[WildReset] Async offline playerdata Y-sync completed: {} offline players updated.", offlineUpdated);
            });
        }
    }

    public static void abortReset() {
        if (activeServer != null) {
            QuarantineAndFreezeManager.deactivateQuarantine(activeServer);
            QuarantineAndFreezeManager.scheduleHideBossBar(1);
        }
        for (CompletableFuture<Void> gate : lazySendGates.values()) {
            gate.complete(null);
        }
        lazySendGates.clear();
        preparedQueue.clear();
        currentPhase = Phase.IDLE;
    }
}
