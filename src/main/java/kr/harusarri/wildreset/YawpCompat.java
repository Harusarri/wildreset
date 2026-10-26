package kr.harusarri.wildreset;

import de.z0rdak.yawp.api.core.RegionManager;
import de.z0rdak.yawp.core.area.CuboidArea;
import de.z0rdak.yawp.core.area.IMarkableArea;
import de.z0rdak.yawp.core.area.SphereArea;
import de.z0rdak.yawp.core.region.IMarkableRegion;
import kr.harusarri.wildreset.api.WildResetRegionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Isolated YAWP compatibility bridge.
 * Only invoked when `FabricLoader.getInstance().isModLoaded("yawp")` is true so WildReset
 * works 100% standalone without requiring YAWP at runtime.
 */
public final class YawpCompat {
    private YawpCompat() {}

    public static List<WildResetRegionProvider.ProtectedRegion> collectActiveRegions(ServerLevel level) {
        List<WildResetRegionProvider.ProtectedRegion> result = new ArrayList<>();
        try {
            String dim = level.dimension().identifier().toString();
            var dimApiOpt = RegionManager.get().getDimRegionApi(level.dimension());
            if (dimApiOpt.isPresent()) {
                for (IMarkableRegion region : dimApiOpt.get().getAllLocalRegions()) {
                    if (region == null || !region.isActive()) continue;
                    String name = region.getName();
                    IMarkableArea area = region.getArea();
                    if (area instanceof CuboidArea cuboid) {
                        BoundingBox bb = cuboid.getArea();
                        result.add(new WildResetRegionProvider.ProtectedRegion(
                            dim, name, name, bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ()
                        ));
                    } else if (area instanceof SphereArea sphere) {
                        BlockPos c = sphere.getCenterPos();
                        int rad = (int) Math.ceil(sphere.getRadius());
                        result.add(new WildResetRegionProvider.ProtectedRegion(
                            dim, name, name, c.getX() - rad, c.getZ() - rad, c.getX() + rad, c.getZ() + rad
                        ));
                    }
                }
            }
        } catch (Throwable t) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to query YAWP regions", t);
        }
        return result;
    }

    public static boolean isBlockInActiveRegion(ServerLevel level, BlockPos pos) {
        try {
            var dimApiOpt = RegionManager.get().getDimRegionApi(level.dimension());
            if (dimApiOpt.isPresent()) {
                return dimApiOpt.get().getRegionsAt(pos).stream().anyMatch(IMarkableRegion::isActive);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static long computeSignature(ServerLevel level) {
        long sig = 0L;
        try {
            var dimApiOpt = RegionManager.get().getDimRegionApi(level.dimension());
            if (dimApiOpt.isPresent()) {
                for (IMarkableRegion region : dimApiOpt.get().getAllLocalRegions()) {
                    if (region == null || !region.isActive()) continue;
                    IMarkableArea area = region.getArea();
                    if (area instanceof CuboidArea cuboid) {
                        BoundingBox bb = cuboid.getArea();
                        long h = ((long) bb.minX() * 31 + bb.maxX()) * 31 + ((long) bb.minZ() * 31 + bb.maxZ());
                        sig ^= h * 0x9E3779B97F4A7C15L;
                    } else if (area instanceof SphereArea sphere) {
                        BlockPos c = sphere.getCenterPos();
                        long h = ((long) c.getX() * 31 + c.getZ()) * 31 + (long) Math.ceil(sphere.getRadius());
                        sig ^= h * 0xBF58476D1CE4E5B9L;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }
}
