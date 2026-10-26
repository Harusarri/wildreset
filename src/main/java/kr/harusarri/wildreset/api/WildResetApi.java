package kr.harusarri.wildreset.api;

import kr.harusarri.wildreset.StandaloneChunkRegistry;
import kr.harusarri.wildreset.WildResetEngine;
import kr.harusarri.wildreset.YawpProtectionIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Set;

/**
 * Public API for integrating external region/protection managers with WildReset,
 * managing standalone protected chunks/ranges, or triggering wilderness/chunk resets programmatically.
 */
public final class WildResetApi {
    private static volatile WildResetRegionProvider provider;

    private WildResetApi() {}

    /**
     * Registers an external region provider and invalidates the cached protection index.
     */
    public static void registerRegionProvider(WildResetRegionProvider regionProvider) {
        provider = regionProvider;
        WildResetEngine.invalidateProtectionIndex();
    }

    public static WildResetRegionProvider getRegionProvider() {
        return provider;
    }

    /**
     * Notifies WildReset that external or standalone protected regions have changed.
     */
    public static void onRegionMutated() {
        WildResetEngine.invalidateProtectionIndex();
    }

    /**
     * Adds a standalone protected chunk (`chunk:<chunkX>,<chunkZ>`) and persists `wildreset_regions.json`.
     */
    public static void addProtectedChunk(String dimension, int chunkX, int chunkZ) {
        StandaloneChunkRegistry.addChunk(dimension, chunkX, chunkZ);
    }

    /**
     * Adds a standalone protected rectangular chunk range (`chunks:<minX>,<minZ>..<maxX>,<maxZ>`)
     * and persists `wildreset_regions.json`.
     */
    public static void addProtectedChunkRange(String dimension, int cx1, int cz1, int cx2, int cz2) {
        StandaloneChunkRegistry.addChunkRange(dimension, cx1, cz1, cx2, cz2);
    }

    /**
     * Removes any standalone protected chunk or range covering `(chunkX, chunkZ)` in the given dimension.
     * @return number of standalone region entries removed
     */
    public static int removeProtectedChunkAt(String dimension, int chunkX, int chunkZ) {
        return StandaloneChunkRegistry.removeCoveringChunk(dimension, chunkX, chunkZ);
    }

    /**
     * Removes a standalone protected region by exact ID (e.g. `chunk:0,0` or `chunks:-2,-2..2,2`).
     */
    public static boolean removeProtectedRegionId(String dimension, String regionId) {
        return StandaloneChunkRegistry.removeById(dimension, regionId);
    }

    /**
     * Returns an unmodifiable snapshot of all standalone protected chunk/range references.
     */
    public static Set<StandaloneChunkRegistry.Ref> getStandaloneRegions() {
        return StandaloneChunkRegistry.all();
    }

    /**
     * Queries whether the given chunk is protected (including configured buffer halo) from wilderness resets.
     */
    public static boolean isProtectedChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) return false;
        return YawpProtectionIndex.fromConfig(level).isChunkProtectedOrHalo(chunkX, chunkZ);
    }

    /**
     * Queries whether the given block position is inside any active protected region (standalone, API provider, or YAWP).
     */
    public static boolean isProtectedBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        return YawpProtectionIndex.isBlockInActiveYawpRegion(level, pos);
    }

    /**
     * Starts a full-radius wilderness reset across the configured spawn cuboid.
     */
    public static boolean startFullReset(MinecraftServer server) {
        return WildResetEngine.startReset(server, 0);
    }

    /**
     * Starts a wilderness reset within the specified chunk radius around spawn center.
     */
    public static boolean startRadiusReset(MinecraftServer server, int radiusChunks) {
        return WildResetEngine.startReset(server, Math.max(1, radiusChunks));
    }

    /**
     * Starts a targeted zero-downtime reset for the specified list of chunks.
     */
    public static boolean startTargetedChunkReset(MinecraftServer server, List<ChunkPos> chunks) {
        return WildResetEngine.startTargetedChunkReset(server, chunks);
    }
}
