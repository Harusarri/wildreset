package kr.harusarri.wildreset;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import kr.harusarri.wildreset.api.WildResetApi;
import kr.harusarri.wildreset.api.WildResetRegionProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;

/**
 * Spatial index of protected chunks and halo buffers combining:
 * 1. Standalone `/wildreset chunk` protected chunks & ranges (`config/wildreset_regions.json`)
 * 2. External `WildResetRegionProvider` regions registered via `WildResetApi`
 * 3. Optional YAWP (Yet Another World Protector) active regions when the `yawp` mod is present
 */
public final class YawpProtectionIndex {
    private static final boolean YAWP_LOADED = FabricLoader.getInstance().isModLoaded("yawp");

    private final String dimensionId;
    private final int centerX;
    private final int centerZ;
    private final int radiusBlocks;
    private final int haloChunks;
    private final int quarantineBufferChunks;

    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;

    private final LongSet directProtectedChunks;
    private final LongSet keepChunksWithHalo;
    private final long regionSignature;

    public YawpProtectionIndex(
        ServerLevel level,
        int centerX,
        int centerZ,
        int radiusBlocks,
        int haloChunks,
        int quarantineBufferChunks
    ) {
        this.dimensionId = level.dimension().identifier().toString();
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusBlocks = Math.max(16, radiusBlocks);
        this.haloChunks = Math.max(0, haloChunks);
        this.quarantineBufferChunks = Math.max(1, quarantineBufferChunks);

        this.minChunkX = (centerX - this.radiusBlocks) >> 4;
        this.maxChunkX = (centerX + this.radiusBlocks) >> 4;
        this.minChunkZ = (centerZ - this.radiusBlocks) >> 4;
        this.maxChunkZ = (centerZ + this.radiusBlocks) >> 4;
        this.regionSignature = computeRegionSignature(level);

        LongOpenHashSet direct = new LongOpenHashSet();
        LongOpenHashSet withHalo = new LongOpenHashSet();

        // 1. Standalone protected chunks & chunk ranges (/wildreset chunk & WildResetApi)
        for (StandaloneChunkRegistry.Ref ref : StandaloneChunkRegistry.all()) {
            if (!this.dimensionId.equals(ref.dimension())) continue;
            int[] b = ref.bounds();
            if (b == null) continue;
            markRect(direct, b[0], b[2], b[1], b[3]);
            markRect(
                withHalo,
                b[0] - this.haloChunks,
                b[2] + this.haloChunks,
                b[1] - this.haloChunks,
                b[3] + this.haloChunks
            );
        }

        // 2. External WildResetRegionProvider registered via WildResetApi
        WildResetRegionProvider apiProvider = WildResetApi.getRegionProvider();
        if (apiProvider != null) {
            try {
                Collection<WildResetRegionProvider.ProtectedRegion> extRegions = apiProvider.getProtectedRegions(level);
                if (extRegions != null) {
                    for (WildResetRegionProvider.ProtectedRegion r : extRegions) {
                        if (r == null) continue;
                        markRect(direct, r.minChunkX(), r.maxChunkX(), r.minChunkZ(), r.maxChunkZ());
                        markRect(
                            withHalo,
                            r.minChunkX() - this.haloChunks,
                            r.maxChunkX() + this.haloChunks,
                            r.minChunkZ() - this.haloChunks,
                            r.maxChunkZ() + this.haloChunks
                        );
                    }
                }
            } catch (Throwable t) {
                WildResetMod.LOGGER.warn("[WildReset] Failed to query external WildResetRegionProvider for {}", dimensionId, t);
            }
        }

        // 3. Optional YAWP regions (isolated so YAWP is completely optional)
        if (YAWP_LOADED) {
            for (WildResetRegionProvider.ProtectedRegion r : YawpCompat.collectActiveRegions(level)) {
                markRect(direct, r.minChunkX(), r.maxChunkX(), r.minChunkZ(), r.maxChunkZ());
                markRect(
                    withHalo,
                    r.minChunkX() - this.haloChunks,
                    r.maxChunkX() + this.haloChunks,
                    r.minChunkZ() - this.haloChunks,
                    r.maxChunkZ() + this.haloChunks
                );
            }
        }

        this.directProtectedChunks = LongSets.unmodifiable(direct);
        this.keepChunksWithHalo = LongSets.unmodifiable(withHalo);
    }

    public static YawpProtectionIndex fromConfig(ServerLevel level) {
        WildResetConfig cfg = WildResetConfig.get();
        return new YawpProtectionIndex(
            level,
            cfg.spawnCenterX,
            cfg.spawnCenterZ,
            cfg.cuboidRadiusBlocks,
            cfg.protectedBufferHaloChunks,
            cfg.quarantineOuterBufferChunks
        );
    }

    private static void markRect(LongOpenHashSet set, int minCx, int maxCx, int minCz, int maxCz) {
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                set.add(ChunkPos.pack(cx, cz));
            }
        }
    }

    public boolean isChunkInCuboid(int cx, int cz) {
        return cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ;
    }

    public boolean isChunkProtectedOrHalo(int cx, int cz) {
        return keepChunksWithHalo.contains(ChunkPos.pack(cx, cz));
    }

    public boolean isChunkDirectlyProtected(int cx, int cz) {
        return directProtectedChunks.contains(ChunkPos.pack(cx, cz));
    }

    public boolean isChunkResetTarget(int cx, int cz) {
        return isChunkInCuboid(cx, cz) && !isChunkProtectedOrHalo(cx, cz);
    }

    public boolean isChunkInQuarantineEnvelope(int cx, int cz) {
        int qMinX = minChunkX - quarantineBufferChunks;
        int qMaxX = maxChunkX + quarantineBufferChunks;
        int qMinZ = minChunkZ - quarantineBufferChunks;
        int qMaxZ = maxChunkZ + quarantineBufferChunks;
        return cx >= qMinX && cx <= qMaxX && cz >= qMinZ && cz <= qMaxZ && !isChunkProtectedOrHalo(cx, cz);
    }

    public static boolean isBlockInActiveYawpRegion(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        String dim = level.dimension().identifier().toString();
        if (StandaloneChunkRegistry.isBlockProtected(dim, pos.getX(), pos.getZ())) {
            return true;
        }
        WildResetRegionProvider apiProvider = WildResetApi.getRegionProvider();
        if (apiProvider != null) {
            try {
                if (apiProvider.isBlockProtected(level, pos)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        if (YAWP_LOADED) {
            return YawpCompat.isBlockInActiveRegion(level, pos);
        }
        return false;
    }

    public static long computeRegionSignature(ServerLevel level) {
        long sig = 0xCBF29CE484222325L ^ (StandaloneChunkRegistry.mutationSignature() * 0x9E3779B97F4A7C15L);
        WildResetRegionProvider apiProvider = WildResetApi.getRegionProvider();
        if (apiProvider != null) {
            try {
                Collection<WildResetRegionProvider.ProtectedRegion> ext = apiProvider.getProtectedRegions(level);
                if (ext != null) {
                    for (WildResetRegionProvider.ProtectedRegion r : ext) {
                        if (r == null) continue;
                        long h = ((long) r.minBlockX() * 31 + r.maxBlockX()) * 31 + ((long) r.minBlockZ() * 31 + r.maxBlockZ());
                        sig ^= h * 0xBF58476D1CE4E5B9L;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (YAWP_LOADED) {
            sig ^= YawpCompat.computeSignature(level);
        }
        return sig;
    }

    public int minChunkX() { return minChunkX; }
    public int maxChunkX() { return maxChunkX; }
    public int minChunkZ() { return minChunkZ; }
    public int maxChunkZ() { return maxChunkZ; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int radiusBlocks() { return radiusBlocks; }
    public int haloChunks() { return haloChunks; }
    public int quarantineBufferChunks() { return quarantineBufferChunks; }
    public int keepChunkCount() { return keepChunksWithHalo.size(); }
    public String dimensionId() { return dimensionId; }
    public long regionSignature() { return regionSignature; }
}
