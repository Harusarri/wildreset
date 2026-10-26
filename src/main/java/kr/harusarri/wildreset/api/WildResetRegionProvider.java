package kr.harusarri.wildreset.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/**
 * SPI implemented by external region/claim managers to supply protected regions to WildReset.
 * All chunks intersecting a protected region (plus the configured halo buffer) are excluded from wilderness resets.
 */
public interface WildResetRegionProvider {
    record ProtectedRegion(
        String dimension,
        String regionId,
        String displayName,
        int minBlockX,
        int minBlockZ,
        int maxBlockX,
        int maxBlockZ
    ) {
        public int minChunkX() {
            return minBlockX >> 4;
        }

        public int maxChunkX() {
            return maxBlockX >> 4;
        }

        public int minChunkZ() {
            return minBlockZ >> 4;
        }

        public int maxChunkZ() {
            return maxBlockZ >> 4;
        }

        public int centerX() {
            return (minBlockX + maxBlockX) / 2;
        }

        public int centerZ() {
            return (minBlockZ + maxBlockZ) / 2;
        }

        public boolean containsBlock(int blockX, int blockZ) {
            return blockX >= minBlockX && blockX <= maxBlockX
                && blockZ >= minBlockZ && blockZ <= maxBlockZ;
        }
    }

    /**
     * Returns all active protected regions in the given level.
     */
    Collection<ProtectedRegion> getProtectedRegions(ServerLevel level);

    /**
     * Returns true if the given block position is inside an active protected region.
     */
    default boolean isBlockProtected(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        for (ProtectedRegion r : getProtectedRegions(level)) {
            if (r != null && r.containsBlock(pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }
}
