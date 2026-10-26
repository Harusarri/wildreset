package kr.harusarri.wildreset;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EvacuatedChestRegistry {
    public static final String NBT_EVACUATED = "wildreset_evacuated";
    public static final String NBT_UNLOCK_AT = "wildreset_unlock_at";
    public static final String NBT_OWNER_UUID = "wildreset_owner";
    public static final String NBT_OWNER_NAME = "wildreset_owner_name";
    public static final String NBT_SEASON = "wildreset_season";

    public record Entry(
        String dimension,
        int x,
        int y,
        int z,
        String ownerUuid,
        String ownerName,
        long unlockAtMs,
        int season
    ) {
        public String key() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, PathOwner> PLACED_OWNERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> NEWBIE_WARNING_COUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEWBIE_LAST_WARNING_MS = new ConcurrentHashMap<>();
    private static volatile int currentSeason = 1;

    public record PathOwner(String ownerUuid, String ownerName) {}

    private EvacuatedChestRegistry() {}

    public static String posKey(String dimension, int x, int y, int z) {
        return dimension + "@" + x + "," + y + "," + z;
    }

    public static String posKey(Level level, BlockPos pos) {
        return posKey(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static int currentSeason() {
        return currentSeason;
    }

    public static int nextSeason() {
        currentSeason++;
        return currentSeason;
    }

    public static boolean isContainerBlock(BlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        return b instanceof ChestBlock
            || b instanceof BarrelBlock
            || b instanceof ShulkerBoxBlock
            || b instanceof AbstractFurnaceBlock
            || b instanceof DispenserBlock
            || b instanceof HopperBlock
            || b instanceof BrewingStandBlock
            || b instanceof ChiseledBookShelfBlock;
    }

    public static boolean isContainerId(String id) {
        if (id == null) return false;
        return id.equals("minecraft:chest")
            || id.equals("minecraft:trapped_chest")
            || id.equals("minecraft:barrel")
            || id.endsWith("shulker_box")
            || id.equals("minecraft:furnace")
            || id.equals("minecraft:blast_furnace")
            || id.equals("minecraft:smoker")
            || id.equals("minecraft:dispenser")
            || id.equals("minecraft:dropper")
            || id.equals("minecraft:hopper")
            || id.equals("minecraft:brewing_stand")
            || id.equals("minecraft:chiseled_bookshelf");
    }

    public static void onPlayerPlacedBlock(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState placedState) {
        WildResetConfig cfg = WildResetConfig.get();
        if (!cfg.enabled) return;

        // 1. If it's a container, record the owner in memory and on the BlockEntity's CustomData
        if (isContainerBlock(placedState)) {
            String uuidStr = player.getUUID().toString();
            String nameStr = player.getName().getString();
            PLACED_OWNERS.put(posKey(level, pos), new PathOwner(uuidStr, nameStr));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag custom = be.components().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                custom.putString(NBT_OWNER_UUID, uuidStr);
                custom.putString(NBT_OWNER_NAME, nameStr);
                DataComponentMap.Builder builder = DataComponentMap.builder().addAll(be.components());
                builder.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
                be.setComponents(builder.build());
                be.setChanged();
            }
        }

        // 2. Check if inside Spawn Reset Cuboid & not in a YAWP protected region -> trigger Newbie Warning (3~4 times)
        if (!cfg.newbieWarningEnabled || !level.dimension().identifier().toString().equals(cfg.dimension)) {
            return;
        }
        int dx = Math.abs(pos.getX() - cfg.spawnCenterX);
        int dz = Math.abs(pos.getZ() - cfg.spawnCenterZ);
        if (dx <= cfg.cuboidRadiusBlocks && dz <= cfg.cuboidRadiusBlocks) {
            if (!YawpProtectionIndex.isBlockInActiveYawpRegion(level, pos)) {
                UUID uid = player.getUUID();
                int count = NEWBIE_WARNING_COUNTS.getOrDefault(uid, 0);
                if (count < cfg.newbieWarningMaxCount) {
                    long now = System.currentTimeMillis();
                    long last = NEWBIE_LAST_WARNING_MS.getOrDefault(uid, 0L);
                    if (now - last >= cfg.newbieWarningCooldownSeconds * 1000L) {
                        int nextCount = count + 1;
                        NEWBIE_WARNING_COUNTS.put(uid, nextCount);
                        NEWBIE_LAST_WARNING_MS.put(uid, now);

                        Map<String, String> placeholders = Map.of(
                            "radius", String.valueOf(cfg.cuboidRadiusBlocks),
                            "count", String.valueOf(nextCount),
                            "max", String.valueOf(cfg.newbieWarningMaxCount),
                            "remaining", String.valueOf(Math.max(0, cfg.newbieWarningMaxCount - nextCount)),
                            "hours", String.valueOf(cfg.chestProtectionHours)
                        );
                        if (cfg.newbieWarningActionBarEnabled
                            && cfg.messages.newbieBlockPlaceActionBar != null
                            && !cfg.messages.newbieBlockPlaceActionBar.isBlank()) {
                            String tpl = WildResetI18n.resolvePlayerTemplate(
                                player,
                                "msg.newbie_block_place.actionbar",
                                cfg.messages.newbieBlockPlaceActionBar,
                                WildResetI18n.DEFAULT_KO_MESSAGES.newbieBlockPlaceActionBar
                            );
                            MiniText.sendActionBar(player, tpl, placeholders);
                        }
                        if (cfg.newbieWarningChatEnabled
                            && cfg.messages.newbieBlockPlaceChat != null
                            && !cfg.messages.newbieBlockPlaceChat.isBlank()) {
                            String tpl = WildResetI18n.resolvePlayerTemplate(
                                player,
                                "msg.newbie_block_place.chat",
                                cfg.messages.newbieBlockPlaceChat,
                                WildResetI18n.DEFAULT_KO_MESSAGES.newbieBlockPlaceChat
                            );
                            MiniText.sendChat(player, tpl, placeholders);
                        }
                        level.playSound(
                            null,
                            player.blockPosition(),
                            SoundEvents.NOTE_BLOCK_PLING.value(),
                            SoundSource.PLAYERS,
                            0.8f,
                            1.2f
                        );
                    }
                }
            }
        }
    }

    public static void resetWarningCount(UUID playerUuid) {
        NEWBIE_WARNING_COUNTS.remove(playerUuid);
        NEWBIE_LAST_WARNING_MS.remove(playerUuid);
    }

    public static int getWarningCount(UUID playerUuid) {
        return NEWBIE_WARNING_COUNTS.getOrDefault(playerUuid, 0);
    }

    public static PathOwner getPlacedOwner(String dimension, int x, int y, int z) {
        return PLACED_OWNERS.get(posKey(dimension, x, y, z));
    }

    public static void registerEvacuatedChest(Entry entry) {
        ENTRIES.put(entry.key(), entry);
    }

    public static Entry getEvacuatedEntry(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        String key = posKey(level, pos);
        Entry e = ENTRIES.get(key);
        if (e != null) {
            return e;
        }
        // Fallback: inspect BlockEntity's CustomData (0 tick overhead, lazy evaluation on interaction)
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            CustomData cd = be.components().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (!cd.isEmpty()) {
                CompoundTag tag = cd.copyTag();
                if (tag.getByteOr(NBT_EVACUATED, (byte) 0) == 1) {
                    long unlockAt = tag.getLongOr(NBT_UNLOCK_AT, 0L);
                    String ownerUuid = tag.getStringOr(NBT_OWNER_UUID, "");
                    String ownerName = tag.getStringOr(NBT_OWNER_NAME, "Player");
                    int season = tag.getIntOr(NBT_SEASON, 1);
                    Entry loaded = new Entry(
                        level.dimension().identifier().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        ownerUuid, ownerName, unlockAt, season
                    );
                    ENTRIES.put(key, loaded);
                    return loaded;
                }
            }
        }
        return null;
    }

    public static boolean isEvacuatedChest(Level level, BlockPos pos) {
        return getEvacuatedEntry(level, pos) != null;
    }

    public static boolean isLockedEvacuatedChest(Level level, BlockPos pos) {
        Entry entry = getEvacuatedEntry(level, pos);
        if (entry == null) {
            return false;
        }
        return System.currentTimeMillis() < entry.unlockAtMs();
    }

    public static boolean isLockedForPlayer(Level level, BlockPos pos, ServerPlayer player) {
        Entry entry = getEvacuatedEntry(level, pos);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= entry.unlockAtMs()) {
            return false;
        }
        return entry.ownerUuid().isEmpty() || !entry.ownerUuid().equalsIgnoreCase(player.getUUID().toString());
    }

    public static void sendDenyMessageFor(Level level, BlockPos pos, ServerPlayer player) {
        Entry entry = getEvacuatedEntry(level, pos);
        if (entry == null) return;
        long now = System.currentTimeMillis();
        String rem = formatRemainingTime(Math.max(1000L, entry.unlockAtMs() - now), player);
        WildResetConfig cfg = WildResetConfig.get();
        String tpl = WildResetI18n.resolvePlayerTemplate(
            player,
            "msg.chest.protected_deny.actionbar",
            cfg.messages.chestProtectedDenyActionBar,
            WildResetI18n.DEFAULT_KO_MESSAGES.chestProtectedDenyActionBar
        );
        MiniText.sendActionBar(player, tpl, Map.of(
            "remaining_time", rem,
            "owner", entry.ownerName()
        ));
    }

    public static void removeEntry(Level level, BlockPos pos) {
        ENTRIES.remove(posKey(level, pos));
        PLACED_OWNERS.remove(posKey(level, pos));
    }

    public static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        Entry entry = getEvacuatedEntry(world, pos);
        if (entry != null) {
            long now = System.currentTimeMillis();
            WildResetConfig cfg = WildResetConfig.get();
            if (now < entry.unlockAtMs()) {
                boolean isOwner = !entry.ownerUuid().isEmpty() && entry.ownerUuid().equalsIgnoreCase(sp.getUUID().toString());
                String rem = formatRemainingTime(entry.unlockAtMs() - now, sp);
                if (!isOwner) {
                    String tpl = WildResetI18n.resolvePlayerTemplate(
                        sp,
                        "msg.chest.protected_deny.actionbar",
                        cfg.messages.chestProtectedDenyActionBar,
                        WildResetI18n.DEFAULT_KO_MESSAGES.chestProtectedDenyActionBar
                    );
                    MiniText.sendActionBar(sp, tpl, Map.of(
                        "remaining_time", rem,
                        "owner", entry.ownerName()
                    ));
                    return InteractionResult.FAIL;
                } else if (hand == InteractionHand.MAIN_HAND && cfg.messages.chestOwnerOpenActionBar != null) {
                    String tpl = WildResetI18n.resolvePlayerTemplate(
                        sp,
                        "msg.chest.owner_open.actionbar",
                        cfg.messages.chestOwnerOpenActionBar,
                        WildResetI18n.DEFAULT_KO_MESSAGES.chestOwnerOpenActionBar
                    );
                    MiniText.sendActionBar(sp, tpl, Map.of(
                        "remaining_time", rem,
                        "owner", entry.ownerName()
                    ));
                }
            } else if (hand == InteractionHand.MAIN_HAND) {
                String tpl = WildResetI18n.resolvePlayerTemplate(
                    sp,
                    "msg.chest.expired_notice.actionbar",
                    cfg.messages.chestExpiredNoticeActionBar,
                    WildResetI18n.DEFAULT_KO_MESSAGES.chestExpiredNoticeActionBar
                );
                MiniText.sendActionBar(sp, tpl, Map.of(
                    "owner", entry.ownerName()
                ));
            }
            return InteractionResult.PASS;
        }

        // Prevent non-owners from placing a Hopper Minecart on a rail under/near a locked evacuated chest
        ItemStack held = sp.getItemInHand(hand);
        if (held.is(Items.HOPPER_MINECART)) {
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos abovePos = pos.above(dy);
                if (isLockedForPlayer(world, abovePos, sp)) {
                    sendDenyMessageFor(world, abovePos, sp);
                    return InteractionResult.FAIL;
                }
            }
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onAttackBlock(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        Entry entry = getEvacuatedEntry(world, pos);
        if (entry == null) {
            return InteractionResult.PASS;
        }
        long now = System.currentTimeMillis();
        WildResetConfig cfg = WildResetConfig.get();
        if (now < entry.unlockAtMs()) {
            boolean isOwner = !entry.ownerUuid().isEmpty() && entry.ownerUuid().equalsIgnoreCase(sp.getUUID().toString());
            String rem = formatRemainingTime(entry.unlockAtMs() - now, sp);
            if (!isOwner) {
                String tpl = WildResetI18n.resolvePlayerTemplate(
                    sp,
                    "msg.chest.protected_deny.actionbar",
                    cfg.messages.chestProtectedDenyActionBar,
                    WildResetI18n.DEFAULT_KO_MESSAGES.chestProtectedDenyActionBar
                );
                MiniText.sendActionBar(sp, tpl, Map.of(
                    "remaining_time", rem,
                    "owner", entry.ownerName()
                ));
                return InteractionResult.FAIL;
            } else if (cfg.messages.chestOwnerOpenActionBar != null) {
                String tpl = WildResetI18n.resolvePlayerTemplate(
                    sp,
                    "msg.chest.owner_open.actionbar",
                    cfg.messages.chestOwnerOpenActionBar,
                    WildResetI18n.DEFAULT_KO_MESSAGES.chestOwnerOpenActionBar
                );
                MiniText.sendActionBar(sp, tpl, Map.of(
                    "remaining_time", rem,
                    "owner", entry.ownerName()
                ));
            }
        } else {
            String tpl = WildResetI18n.resolvePlayerTemplate(
                sp,
                "msg.chest.expired_notice.actionbar",
                cfg.messages.chestExpiredNoticeActionBar,
                WildResetI18n.DEFAULT_KO_MESSAGES.chestExpiredNoticeActionBar
            );
            MiniText.sendActionBar(sp, tpl, Map.of(
                "owner", entry.ownerName()
            ));
        }
        return InteractionResult.PASS;
    }

    public static boolean onBeforeBreakBlock(Level world, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
            return true;
        }
        Entry entry = getEvacuatedEntry(world, pos);
        if (entry == null) {
            removeEntry(world, pos);
            return true;
        }
        long now = System.currentTimeMillis();
        WildResetConfig cfg = WildResetConfig.get();
        if (now < entry.unlockAtMs()) {
            boolean isOwner = !entry.ownerUuid().isEmpty() && entry.ownerUuid().equalsIgnoreCase(sp.getUUID().toString());
            if (!isOwner) {
                String rem = formatRemainingTime(entry.unlockAtMs() - now, sp);
                String tpl = WildResetI18n.resolvePlayerTemplate(
                    sp,
                    "msg.chest.protected_deny.actionbar",
                    cfg.messages.chestProtectedDenyActionBar,
                    WildResetI18n.DEFAULT_KO_MESSAGES.chestProtectedDenyActionBar
                );
                MiniText.sendActionBar(sp, tpl, Map.of(
                    "remaining_time", rem,
                    "owner", entry.ownerName()
                ));
                return false;
            }
        }
        removeEntry(world, pos);
        return true;
    }

    public static String formatRemainingTime(long remainingMs, ServerPlayer player) {
        return WildResetI18n.formatRemainingTime(remainingMs, WildResetI18n.resolveLang(player));
    }

    public static String formatRemainingTime(long remainingMs) {
        return WildResetI18n.formatRemainingTime(remainingMs, WildResetI18n.resolveLang((ServerPlayer) null));
    }

    public static void save(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve("evacuated_chests.json");
            Files.writeString(file, WildResetConfig.GSON.toJson(ENTRIES.values()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to save evacuated_chests.json", e);
        }
    }

    public static void load(Path cacheDir) {
        Path file = cacheDir.resolve("evacuated_chests.json");
        if (!Files.exists(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Type listType = new TypeToken<java.util.List<Entry>>() {}.getType();
            java.util.List<Entry> list = WildResetConfig.GSON.fromJson(raw, listType);
            ENTRIES.clear();
            if (list != null) {
                for (Entry e : list) {
                    if (e != null) {
                        ENTRIES.put(e.key(), e);
                        if (e.season() > currentSeason) {
                            currentSeason = e.season();
                        }
                    }
                }
            }
        } catch (Exception e) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to load evacuated_chests.json", e);
        }
    }

    public static Map<String, Entry> allEntries() {
        return ENTRIES;
    }
}
