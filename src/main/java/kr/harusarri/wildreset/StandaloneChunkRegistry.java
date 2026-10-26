package kr.harusarri.wildreset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone chunk & chunk-range protection registry and `/wildreset chunk` command handlers.
 * Allows WildReset to protect spawn builds and custom chunk ranges 100% standalone without YAWP,
 * using the same `chunk:x,z` / `chunks:x1,z1..x2,z2` syntax and interactive chat UI as Neodymium 2.
 */
public final class StandaloneChunkRegistry {
    private static final int MAX_CHUNK_COORD = 1_875_000;
    private static final int MAX_CHUNK_RANGE_SPAN = 256;
    private static final int PAGE_SIZE = 6;

    private static final Set<Ref> REGIONS = ConcurrentHashMap.newKeySet();
    private static volatile long mutationCounter = 1L;

    public record Ref(String dimension, String region) {
        public Ref {
            if (dimension == null || dimension.isBlank() || region == null || region.isBlank()) {
                throw new IllegalArgumentException("Invalid standalone chunk reference");
            }
        }

        public static Ref ofChunk(String dimension, int chunkX, int chunkZ) {
            validateChunkCoord(chunkX, chunkZ);
            return new Ref(dimension, "chunk:" + chunkX + "," + chunkZ);
        }

        public static Ref ofChunkRange(String dimension, int cx1, int cz1, int cx2, int cz2) {
            validateChunkCoord(cx1, cz1);
            validateChunkCoord(cx2, cz2);
            int minCx = Math.min(cx1, cx2);
            int maxCx = Math.max(cx1, cx2);
            int minCz = Math.min(cz1, cz2);
            int maxCz = Math.max(cz1, cz2);
            if (maxCx - minCx + 1 > MAX_CHUNK_RANGE_SPAN || maxCz - minCz + 1 > MAX_CHUNK_RANGE_SPAN) {
                throw new IllegalArgumentException("Chunk range span exceeds maximum (" + MAX_CHUNK_RANGE_SPAN + ")");
            }
            if (minCx == maxCx && minCz == maxCz) {
                return ofChunk(dimension, minCx, minCz);
            }
            return new Ref(dimension, "chunks:" + minCx + "," + minCz + ".." + maxCx + "," + maxCz);
        }

        public int[] bounds() {
            return parseStandaloneChunkBounds(this.region);
        }

        public boolean coversChunk(String dim, int chunkX, int chunkZ) {
            if (!this.dimension.equals(dim)) return false;
            int[] b = bounds();
            if (b == null) return false;
            return chunkX >= b[0] && chunkX <= b[2] && chunkZ >= b[1] && chunkZ <= b[3];
        }

        public boolean coversBlock(String dim, int blockX, int blockZ) {
            return coversChunk(dim, blockX >> 4, blockZ >> 4);
        }
    }

    private StandaloneChunkRegistry() {}

    public static Path regionsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("wildreset_regions.json");
    }

    private static void validateChunkCoord(int cx, int cz) {
        if (cx < -MAX_CHUNK_COORD || cx > MAX_CHUNK_COORD || cz < -MAX_CHUNK_COORD || cz > MAX_CHUNK_COORD) {
            throw new IllegalArgumentException("Chunk coordinates out of bounds: " + cx + ", " + cz);
        }
    }

    /**
     * Parses "chunk:x,z" or "chunks:x1,z1..x2,z2" into [minCx, minCz, maxCx, maxCz], or null if invalid.
     */
    public static int[] parseStandaloneChunkBounds(String regionId) {
        if (regionId == null) return null;
        try {
            if (regionId.startsWith("chunk:")) {
                String rest = regionId.substring("chunk:".length());
                int comma = rest.indexOf(',');
                if (comma <= 0 || comma != rest.lastIndexOf(',')) return null;
                int cx = Integer.parseInt(rest.substring(0, comma).trim());
                int cz = Integer.parseInt(rest.substring(comma + 1).trim());
                validateChunkCoord(cx, cz);
                return new int[]{cx, cz, cx, cz};
            }
            if (regionId.startsWith("chunks:")) {
                String rest = regionId.substring("chunks:".length());
                int dots = rest.indexOf("..");
                if (dots <= 0) return null;
                String first = rest.substring(0, dots);
                String second = rest.substring(dots + 2);
                int c1 = first.indexOf(',');
                int c2 = second.indexOf(',');
                if (c1 <= 0 || c1 != first.lastIndexOf(',') || c2 <= 0 || c2 != second.lastIndexOf(',')) return null;
                int cx1 = Integer.parseInt(first.substring(0, c1).trim());
                int cz1 = Integer.parseInt(first.substring(c1 + 1).trim());
                int cx2 = Integer.parseInt(second.substring(0, c2).trim());
                int cz2 = Integer.parseInt(second.substring(c2 + 1).trim());
                validateChunkCoord(cx1, cz1);
                validateChunkCoord(cx2, cz2);
                if (cx1 > cx2 || cz1 > cz2) return null;
                if (cx2 - cx1 + 1 > MAX_CHUNK_RANGE_SPAN || cz2 - cz1 + 1 > MAX_CHUNK_RANGE_SPAN) return null;
                return new int[]{cx1, cz1, cx2, cz2};
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    public static synchronized void load() {
        Path path = regionsPath();
        REGIONS.clear();
        if (!Files.exists(path)) {
            save();
            return;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root != null && root.has("regions") && root.get("regions").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("regions")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String dim = obj.has("dimension") ? obj.get("dimension").getAsString() : "minecraft:overworld";
                    String reg = obj.has("region") ? obj.get("region").getAsString() : "";
                    boolean prot = !obj.has("protected") || obj.get("protected").getAsBoolean();
                    if (prot && parseStandaloneChunkBounds(reg) != null) {
                        REGIONS.add(new Ref(dim, reg));
                    }
                }
            }
            mutationCounter++;
            WildResetEngine.invalidateProtectionIndex();
        } catch (Exception e) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to load config/wildreset_regions.json", e);
        }
    }

    public static synchronized void save() {
        Path path = regionsPath();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 1);
            JsonArray arr = new JsonArray();
            REGIONS.stream()
                .sorted(Comparator.comparing(Ref::dimension).thenComparing(Ref::region))
                .forEach(r -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("dimension", r.dimension());
                    o.addProperty("region", r.region());
                    o.addProperty("protected", true);
                    arr.add(o);
                });
            root.add("regions", arr);
            Files.writeString(path, WildResetConfig.GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to save config/wildreset_regions.json", e);
        }
    }

    public static Set<Ref> all() {
        return Set.copyOf(REGIONS);
    }

    public static long mutationSignature() {
        return mutationCounter;
    }

    public static boolean containsExact(Ref ref) {
        return REGIONS.contains(ref);
    }

    public static boolean isBlockProtected(String dimension, int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        for (Ref ref : REGIONS) {
            if (ref.coversChunk(dimension, cx, cz)) {
                return true;
            }
        }
        return false;
    }

    public static void addChunk(String dimension, int chunkX, int chunkZ) {
        Ref ref = Ref.ofChunk(dimension, chunkX, chunkZ);
        REGIONS.add(ref);
        mutationCounter++;
        save();
        WildResetEngine.invalidateProtectionIndex();
    }

    public static void addChunkRange(String dimension, int cx1, int cz1, int cx2, int cz2) {
        Ref ref = Ref.ofChunkRange(dimension, cx1, cz1, cx2, cz2);
        REGIONS.add(ref);
        mutationCounter++;
        save();
        WildResetEngine.invalidateProtectionIndex();
    }

    public static int removeCoveringChunk(String dimension, int chunkX, int chunkZ) {
        List<Ref> matching = new ArrayList<>();
        for (Ref ref : REGIONS) {
            if (ref.coversChunk(dimension, chunkX, chunkZ)) {
                matching.add(ref);
            }
        }
        if (!matching.isEmpty()) {
            matching.forEach(REGIONS::remove);
            mutationCounter++;
            save();
            WildResetEngine.invalidateProtectionIndex();
        }
        return matching.size();
    }

    public static boolean removeById(String dimension, String regionId) {
        Ref ref = new Ref(dimension, regionId);
        if (REGIONS.remove(ref)) {
            mutationCounter++;
            save();
            WildResetEngine.invalidateProtectionIndex();
            return true;
        }
        return false;
    }

    // =========================================================================
    // Interactive /wildreset chunk Command Handlers (Neodymium-style UI)
    // =========================================================================

    private static MutableComponent text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(color);
    }

    private static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
            .withColor(color)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    public static int sendChunkRegionsPage(CommandSourceStack source, int requestedPage) {
        String lang = WildResetI18n.resolveLang(source);
        List<Ref> refs = new ArrayList<>(REGIONS);
        refs.sort(Comparator.comparing(Ref::dimension).thenComparing(Ref::region));

        int total = refs.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 1, totalPages);
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);

        MutableComponent out = Component.empty();
        String header = WildResetI18n.raw(lang, "cmd.chunk.list_header")
            .replace("<page>", String.valueOf(page))
            .replace("<total_pages>", String.valueOf(totalPages))
            .replace("<total>", String.valueOf(total));
        out.append(text(header + "  ", ChatFormatting.GOLD));

        if (source.getEntity() instanceof ServerPlayer) {
            out.append(button(
                WildResetI18n.raw(lang, "cmd.chunk.btn_toggle"),
                ChatFormatting.GREEN,
                "/wildreset chunk toggle",
                WildResetI18n.raw(lang, "cmd.chunk.hover_toggle")
            ));
        }
        out.append(text("\n", ChatFormatting.GRAY));

        if (total == 0) {
            out.append(text(WildResetI18n.raw(lang, "cmd.chunk.empty") + "\n", ChatFormatting.GRAY));
        } else {
            for (int i = start; i < end; i++) {
                Ref ref = refs.get(i);
                int[] b = ref.bounds();
                if (b == null) continue;
                int minBlockX = b[0] * 16;
                int minBlockZ = b[1] * 16;
                int maxBlockX = b[2] * 16 + 15;
                int maxBlockZ = b[3] * 16 + 15;
                int centerX = (minBlockX + maxBlockX) / 2;
                int centerZ = (minBlockZ + maxBlockZ) / 2;

                String disp = (b[0] == b[2] && b[1] == b[3])
                    ? WildResetI18n.raw(lang, "cmd.chunk.fmt_single")
                        .replace("<cx>", String.valueOf(b[0]))
                        .replace("<cz>", String.valueOf(b[1]))
                    : WildResetI18n.raw(lang, "cmd.chunk.fmt_range")
                        .replace("<min_cx>", String.valueOf(b[0]))
                        .replace("<min_cz>", String.valueOf(b[1]))
                        .replace("<max_cx>", String.valueOf(b[2]))
                        .replace("<max_cz>", String.valueOf(b[3]))
                        .replace("<w>", String.valueOf(b[2] - b[0] + 1))
                        .replace("<h>", String.valueOf(b[3] - b[1] + 1));

                String tpCmd = "/execute in " + ref.dimension() + " run tp @s " + centerX + " 80 " + centerZ;
                String removeCmd = "/wildreset chunk removeid \"" + ref.dimension() + "\" " + ref.region();

                out.append(text(String.format(Locale.ROOT, "  %02d  ", i + 1), ChatFormatting.GRAY))
                    .append(button(WildResetI18n.raw(lang, "cmd.chunk.btn_tp"), ChatFormatting.YELLOW, tpCmd, tpCmd))
                    .append(text(" ", ChatFormatting.DARK_GRAY))
                    .append(button(WildResetI18n.raw(lang, "cmd.chunk.btn_remove"), ChatFormatting.RED, removeCmd, removeCmd))
                    .append(text("  " + ref.dimension() + "  ", ChatFormatting.GRAY))
                    .append(text(disp, ChatFormatting.WHITE))
                    .append(text(String.format(Locale.ROOT, "  (%d, 80, %d)\n", centerX, centerZ), ChatFormatting.GRAY));
            }
        }

        if (page > 1) {
            out.append(button("[<]", ChatFormatting.YELLOW, "/wildreset chunk list " + (page - 1), "Page " + (page - 1)));
        } else {
            out.append(text("[<]", ChatFormatting.DARK_GRAY));
        }
        out.append(text(String.format(Locale.ROOT, "  %d / %d  ", page, totalPages), ChatFormatting.WHITE));
        if (page < totalPages) {
            out.append(button("[>]", ChatFormatting.YELLOW, "/wildreset chunk list " + (page + 1), "Page " + (page + 1)));
        } else {
            out.append(text("[>]", ChatFormatting.DARK_GRAY));
        }

        source.sendSuccess(() -> out, false);
        return 1;
    }

    public static int toggleCurrentChunk(CommandSourceStack source) {
        String lang = WildResetI18n.resolveLang(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.chunk.player_only")));
            return 0;
        }
        String dim = player.level().dimension().identifier().toString();
        BlockPos pos = player.blockPosition();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        Ref exact = Ref.ofChunk(dim, cx, cz);
        if (containsExact(exact)) {
            return removeChunkCoords(source, cx, cz);
        }
        return addChunkCoords(source, cx, cz);
    }

    public static int addCurrentChunk(CommandSourceStack source, int radius) {
        String lang = WildResetI18n.resolveLang(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.chunk.player_only")));
            return 0;
        }
        if (radius < 0 || radius > 16) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.chunk.radius_range")));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (radius == 0) {
            return addChunkCoords(source, cx, cz);
        }
        return addChunkRangeCoords(source, cx - radius, cz - radius, cx + radius, cz + radius);
    }

    public static int addChunkCoords(CommandSourceStack source, int chunkX, int chunkZ) {
        String lang = WildResetI18n.resolveLang(source);
        String dim = source.getLevel().dimension().identifier().toString();
        try {
            addChunk(dim, chunkX, chunkZ);
            MiniText.sendCommandFeedback(
                source,
                WildResetI18n.raw(lang, "cmd.chunk.added_single"),
                Map.of("cx", String.valueOf(chunkX), "cz", String.valueOf(chunkZ), "dim", dim)
            );
            return sendChunkRegionsPage(source, 1);
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    public static int addChunkRangeCoords(CommandSourceStack source, int cx1, int cz1, int cx2, int cz2) {
        String lang = WildResetI18n.resolveLang(source);
        String dim = source.getLevel().dimension().identifier().toString();
        try {
            addChunkRange(dim, cx1, cz1, cx2, cz2);
            int minCx = Math.min(cx1, cx2), maxCx = Math.max(cx1, cx2);
            int minCz = Math.min(cz1, cz2), maxCz = Math.max(cz1, cz2);
            MiniText.sendCommandFeedback(
                source,
                WildResetI18n.raw(lang, "cmd.chunk.added_range"),
                Map.of(
                    "min_cx", String.valueOf(minCx),
                    "min_cz", String.valueOf(minCz),
                    "max_cx", String.valueOf(maxCx),
                    "max_cz", String.valueOf(maxCz),
                    "dim", dim
                )
            );
            return sendChunkRegionsPage(source, 1);
        } catch (Exception ex) {
            source.sendFailure(Component.literal(ex.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    public static int removeCurrentChunk(CommandSourceStack source) {
        String lang = WildResetI18n.resolveLang(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.chunk.player_only")));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        return removeChunkCoords(source, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static int removeChunkCoords(CommandSourceStack source, int chunkX, int chunkZ) {
        String lang = WildResetI18n.resolveLang(source);
        String dim = source.getLevel().dimension().identifier().toString();
        int removed = removeCoveringChunk(dim, chunkX, chunkZ);
        if (removed == 0) {
            MiniText.sendCommandFeedback(
                source,
                WildResetI18n.raw(lang, "cmd.chunk.none_at"),
                Map.of("cx", String.valueOf(chunkX), "cz", String.valueOf(chunkZ))
            );
            return 0;
        }
        MiniText.sendCommandFeedback(
            source,
            WildResetI18n.raw(lang, "cmd.chunk.removed_coords"),
            Map.of("cx", String.valueOf(chunkX), "cz", String.valueOf(chunkZ), "count", String.valueOf(removed))
        );
        return sendChunkRegionsPage(source, 1);
    }

    public static int removeChunkById(CommandSourceStack source, String dimension, String regionId) {
        String lang = WildResetI18n.resolveLang(source);
        if (!removeById(dimension, regionId)) {
            MiniText.sendCommandFeedback(
                source,
                WildResetI18n.raw(lang, "cmd.chunk.none_id"),
                Map.of("region", regionId)
            );
            return 0;
        }
        MiniText.sendCommandFeedback(
            source,
            WildResetI18n.raw(lang, "cmd.chunk.removed_id"),
            Map.of("region", regionId)
        );
        return sendChunkRegionsPage(source, 1);
    }

    public static int resetCurrentChunkCmd(CommandSourceStack source, int radius) {
        String lang = WildResetI18n.resolveLang(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.chunk.player_only")));
            return 0;
        }
        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();
        List<ChunkPos> targets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                targets.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        boolean ok = WildResetEngine.startTargetedChunkReset(source.getServer(), targets);
        if (!ok) {
            source.sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.run.failed")));
            return 0;
        }
        MiniText.sendCommandFeedback(
            source,
            WildResetI18n.raw(lang, "cmd.chunk.reset_started"),
            Map.of("count", String.valueOf(targets.size()), "cx", String.valueOf(cx), "cz", String.valueOf(cz))
        );
        return 1;
    }
}
