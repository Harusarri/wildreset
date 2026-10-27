package kr.harusarri.wildreset;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main Fabric ModInitializer for WildReset (Zero-Downtime / No-Limbo Wilderness Chunk Reset).
 */
public final class WildResetMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("wildreset");
    private static int cmdPollTickCounter = 0;
    private static ChunkPos testVillageCenterChunk = null;

    @Override
    public void onInitialize() {
        WildResetConfig.load();
        StandaloneChunkRegistry.load();

        // 1. Server Lifecycle Hooks
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MiniText.setGlobalLookup(server.registryAccess());
            ServerLevel overworld = server.overworld();
            Path cacheDir = CleanChunkCache.getCacheRootDir(overworld);
            EvacuatedChestRegistry.load(cacheDir);
            WildResetEngine.loadSeasonState(overworld, cacheDir);
            WildResetConfig cfg = WildResetConfig.get();
            LOGGER.info("[WildReset] Initialized. Center=({}, {}), Radius={} blocks, Halo=+{} chunks, Quarantine=+{} chunks, ActiveSeason={}",
                cfg.spawnCenterX, cfg.spawnCenterZ, cfg.cuboidRadiusBlocks, cfg.protectedBufferHaloChunks,
                cfg.quarantineOuterBufferChunks, WildResetEngine.getActiveResetSeason());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WildResetEngine.abortReset();
            Path cacheDir = CleanChunkCache.getCacheRootDir(server.overworld());
            EvacuatedChestRegistry.save(cacheDir);
            WildResetEngine.saveSeasonState(cacheDir);
            CleanChunkCache.close();
        });

        // 2. Main-Thread Tick Engine Hook + Dev Console Command File Watcher
        ServerTickEvents.START_SERVER_TICK.register(WildResetEngine::onStartServerTick);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WildResetEngine.onEndServerTick(server);
            if (++cmdPollTickCounter >= 20) {
                cmdPollTickCounter = 0;
                pollDevCommandFile(server);
            }
        });

        // 2b. Full-Radius Season On-Load Chunk Reset & Disk Entity Sweep Hooks
        ServerChunkEvents.CHUNK_LOAD.register(WildResetEngine::onChunkLoad);
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register(WildResetEngine::onFullChunkStatusChange);
        ServerChunkEvents.CHUNK_UNLOAD.register(WildResetEngine::onChunkUnload);
        ServerEntityEvents.ALLOW_LOAD.register(WildResetEngine::onAllowEntityLoad);
        ServerEntityEvents.ENTITY_LOAD.register(WildResetEngine::onEntityLoaded);

        // 3. 3-Day Lazy Timestamp Evacuated Chest Protection (Open, Attack & Break)
        UseBlockCallback.EVENT.register(EvacuatedChestRegistry::onUseBlock);
        AttackBlockCallback.EVENT.register(EvacuatedChestRegistry::onAttackBlock);
        PlayerBlockBreakEvents.BEFORE.register(EvacuatedChestRegistry::onBeforeBreakBlock);

        // 4. Register /wildreset Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("wildreset")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("reload")
                        .executes(ctx -> {
                            WildResetConfig cfg = WildResetConfig.load();
                            StandaloneChunkRegistry.load();
                            WildResetEngine.invalidateProtectionIndex();
                            LOGGER.info("[WildReset] Config reloaded via /wildreset reload (center=({}, {}), radius={}, lang={})",
                                cfg.spawnCenterX, cfg.spawnCenterZ, cfg.cuboidRadiusBlocks, cfg.language);
                            String tpl = WildResetI18n.resolveSourceTemplate(
                                ctx.getSource(),
                                "cmd.reload.success",
                                cfg.messages.reloadSuccessChat,
                                WildResetI18n.DEFAULT_KO_MESSAGES.reloadSuccessChat
                            );
                            MiniText.sendCommandFeedback(
                                ctx.getSource(),
                                tpl,
                                Map.of(
                                    "x", String.valueOf(cfg.spawnCenterX),
                                    "z", String.valueOf(cfg.spawnCenterZ),
                                    "radius", String.valueOf(cfg.cuboidRadiusBlocks),
                                    "buffer", String.valueOf(cfg.quarantineOuterBufferChunks)
                                )
                            );
                            return 1;
                        })
                    )
                    .then(Commands.literal("lang")
                        .executes(ctx -> {
                            String mode = WildResetI18n.getMode();
                            String resolved = WildResetI18n.resolveLang(ctx.getSource());
                            MiniText.sendCommandFeedback(
                                ctx.getSource(),
                                WildResetI18n.raw(resolved, "cmd.lang.current"),
                                Map.of("mode", mode, "resolved", resolved)
                            );
                            return 1;
                        })
                        .then(Commands.literal("auto").executes(ctx -> setLanguageCommand(ctx.getSource(), "auto")))
                        .then(Commands.literal("en_us").executes(ctx -> setLanguageCommand(ctx.getSource(), "en_us")))
                        .then(Commands.literal("ko_kr").executes(ctx -> setLanguageCommand(ctx.getSource(), "ko_kr")))
                    )
                    .then(Commands.literal("chunk")
                        .executes(ctx -> StandaloneChunkRegistry.sendChunkRegionsPage(ctx.getSource(), 1))
                        .then(Commands.literal("list")
                            .executes(ctx -> StandaloneChunkRegistry.sendChunkRegionsPage(ctx.getSource(), 1))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> StandaloneChunkRegistry.sendChunkRegionsPage(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "page")
                                ))
                            )
                        )
                        .then(Commands.literal("toggle")
                            .executes(ctx -> StandaloneChunkRegistry.toggleCurrentChunk(ctx.getSource()))
                        )
                        .then(Commands.literal("add")
                            .executes(ctx -> StandaloneChunkRegistry.addCurrentChunk(ctx.getSource(), 0))
                            .then(Commands.argument("arg1", IntegerArgumentType.integer(-1875000, 1875000))
                                .executes(ctx -> StandaloneChunkRegistry.addCurrentChunk(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "arg1")
                                ))
                                .then(Commands.argument("arg2", IntegerArgumentType.integer(-1875000, 1875000))
                                    .executes(ctx -> StandaloneChunkRegistry.addChunkCoords(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "arg1"),
                                        IntegerArgumentType.getInteger(ctx, "arg2")
                                    ))
                                    .then(Commands.argument("toChunkX", IntegerArgumentType.integer(-1875000, 1875000))
                                        .then(Commands.argument("toChunkZ", IntegerArgumentType.integer(-1875000, 1875000))
                                            .executes(ctx -> StandaloneChunkRegistry.addChunkRangeCoords(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "arg1"),
                                                IntegerArgumentType.getInteger(ctx, "arg2"),
                                                IntegerArgumentType.getInteger(ctx, "toChunkX"),
                                                IntegerArgumentType.getInteger(ctx, "toChunkZ")
                                            ))
                                        )
                                    )
                                )
                            )
                        )
                        .then(Commands.literal("remove")
                            .executes(ctx -> StandaloneChunkRegistry.removeCurrentChunk(ctx.getSource()))
                            .then(Commands.argument("chunkX", IntegerArgumentType.integer(-1875000, 1875000))
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-1875000, 1875000))
                                    .executes(ctx -> StandaloneChunkRegistry.removeChunkCoords(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "chunkX"),
                                        IntegerArgumentType.getInteger(ctx, "chunkZ")
                                    ))
                                )
                            )
                        )
                        .then(Commands.literal("removeid")
                            .then(Commands.argument("dimension", StringArgumentType.string())
                                .then(Commands.argument("region", StringArgumentType.greedyString())
                                    .executes(ctx -> StandaloneChunkRegistry.removeChunkById(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "dimension"),
                                        StringArgumentType.getString(ctx, "region")
                                    ))
                                )
                            )
                        )
                        .then(Commands.literal("reset")
                            .executes(ctx -> StandaloneChunkRegistry.resetCurrentChunkCmd(ctx.getSource(), 0))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                .executes(ctx -> StandaloneChunkRegistry.resetCurrentChunkCmd(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "radius")
                                ))
                            )
                        )
                    )
                    .then(Commands.literal("status")
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerLevel overworld = server.overworld();
                            WildResetConfig cfg = WildResetConfig.get();
                            YawpProtectionIndex idx = YawpProtectionIndex.fromConfig(overworld);
                            String resolvedLang = WildResetI18n.resolveLang(ctx.getSource());
                            String tpl = WildResetI18n.raw(resolvedLang, "cmd.status.report");
                            Map<String, String> placeholders = new LinkedHashMap<>();
                            placeholders.put("x", String.valueOf(cfg.spawnCenterX));
                            placeholders.put("z", String.valueOf(cfg.spawnCenterZ));
                            placeholders.put("radius", String.valueOf(cfg.cuboidRadiusBlocks));
                            placeholders.put("keep_chunks", String.valueOf(idx.keepChunkCount()));
                            placeholders.put("halo", String.valueOf(cfg.protectedBufferHaloChunks));
                            placeholders.put("quarantine", String.valueOf(cfg.quarantineOuterBufferChunks));
                            placeholders.put("chests", String.valueOf(EvacuatedChestRegistry.allEntries().size()));
                            placeholders.put("season", String.valueOf(EvacuatedChestRegistry.currentSeason()));
                            placeholders.put("ore_max_y", String.valueOf(cfg.oreRandomizerMaxY));
                            placeholders.put("lapis_pct", String.format(java.util.Locale.ROOT, "%.1f", cfg.lapisToDiamondChance * 100.0));
                            placeholders.put("redstone_pct", String.format(java.util.Locale.ROOT, "%.1f", cfg.redstoneToDiamondChance * 100.0));
                            placeholders.put("lang_mode", WildResetI18n.getMode());
                            placeholders.put("lang_resolved", resolvedLang);
                            placeholders.put("engine", WildResetEngine.getStatusSummary());
                            LOGGER.info("[WildReset Status] Center=({}, {}), Radius={}, YAWP Keep+Halo={}, EvacuatedChests={}, Lang={}({}), Engine=[{}]",
                                cfg.spawnCenterX, cfg.spawnCenterZ, cfg.cuboidRadiusBlocks, idx.keepChunkCount(),
                                EvacuatedChestRegistry.allEntries().size(), WildResetI18n.getMode(), resolvedLang, WildResetEngine.getStatusSummary());
                            MiniText.sendCommandFeedback(ctx.getSource(), tpl, placeholders);
                            return 1;
                        })
                    )
                    .then(Commands.literal("setcenter")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int x = IntegerArgumentType.getInteger(ctx, "x");
                                    int z = IntegerArgumentType.getInteger(ctx, "z");
                                    WildResetConfig cfg = WildResetConfig.get();
                                    cfg.spawnCenterX = x;
                                    cfg.spawnCenterZ = z;
                                    WildResetConfig.save();
                                    WildResetEngine.invalidateProtectionIndex();
                                    String lang = WildResetI18n.resolveLang(ctx.getSource());
                                    MiniText.sendCommandFeedback(
                                        ctx.getSource(),
                                        WildResetI18n.raw(lang, "cmd.setcenter.success"),
                                        Map.of("x", String.valueOf(x), "z", String.valueOf(z))
                                    );
                                    return 1;
                                })
                            )
                        )
                    )
                    .then(Commands.literal("setradius")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(16, 50000))
                            .executes(ctx -> {
                                int r = IntegerArgumentType.getInteger(ctx, "blocks");
                                WildResetConfig cfg = WildResetConfig.get();
                                cfg.cuboidRadiusBlocks = r;
                                WildResetConfig.save();
                                WildResetEngine.invalidateProtectionIndex();
                                String lang = WildResetI18n.resolveLang(ctx.getSource());
                                MiniText.sendCommandFeedback(
                                    ctx.getSource(),
                                    WildResetI18n.raw(lang, "cmd.setradius.success"),
                                    Map.of("radius", String.valueOf(r))
                                );
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("cache")
                        .then(Commands.literal("clear")
                            .executes(ctx -> {
                                ServerLevel overworld = ctx.getSource().getServer().overworld();
                                int removed = CleanChunkCache.clearDiskCache(overworld);
                                String lang = WildResetI18n.resolveLang(ctx.getSource());
                                MiniText.sendCommandFeedback(
                                    ctx.getSource(),
                                    WildResetI18n.raw(lang, "cmd.cache.cleared"),
                                    Map.of("count", String.valueOf(removed))
                                );
                                return 1;
                            })
                        )
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> {
                                int radiusChunks = IntegerArgumentType.getInteger(ctx, "radiusChunks");
                                ServerLevel overworld = ctx.getSource().getServer().overworld();
                                WildResetConfig cfg = WildResetConfig.get();
                                int cached = CleanChunkCache.captureLoadedChunksAsCleanCache(
                                    overworld, cfg.spawnCenterX, cfg.spawnCenterZ, radiusChunks << 4
                                );
                                String lang = WildResetI18n.resolveLang(ctx.getSource());
                                MiniText.sendCommandFeedback(
                                    ctx.getSource(),
                                    WildResetI18n.raw(lang, "cmd.cache.success"),
                                    Map.of("count", String.valueOf(cached))
                                );
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("run")
                        .executes(ctx -> {
                            boolean ok = WildResetEngine.startReset(ctx.getSource().getServer(), 0);
                            if (!ok) {
                                String lang = WildResetI18n.resolveLang(ctx.getSource());
                                ctx.getSource().sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.run.failed")));
                                return 0;
                            }
                            return 1;
                        })
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 32))
                            .executes(ctx -> {
                                int radiusChunks = IntegerArgumentType.getInteger(ctx, "radiusChunks");
                                boolean ok = WildResetEngine.startReset(ctx.getSource().getServer(), radiusChunks);
                                if (!ok) {
                                    String lang = WildResetI18n.resolveLang(ctx.getSource());
                                    ctx.getSource().sendFailure(MiniText.parse(WildResetI18n.raw(lang, "cmd.run.failed")));
                                    return 0;
                                }
                                return 1;
                            })
                        )
                    )
                    .then(Commands.literal("test-setup")
                        .executes(ctx -> runAutomatedDevServerTestSetup(ctx.getSource().getServer(), ctx.getSource()))
                    )
                    .then(Commands.literal("test-verify")
                        .executes(ctx -> runAutomatedDevServerVerify(ctx.getSource().getServer(), ctx.getSource()))
                    )
                    .then(Commands.literal("test-village-setup")
                        .executes(ctx -> runAutomatedVillageTestSetup(ctx.getSource().getServer(), ctx.getSource()))
                    )
                    .then(Commands.literal("test-village-verify")
                        .executes(ctx -> runAutomatedVillageTestVerify(ctx.getSource().getServer(), ctx.getSource()))
                    )
                    .then(Commands.literal("test-chest-protection")
                        .executes(ctx -> runAutomatedChestProtectionVerify(ctx.getSource().getServer(), ctx.getSource()))
                    )
            );
        });
    }

    private static int setLanguageCommand(CommandSourceStack source, String mode) {
        WildResetI18n.setMode(mode);
        String resolved = WildResetI18n.resolveLang(source);
        LOGGER.info("[WildReset] Language mode updated via /wildreset lang: mode={}, resolved={}", mode, resolved);
        MiniText.sendCommandFeedback(
            source,
            WildResetI18n.raw(resolved, "cmd.lang.changed"),
            Map.of("mode", mode, "resolved", resolved)
        );
        return 1;
    }

    private static void pollDevCommandFile(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path[] candidates = new Path[] {
            worldRoot.getParent().resolve("wildreset_cmd.txt"),
            worldRoot.normalize().toAbsolutePath().getParent().resolve("wildreset_cmd.txt"),
            worldRoot.normalize().toAbsolutePath().getParent().resolve("wildreset_dev_cmd.txt")
        };
        for (Path cmdFile : candidates) {
            try {
                if (!Files.exists(cmdFile)) continue;
                List<String> lines = Files.readAllLines(cmdFile, StandardCharsets.UTF_8);
                Files.deleteIfExists(cmdFile);
                for (String line : lines) {
                    String cmd = line.replace("\uFEFF", "").trim();
                    if (!cmd.isEmpty() && !cmd.startsWith("#")) {
                        LOGGER.info("[WildReset] Executing command from {}: {}", cmdFile.getFileName(), cmd);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[WildReset] Failed to process {}", cmdFile, e);
            }
        }
    }

    private static int runAutomatedDevServerTestSetup(MinecraftServer server, CommandSourceStack source) {
        ServerLevel overworld = server.overworld();
        WildResetConfig cfg = WildResetConfig.get();
        int cx = cfg.spawnCenterX >> 4;
        int cz = cfg.spawnCenterZ >> 4;
        ChunkPos testChunk = new ChunkPos(cx, cz);

        // Force-load chunk so entities & block entities remain active even with 0 players connected
        overworld.getChunkSource().updateChunkForced(testChunk, true);
        var liveChunk = overworld.getChunk(cx, cz);

        // Clear any previous test entities/chests in testChunk before capturing clean baseline
        liveChunk.clearAllBlockEntities();
        AABB clearBox = new AABB(
            testChunk.getMinBlockX(), overworld.getMinY(), testChunk.getMinBlockZ(),
            testChunk.getMaxBlockX() + 1, overworld.getMinY() + overworld.getHeight(), testChunk.getMaxBlockZ() + 1
        );
        for (Entity e : overworld.getEntities((Entity) null, clearBox, ent -> !(ent instanceof net.minecraft.server.level.ServerPlayer))) {
            e.discard();
        }

        // 1. Capture clean baseline into cache before placing test structures
        CleanChunkCache.writeCachedCleanChunk(overworld, testChunk, CleanChunkCache.generateCleanChunkFromSeed(overworld, testChunk));

        int baseX = testChunk.getMinBlockX() + 8;
        int baseZ = testChunk.getMinBlockZ() + 8;
        int undergroundY = 20;

        // 2. Place a Double Chest underground at Y = 20 containing Diamonds, Emeralds, and Golden Apples
        BlockPos leftChestPos = new BlockPos(baseX, undergroundY, baseZ);
        BlockPos rightChestPos = new BlockPos(baseX + 1, undergroundY, baseZ);

        BlockState leftState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState rightState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.RIGHT);

        overworld.setBlock(leftChestPos, leftState, 3);
        overworld.setBlock(rightChestPos, rightState, 3);

        String testOwnerUuid = "00000000-0000-0000-0000-000000000001";
        String testOwnerName = "TestNewbieA";
        if (overworld.getBlockEntity(leftChestPos) instanceof ChestBlockEntity leftBe) {
            leftBe.setItem(0, new ItemStack(Items.DIAMOND, 32));
            leftBe.setItem(1, new ItemStack(Items.GOLDEN_APPLE, 16));
            stampOwnerOnBe(leftBe, testOwnerUuid, testOwnerName);
        }
        if (overworld.getBlockEntity(rightChestPos) instanceof ChestBlockEntity rightBe) {
            rightBe.setItem(0, new ItemStack(Items.EMERALD, 48));
            rightBe.setItem(1, new ItemStack(Items.NETHERITE_INGOT, 4));
            stampOwnerOnBe(rightBe, testOwnerUuid, testOwnerName);
        }

        // 3. Spawn a tamed Wolf and a tamed Cat underground at Y = 20
        EntityType<?> wolfType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("wolf"));
        if (wolfType.create(overworld, EntitySpawnReason.COMMAND) instanceof Wolf wolf) {
            wolf.setPos(baseX + 0.5, undergroundY + 1, baseZ + 0.5);
            wolf.setTame(true, true);
            wolf.setCustomName(MiniText.parse("<aqua>TestTamedWolf</aqua>"));
            overworld.addFreshEntity(wolf);
        }

        EntityType<?> catType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("cat"));
        if (catType.create(overworld, EntitySpawnReason.COMMAND) instanceof Cat cat) {
            cat.setPos(baseX + 1.5, undergroundY + 1, baseZ + 0.5);
            cat.setTame(true, true);
            cat.setCustomName(MiniText.parse("<light_purple>TestTamedCat</light_purple>"));
            overworld.addFreshEntity(cat);
        }

        // 4. Place a Polymer virtual-display block (farmersdelight:cutting_board) to test Polymer display cleanup
        Block polymerBlock = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("farmersdelight", "cutting_board"));
        if (polymerBlock != Blocks.AIR) {
            overworld.setBlock(new BlockPos(baseX + 3, 70, baseZ), polymerBlock.defaultBlockState(), 3);
        }

        // 5. Place a non-opaque light-emitting block (Torch, light=14) underground in solid deepslate at (baseX + 5, -20, baseZ)
        BlockPos glowstonePos = new BlockPos(baseX + 5, -20, baseZ);
        overworld.setBlock(glowstonePos.below(), Blocks.DEEPSLATE.defaultBlockState(), 3);
        overworld.setBlock(glowstonePos, Blocks.TORCH.defaultBlockState(), 3);
        if (overworld.getChunkSource().getLightEngine() instanceof ca.spottedleaf.starlight.common.light.StarLightLightingProvider slp
                && slp.scalablelux$getLightEngine() != null) {
            slp.scalablelux$getLightEngine().relightChunks(java.util.Set.of(testChunk), p -> {}, null);
        }
        int blockLightBefore = overworld.getBrightness(LightLayer.BLOCK, glowstonePos);

        int holdersBefore = (liveChunk instanceof eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder hah)
            ? hah.polymerVE$getHolders().size() : -1;

        LOGGER.info("[WildReset Test Setup] Placed Double Chest + Tamed Wolf + Tamed Cat + Polymer Block + Glowstone(light={}) at ({}, {}, {}) in chunk ({}, {}), polymerHoldersBefore={}",
            blockLightBefore, baseX, undergroundY, baseZ, cx, cz, holdersBefore);
        MiniText.sendCommandFeedback(
            source,
            "<green><bold>[WildReset Test Setup]</bold> 청크 (" + cx + ", " + cz + ") 지하 Y=" + undergroundY
            + "에 더블 상자 1세트 + 개/고양이 + 폴리머 블록(holders=" + holdersBefore + ") + 발광석(light=" + blockLightBefore + ") 배치 완료!</green>",
            Map.of()
        );
        return 1;
    }

    private static void stampOwnerOnBe(BlockEntity be, String ownerUuid, String ownerName) {
        CompoundTag custom = be.components().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        custom.putString(EvacuatedChestRegistry.NBT_OWNER_UUID, ownerUuid);
        custom.putString(EvacuatedChestRegistry.NBT_OWNER_NAME, ownerName);
        DataComponentMap.Builder builder = DataComponentMap.builder().addAll(be.components());
        builder.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        be.setComponents(builder.build());
        be.setChanged();
    }

    private static int runAutomatedDevServerVerify(MinecraftServer server, CommandSourceStack source) {
        ServerLevel overworld = server.overworld();
        WildResetConfig cfg = WildResetConfig.get();
        int cx = cfg.spawnCenterX >> 4;
        int cz = cfg.spawnCenterZ >> 4;
        ChunkPos testChunk = new ChunkPos(cx, cz);
        var chunk = overworld.getChunk(cx, cz);

        int singleChestCount = 0;
        int doubleChestCount = 0;
        StringBuilder chestDetails = new StringBuilder();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState st = chunk.getBlockState(pos);
            if (st.getBlock() instanceof ChestBlock) {
                ChestType type = st.getValue(ChestBlock.TYPE);
                if (type == ChestType.SINGLE) singleChestCount++;
                else doubleChestCount++;
                BlockState floor = overworld.getBlockState(pos.below());
                BlockState above = overworld.getBlockState(pos.above());
                boolean floorValid = !floor.isAir() && !floor.is(BlockTags.LEAVES) && !floor.is(BlockTags.LOGS);
                EvacuatedChestRegistry.Entry reg = EvacuatedChestRegistry.getEvacuatedEntry(overworld, pos);
                String rem = reg != null ? EvacuatedChestRegistry.formatRemainingTime(reg.unlockAtMs() - System.currentTimeMillis()) : "none";
                int itemCount = 0;
                if (entry.getValue() instanceof ChestBlockEntity cbe) {
                    for (int i = 0; i < cbe.getContainerSize(); i++) {
                        if (!cbe.getItem(i).isEmpty()) itemCount += cbe.getItem(i).getCount();
                    }
                }
                chestDetails.append(String.format(
                    "  - Chest at (%d, %d, %d) [type=%s, items=%d, floor=%s (trueGround=%s), headroom=%s, owner=%s, lock=%s, season=%d]\n",
                    pos.getX(), pos.getY(), pos.getZ(), type.getSerializedName(), itemCount,
                    floor.getBlock().getDescriptionId(), floorValid, above.getBlock().getDescriptionId(),
                    reg != null ? reg.ownerName() : "N/A", rem, reg != null ? reg.season() : -1
                ));
            }
        }

        AABB box = new AABB(
            testChunk.getMinBlockX(), overworld.getMinY(), testChunk.getMinBlockZ(),
            testChunk.getMaxBlockX() + 1, overworld.getMinY() + overworld.getHeight(), testChunk.getMaxBlockZ() + 1
        );
        List<Entity> pets = overworld.getEntities((Entity) null, box,
            e -> (e instanceof Wolf w && w.isTame()) || (e instanceof Cat c && c.isTame()));
        StringBuilder petDetails = new StringBuilder();
        for (Entity pet : pets) {
            BlockPos ppos = pet.blockPosition();
            BlockState floor = overworld.getBlockState(ppos.below());
            petDetails.append(String.format("  - %s at (%d, %d, %d) [floor=%s, sitting=%s]\n",
                pet.getType().toShortString(), ppos.getX(), ppos.getY(), ppos.getZ(),
                floor.getBlock().getDescriptionId(), ((TamableAnimal) pet).isInSittingPose()));
        }

        int polymerHoldersAfter = (chunk instanceof eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder hah)
            ? hah.polymerVE$getHolders().size() : -1;

        // Verify Torch block light at (baseX + 5, -20, baseZ) was cleared to 0 by ScalableLux synchronous relight
        BlockPos glowstonePos = new BlockPos(testChunk.getMinBlockX() + 13, -20, testChunk.getMinBlockZ() + 8);
        int blockLightAfter = overworld.getBrightness(LightLayer.BLOCK, glowstonePos);

        // Count natural vegetation (logs, leaves, grass/flowers/ferns/plants) in the 5x5 reset chunks around testChunk
        int logBlocks = 0;
        int leafBlocks = 0;
        int plantBlocks = 0;
        int crossBorderLeafPairs = 0;
        int borderTreesTotal = 0;
        int borderTreesIntact = 0;
        BlockPos.MutableBlockPos vegPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborVegPos = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                var rChunk = overworld.getChunk(cx + dx, cz + dz);
                int minX = rChunk.getPos().getMinBlockX();
                int minZ = rChunk.getPos().getMinBlockZ();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        boolean hasLogInColumn = false;
                        int canopyY = -1;
                        for (int y = 55; y <= 140; y++) {
                            vegPos.set(minX + lx, y, minZ + lz);
                            BlockState bs = rChunk.getBlockState(vegPos);
                            if (bs.isAir()) continue;
                            if (bs.is(BlockTags.LOGS)) {
                                logBlocks++;
                                hasLogInColumn = true;
                            } else if (bs.is(BlockTags.LEAVES)) {
                                leafBlocks++;
                                canopyY = y;
                                if (lx == 15 && dx < 2) {
                                    neighborVegPos.set(minX + 16, y, minZ + lz);
                                    if (overworld.getBlockState(neighborVegPos).is(BlockTags.LEAVES)) {
                                        crossBorderLeafPairs++;
                                    }
                                }
                                if (lz == 15 && dz < 2) {
                                    neighborVegPos.set(minX + lx, y, minZ + 16);
                                    if (overworld.getBlockState(neighborVegPos).is(BlockTags.LEAVES)) {
                                        crossBorderLeafPairs++;
                                    }
                                }
                            } else if (bs.is(Blocks.SHORT_GRASS)
                                || bs.is(Blocks.TALL_GRASS)
                                || bs.is(Blocks.FERN)
                                || bs.is(Blocks.LARGE_FERN)
                                || bs.is(BlockTags.FLOWERS)
                                || bs.is(Blocks.SUGAR_CANE)
                                || bs.is(Blocks.PUMPKIN)
                                || bs.is(Blocks.DANDELION)
                                || bs.is(Blocks.POPPY)
                                || bs.is(Blocks.SEAGRASS)
                                || bs.is(Blocks.TALL_SEAGRASS)
                                || bs.is(Blocks.KELP)
                                || bs.is(Blocks.KELP_PLANT)) {
                                plantBlocks++;
                            }
                        }
                        if (hasLogInColumn && canopyY > 0) {
                            int borderTargetX = (lx <= 1 && dx > -2) ? (minX - 1) : ((lx >= 14 && dx < 2) ? (minX + 16) : Integer.MIN_VALUE);
                            if (borderTargetX != Integer.MIN_VALUE) {
                                borderTreesTotal++;
                                boolean foundAcross = false;
                                for (int sy = canopyY - 3; sy <= canopyY + 2 && !foundAcross; sy++) {
                                    for (int sz = -1; sz <= 1 && !foundAcross; sz++) {
                                        neighborVegPos.set(borderTargetX, sy, minZ + lz + sz);
                                        if (overworld.getBlockState(neighborVegPos).is(BlockTags.LEAVES)) {
                                            foundAcross = true;
                                        }
                                    }
                                }
                                if (foundAcross) borderTreesIntact++;
                            }
                            int borderTargetZ = (lz <= 1 && dz > -2) ? (minZ - 1) : ((lz >= 14 && dz < 2) ? (minZ + 16) : Integer.MIN_VALUE);
                            if (borderTargetZ != Integer.MIN_VALUE) {
                                borderTreesTotal++;
                                boolean foundAcross = false;
                                for (int sy = canopyY - 3; sy <= canopyY + 2 && !foundAcross; sy++) {
                                    for (int sx = -1; sx <= 1 && !foundAcross; sx++) {
                                        neighborVegPos.set(minX + lx + sx, sy, borderTargetZ);
                                        if (overworld.getBlockState(neighborVegPos).is(BlockTags.LEAVES)) {
                                            foundAcross = true;
                                        }
                                    }
                                }
                                if (foundAcross) borderTreesIntact++;
                            }
                        }
                    }
                }
            }
        }

        // Test breaking and restoring a random-ticking GRASS_BLOCK in the reset chunk (Lithium RandomTickingSectionDataHelper test)
        boolean breakRandomTickBlockOk = false;
        try {
            BlockPos testBreakPos = new BlockPos(testChunk.getMinBlockX() + 2, 62, testChunk.getMinBlockZ() + 2);
            for (int y = 120; y >= 50; y--) {
                BlockPos candidate = new BlockPos(testChunk.getMinBlockX() + 2, y, testChunk.getMinBlockZ() + 2);
                if (overworld.getBlockState(candidate).isRandomlyTicking()) {
                    testBreakPos = candidate;
                    break;
                }
            }
            BlockState orig = overworld.getBlockState(testBreakPos);
            overworld.destroyBlock(testBreakPos, false);
            overworld.setBlock(testBreakPos, orig, 3);
            breakRandomTickBlockOk = true;
        } catch (Throwable t) {
            LOGGER.error("[WildReset Verify] Failed to break random-ticking block in reset chunk!", t);
        }

        LOGGER.info("[WildReset Verify] Chunk ({}, {}): singleChests={}, doubleChests={}, tamedPets={}, polymerHoldersAfter={}, glowstoneLightAfter={}, breakRandomTickBlockOk={}, lazyOnLoadResets={}, vegetation5x5=(logs={}, leaves={}, plants={}, crossBorderLeafPairs={}, borderTreesIntact={}/{})\nChests:\n{}Pets:\n{}",
            cx, cz, singleChestCount, doubleChestCount, pets.size(), polymerHoldersAfter, blockLightAfter,
            breakRandomTickBlockOk, WildResetEngine.getLazyResetCount(), logBlocks, leafBlocks, plantBlocks,
            crossBorderLeafPairs, borderTreesIntact, borderTreesTotal, chestDetails, petDetails);
        MiniText.sendCommandFeedback(
            source,
            "<gold><bold>[WildReset Verify]</bold></gold> <white>단일 상자=" + singleChestCount
            + "개 (더블상자=" + doubleChestCount + "개), 지상 이전 펫=" + pets.size()
            + "마리, 폴리머 잔여 홀더=" + polymerHoldersAfter + "개, 발광석 제거 후 잔여 밝기=" + blockLightAfter
            + ", 블록 파괴=" + breakRandomTickBlockOk + ", 지연 로드 초기화=" + WildResetEngine.getLazyResetCount()
            + "개, 식생(원목=" + logBlocks + ", 나뭇잎=" + leafBlocks + ", 풀/꽃=" + plantBlocks
            + ", 청크경계잎연속=" + crossBorderLeafPairs + ", 경계나무보전=" + borderTreesIntact + "/" + borderTreesTotal + ") 검증 완료!</white>",
            Map.of()
        );
        return 1;
    }

    private static int runAutomatedVillageTestSetup(MinecraftServer server, CommandSourceStack source) {
        ServerLevel overworld = server.overworld();
        WildResetConfig cfg = WildResetConfig.get();
        BlockPos searchOrigin = new BlockPos(cfg.spawnCenterX + 400, 64, cfg.spawnCenterZ + 400);
        BlockPos villagePos = overworld.findNearestMapStructure(StructureTags.VILLAGE, searchOrigin, 100, false);
        if (villagePos == null) {
            LOGGER.warn("[WildReset Village Test] Could not locate any #minecraft:village near {}", searchOrigin);
            source.sendFailure(MiniText.parse("<red>[WildReset Village Test] 주변에서 마을 구조물을 찾지 못했습니다.</red>"));
            return 0;
        }

        ChunkPos centerChunk = new ChunkPos(villagePos.getX() >> 4, villagePos.getZ() >> 4);
        testVillageCenterChunk = centerChunk;

        List<ChunkPos> villageChunks = new ArrayList<>();
        int blocksRuined = 0;
        int entitiesDiscarded = 0;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos cpos = new ChunkPos(centerChunk.x() + dx, centerChunk.z() + dz);
                villageChunks.add(cpos);
                overworld.getChunkSource().updateChunkForced(cpos, true);
                var rChunk = overworld.getChunk(cpos.x(), cpos.z());

                // Clear all structure block entities and wipe surface/building blocks so the village is completely ruined before reset
                rChunk.clearAllBlockEntities();
                int minX = cpos.getMinBlockX();
                int minZ = cpos.getMinBlockZ();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = 58; y <= 130; y++) {
                            mpos.set(minX + lx, y, minZ + lz);
                            BlockState bs = rChunk.getBlockState(mpos);
                            if (bs.isAir() || bs.is(Blocks.STONE) || bs.is(Blocks.DIRT) || bs.is(Blocks.GRASS_BLOCK)
                                || bs.is(Blocks.SAND) || bs.is(Blocks.SANDSTONE) || bs.is(Blocks.WATER)) {
                                continue;
                            }
                            rChunk.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                            blocksRuined++;
                        }
                    }
                }

                AABB box = new AABB(
                    cpos.getMinBlockX(), overworld.getMinY(), cpos.getMinBlockZ(),
                    cpos.getMaxBlockX() + 1, overworld.getMinY() + overworld.getHeight(), cpos.getMaxBlockZ() + 1
                );
                for (Entity e : overworld.getEntities((Entity) null, box, ent -> !(ent instanceof net.minecraft.server.level.ServerPlayer))) {
                    e.discard();
                    entitiesDiscarded++;
                }
            }
        }

        boolean started = WildResetEngine.startTargetedChunkReset(server, villageChunks);
        LOGGER.info("[WildReset Village Test Setup] Located village at {} (chunk {}, {}), force-loaded 5x5 chunks ({}), ruined {} structure/surface blocks, discarded {} entities, startedTargetedReset={}",
            villagePos, centerChunk.x(), centerChunk.z(), villageChunks.size(), blocksRuined, entitiesDiscarded, started);
        MiniText.sendCommandFeedback(
            source,
            "<aqua><bold>[WildReset Village Test Setup]</bold> 마을 위치 " + villagePos.toShortString()
            + " (청크 " + centerChunk.x() + ", " + centerChunk.z() + ") 5x5 청크 철거(블록 " + blocksRuined
            + "개, 엔티티 " + entitiesDiscarded + "마리 제거) 후 리셋 시작=" + started + "</aqua>",
            Map.of()
        );
        return started ? 1 : 0;
    }

    private static int runAutomatedVillageTestVerify(MinecraftServer server, CommandSourceStack source) {
        ServerLevel overworld = server.overworld();
        if (testVillageCenterChunk == null) {
            source.sendFailure(MiniText.parse("<red>[WildReset Village Verify] 먼저 /wildreset test-village-setup 을 실행하세요.</red>"));
            return 0;
        }

        ChunkPos centerChunk = testVillageCenterChunk;
        int bellBlocks = 0;
        int bedBlocks = 0;
        int pathBlocks = 0;
        int chestBlocks = 0;
        int workstationBlocks = 0;
        int buildingBlocks = 0;
        int leafBlocks = 0;
        int crossBorderLeafPairs = 0;
        int floatingAttachments = 0;
        int geodeBlocks = 0;
        int crossBorderGeodePairs = 0;
        int caveFeatureBlocks = 0;
        int grassInBuildings = 0;
        int terrainFusedInGeodes = 0;
        int flowingCaveFluids = 0;
        int totalBlockEntities = 0;
        Map<String, Integer> blockEntityCounts = new LinkedHashMap<>();

        int villagers = 0;
        int ironGolems = 0;
        int cats = 0;
        int biomeAnimals = 0;
        int totalEntities = 0;
        Map<String, Integer> entityCounts = new LinkedHashMap<>();

        int minY = overworld.getMinY();
        int maxY = minY + overworld.getHeight();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos cpos = new ChunkPos(centerChunk.x() + dx, centerChunk.z() + dz);
                var rChunk = overworld.getChunk(cpos.x(), cpos.z());

                for (BlockEntity be : rChunk.getBlockEntities().values()) {
                    totalBlockEntities++;
                    String beId = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
                    blockEntityCounts.merge(beId, 1, Integer::sum);
                }

                int minX = cpos.getMinBlockX();
                int minZ = cpos.getMinBlockZ();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = minY; y < maxY; y++) {
                            mpos.set(minX + lx, y, minZ + lz);
                            BlockState bs = rChunk.getBlockState(mpos);
                            if (bs.isAir()) continue;
                            if (y < 55 && !bs.getFluidState().isEmpty() && !bs.getFluidState().isSource()) {
                                flowingCaveFluids++;
                            }
                            if ((bs.is(Blocks.SHORT_GRASS) || bs.is(Blocks.TALL_GRASS)
                                || bs.is(Blocks.FERN) || bs.is(Blocks.LARGE_FERN))
                                && CleanChunkCache.isInsideRigidStructureInterior(overworld, cpos, mpos)) {
                                grassInBuildings++;
                            }
                            if (y < 50 && (bs.is(Blocks.TUFF) || bs.is(Blocks.DEEPSLATE) || bs.is(Blocks.STONE)
                                || bs.is(Blocks.GRANITE) || bs.is(Blocks.DIORITE) || bs.is(Blocks.ANDESITE)
                                || bs.is(Blocks.DIRT) || bs.is(Blocks.GRAVEL))) {
                                boolean amyNegX = false, amyPosX = false, amyNegZ = false, amyPosZ = false, amyNegY = false, amyPosY = false;
                                for (int d = 1; d <= 5; d++) {
                                    BlockState bx1 = overworld.getBlockState(npos.set(minX + lx - d, y, minZ + lz));
                                    if (bx1.is(Blocks.AMETHYST_BLOCK) || bx1.is(Blocks.BUDDING_AMETHYST) || bx1.is(Blocks.AMETHYST_CLUSTER)) amyNegX = true;
                                    BlockState bx2 = overworld.getBlockState(npos.set(minX + lx + d, y, minZ + lz));
                                    if (bx2.is(Blocks.AMETHYST_BLOCK) || bx2.is(Blocks.BUDDING_AMETHYST) || bx2.is(Blocks.AMETHYST_CLUSTER)) amyPosX = true;
                                    BlockState bz1 = overworld.getBlockState(npos.set(minX + lx, y, minZ + lz - d));
                                    if (bz1.is(Blocks.AMETHYST_BLOCK) || bz1.is(Blocks.BUDDING_AMETHYST) || bz1.is(Blocks.AMETHYST_CLUSTER)) amyNegZ = true;
                                    BlockState bz2 = overworld.getBlockState(npos.set(minX + lx, y, minZ + lz + d));
                                    if (bz2.is(Blocks.AMETHYST_BLOCK) || bz2.is(Blocks.BUDDING_AMETHYST) || bz2.is(Blocks.AMETHYST_CLUSTER)) amyPosZ = true;
                                    BlockState by1 = overworld.getBlockState(npos.set(minX + lx, y - d, minZ + lz));
                                    if (by1.is(Blocks.AMETHYST_BLOCK) || by1.is(Blocks.BUDDING_AMETHYST) || by1.is(Blocks.AMETHYST_CLUSTER)) amyNegY = true;
                                    BlockState by2 = overworld.getBlockState(npos.set(minX + lx, y + d, minZ + lz));
                                    if (by2.is(Blocks.AMETHYST_BLOCK) || by2.is(Blocks.BUDDING_AMETHYST) || by2.is(Blocks.AMETHYST_CLUSTER)) amyPosY = true;
                                }
                                if (amyNegX && amyPosX && amyNegZ && amyPosZ && amyNegY && amyPosY) {
                                    terrainFusedInGeodes++;
                                    LOGGER.warn("[WildReset Village Verify] Terrain fused in geode at {}: state={}, +X={}, -X={}, +Y={}, -Y={}, +Z={}, -Z={}",
                                        mpos, bs,
                                        overworld.getBlockState(mpos.east()),
                                        overworld.getBlockState(mpos.west()),
                                        overworld.getBlockState(mpos.above()),
                                        overworld.getBlockState(mpos.below()),
                                        overworld.getBlockState(mpos.south()),
                                        overworld.getBlockState(mpos.north()));
                                }
                            }
                            if (bs.is(Blocks.TORCH) || bs.is(Blocks.WALL_TORCH) || bs.is(Blocks.VINE)
                                || bs.is(Blocks.GLOW_LICHEN) || bs.is(Blocks.MOSS_CARPET)
                                || bs.is(Blocks.AMETHYST_CLUSTER) || bs.is(Blocks.LARGE_AMETHYST_BUD)
                                || bs.is(Blocks.MEDIUM_AMETHYST_BUD) || bs.is(Blocks.SMALL_AMETHYST_BUD)
                                || bs.is(Blocks.POINTED_DRIPSTONE) || bs.is(Blocks.CAVE_VINES)
                                || bs.is(Blocks.CAVE_VINES_PLANT) || bs.is(Blocks.HANGING_ROOTS)
                                || bs.is(Blocks.SPORE_BLOSSOM)) {
                                caveFeatureBlocks++;
                                if (!bs.canSurvive(overworld, mpos)) {
                                    floatingAttachments++;
                                }
                            }
                            if (bs.is(Blocks.AMETHYST_BLOCK) || bs.is(Blocks.BUDDING_AMETHYST)
                                || bs.is(Blocks.CALCITE) || bs.is(Blocks.SMOOTH_BASALT)
                                || bs.is(Blocks.AMETHYST_CLUSTER)) {
                                geodeBlocks++;
                                if (lx == 15 && dx < 2) {
                                    npos.set(minX + 16, y, minZ + lz);
                                    BlockState nb = overworld.getBlockState(npos);
                                    if (nb.is(Blocks.AMETHYST_BLOCK) || nb.is(Blocks.BUDDING_AMETHYST)
                                        || nb.is(Blocks.CALCITE) || nb.is(Blocks.SMOOTH_BASALT)
                                        || nb.is(Blocks.AMETHYST_CLUSTER)) {
                                        crossBorderGeodePairs++;
                                    }
                                }
                                if (lz == 15 && dz < 2) {
                                    npos.set(minX + lx, y, minZ + 16);
                                    BlockState nb = overworld.getBlockState(npos);
                                    if (nb.is(Blocks.AMETHYST_BLOCK) || nb.is(Blocks.BUDDING_AMETHYST)
                                        || nb.is(Blocks.CALCITE) || nb.is(Blocks.SMOOTH_BASALT)
                                        || nb.is(Blocks.AMETHYST_CLUSTER)) {
                                        crossBorderGeodePairs++;
                                    }
                                }
                            }
                            if (y < 55 || y > 130) continue;
                            if (bs.is(BlockTags.LEAVES)) {
                                leafBlocks++;
                                if (lx == 15 && dx < 2) {
                                    npos.set(minX + 16, y, minZ + lz);
                                    if (overworld.getBlockState(npos).is(BlockTags.LEAVES)) {
                                        crossBorderLeafPairs++;
                                    }
                                }
                                if (lz == 15 && dz < 2) {
                                    npos.set(minX + lx, y, minZ + 16);
                                    if (overworld.getBlockState(npos).is(BlockTags.LEAVES)) {
                                        crossBorderLeafPairs++;
                                    }
                                }
                            } else if (bs.is(Blocks.BELL)) {
                                bellBlocks++;
                            } else if (bs.is(BlockTags.BEDS)) {
                                bedBlocks++;
                            } else if (bs.is(Blocks.DIRT_PATH)) {
                                pathBlocks++;
                            } else if (bs.is(Blocks.CHEST) || bs.is(Blocks.TRAPPED_CHEST) || bs.is(Blocks.BARREL)) {
                                chestBlocks++;
                            } else if (bs.is(Blocks.COMPOSTER) || bs.is(Blocks.SMOKER) || bs.is(Blocks.BLAST_FURNACE)
                                || bs.is(Blocks.BREWING_STAND) || bs.is(Blocks.LECTERN) || bs.is(Blocks.CARTOGRAPHY_TABLE)
                                || bs.is(Blocks.FLETCHING_TABLE) || bs.is(Blocks.SMITHING_TABLE) || bs.is(Blocks.LOOM)
                                || bs.is(Blocks.STONECUTTER) || bs.is(Blocks.CAULDRON) || bs.is(Blocks.GRINDSTONE)) {
                                workstationBlocks++;
                            } else if (bs.is(BlockTags.PLANKS) || bs.is(BlockTags.LOGS) || bs.is(Blocks.COBBLESTONE)
                                || bs.is(Blocks.MOSSY_COBBLESTONE) || bs.is(BlockTags.DOORS) || bs.is(BlockTags.FENCES)
                                || bs.is(Blocks.HAY_BLOCK) || bs.is(Blocks.GLASS_PANE) || bs.is(Blocks.TORCH)
                                || bs.is(Blocks.WALL_TORCH) || bs.is(Blocks.LANTERN)) {
                                buildingBlocks++;
                            }
                        }
                    }
                }

                AABB box = new AABB(
                    cpos.getMinBlockX(), overworld.getMinY(), cpos.getMinBlockZ(),
                    cpos.getMaxBlockX() + 1, overworld.getMinY() + overworld.getHeight(), cpos.getMaxBlockZ() + 1
                );
                for (Entity e : overworld.getEntities((Entity) null, box, ent -> !(ent instanceof net.minecraft.server.level.ServerPlayer) && ent.chunkPosition().equals(cpos))) {
                    totalEntities++;
                    String typeId = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                    entityCounts.merge(typeId, 1, Integer::sum);
                    if (typeId.equals("minecraft:villager")) {
                        villagers++;
                    } else if (typeId.equals("minecraft:iron_golem")) {
                        ironGolems++;
                    } else if (typeId.equals("minecraft:cat")) {
                        cats++;
                    } else if (e instanceof Animal) {
                        biomeAnimals++;
                    }
                }
            }
        }

        LOGGER.info("[WildReset Village Verify] 5x5 chunks around ({}, {}): bells={}, beds={}, paths={}, chests={}, workstations={}, buildingBlocks={}, grassInBuildings={}, leaves={}, crossBorderLeafPairs={}, caveFeatureBlocks={}, floatingAttachments={}, geodeBlocks={}, crossBorderGeodePairs={}, terrainFusedInGeodes={}, flowingCaveFluids={}, blockEntities={} ({}), totalEntities={}, villagers={}, ironGolems={}, cats={}, biomeAnimals={}, entityBreakdown={}",
            centerChunk.x(), centerChunk.z(), bellBlocks, bedBlocks, pathBlocks, chestBlocks, workstationBlocks,
            buildingBlocks, grassInBuildings, leafBlocks, crossBorderLeafPairs, caveFeatureBlocks, floatingAttachments, geodeBlocks, crossBorderGeodePairs,
            terrainFusedInGeodes, flowingCaveFluids,
            totalBlockEntities, blockEntityCounts, totalEntities, villagers, ironGolems, cats,
            biomeAnimals, entityCounts);
        MiniText.sendCommandFeedback(
            source,
            "<gold><bold>[WildReset Village Verify]</bold></gold> <white>마을 5x5 청크(" + centerChunk.x() + ", " + centerChunk.z()
            + "): 종=" + bellBlocks + ", 침대=" + bedBlocks + ", 마을길=" + pathBlocks + ", 상자=" + chestBlocks
            + ", 작업대=" + workstationBlocks + ", 건축블록=" + buildingBlocks + "(건물내잔디=" + grassInBuildings + "), 나뭇잎=" + leafBlocks
            + "(경계연속=" + crossBorderLeafPairs + "), 동굴생성물=" + caveFeatureBlocks
            + "(공중부유=" + floatingAttachments + ", 동굴유체흐름=" + flowingCaveFluids + "), 자수정정동블록=" + geodeBlocks
            + "(경계연속=" + crossBorderGeodePairs + ", 지형융합=" + terrainFusedInGeodes + "), 블록엔티티=" + totalBlockEntities
            + ", 주민=" + villagers + "명, 철골렘=" + ironGolems + "마리, 고양이=" + cats + "마리, 지역동물=" + biomeAnimals
            + "마리 (전체 엔티티=" + totalEntities + ")</white>",
            Map.of()
        );
        return 1;
    }

    private static int runAutomatedChestProtectionVerify(MinecraftServer server, CommandSourceStack source) {
        ServerLevel overworld = server.overworld();
        BlockPos basePos = new BlockPos(8, 120, 8);
        overworld.getChunkSource().updateChunkForced(new ChunkPos(0, 0), true);

        // 1. Create a locked evacuated chest at (8, 120, 8) with 32 Diamonds
        overworld.setBlock(basePos.below(), Blocks.STONE.defaultBlockState(), 3);
        BlockState singleNorth = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.SINGLE);
        overworld.setBlock(basePos, singleNorth, 3);

        long unlockAt = System.currentTimeMillis() + 72L * 3600_000L;
        if (overworld.getBlockEntity(basePos) instanceof ChestBlockEntity cbe) {
            cbe.clearContent();
            cbe.setItem(0, new ItemStack(Items.DIAMOND, 32));
        }
        EvacuatedChestRegistry.registerEvacuatedChest(new EvacuatedChestRegistry.Entry(
            overworld.dimension().identifier().toString(),
            basePos.getX(), basePos.getY(), basePos.getZ(),
            "00000000-0000-0000-0000-000000000099",
            "ProtectedOwner",
            unlockAt,
            EvacuatedChestRegistry.currentSeason()
        ));

        // 2. Test Double-Chest Merge Bypass: attempt to place a LEFT chest at (9, 120, 8) connected to (8, 120, 8)
        BlockPos adjPos = basePos.east();
        overworld.setBlock(
            adjPos,
            Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT),
            3
        );
        ChestType evacuatedChestTypeAfterAdj = overworld.getBlockState(basePos).getValue(ChestBlock.TYPE);
        boolean doubleChestBlocked = (evacuatedChestTypeAfterAdj == ChestType.SINGLE);
        overworld.setBlock(adjPos, Blocks.AIR.defaultBlockState(), 3);

        // 3. Test Hopper Extraction & Insertion Bypass
        BlockPos belowPos = basePos.below();
        overworld.setBlock(belowPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), 3);
        int stolenByHopper = 0;
        if (overworld.getBlockEntity(belowPos) instanceof HopperBlockEntity hopperBe) {
            HopperBlockEntity.pushItemsTick(overworld, belowPos, overworld.getBlockState(belowPos), hopperBe);
            HopperBlockEntity.suckInItems(overworld, hopperBe);
            for (int i = 0; i < hopperBe.getContainerSize(); i++) {
                stolenByHopper += hopperBe.getItem(i).getCount();
            }
        }
        overworld.setBlock(belowPos, Blocks.STONE.defaultBlockState(), 3);

        // 4. Test Hopper Minecart Extraction Bypass
        int stolenByMinecartHopper = 0;
        if (EntityTypes.HOPPER_MINECART.create(overworld, EntitySpawnReason.COMMAND) instanceof MinecartHopper cart) {
            cart.setPos(basePos.getX() + 0.5, basePos.getY() - 0.5, basePos.getZ() + 0.5);
            overworld.addFreshEntity(cart);
            HopperBlockEntity.suckInItems(overworld, cart);
            for (int i = 0; i < cart.getContainerSize(); i++) {
                stolenByMinecartHopper += cart.getItem(i).getCount();
            }
            cart.discard();
        }

        // 5. Test Piston Push Bypass
        PistonStructureResolver pistonResolver = new PistonStructureResolver(overworld, basePos.west(), Direction.EAST, true);
        boolean pistonBlocked = !pistonResolver.resolve();

        // 6. Test Explosion Bypass
        overworld.explode(null, basePos.getX() + 1.5, basePos.getY() + 0.5, basePos.getZ() + 0.5, 4.0f, Level.ExplosionInteraction.TNT);
        boolean chestIntactAfterExplosion = overworld.getBlockState(basePos).is(Blocks.CHEST);
        int remainingDiamonds = 0;
        if (overworld.getBlockEntity(basePos) instanceof ChestBlockEntity cbeAfter) {
            remainingDiamonds = cbeAfter.getItem(0).getCount();
        }

        // Clean up test chest
        EvacuatedChestRegistry.removeEntry(overworld, basePos);
        overworld.setBlock(basePos, Blocks.AIR.defaultBlockState(), 3);
        overworld.setBlock(basePos.below(), Blocks.AIR.defaultBlockState(), 3);

        LOGGER.info("[WildReset Chest Protection Verify] doubleChestBlocked={} (type={}), stolenByHopper={}, stolenByMinecartHopper={}, pistonBlocked={}, chestIntactAfterExplosion={}, remainingDiamonds={}/32",
            doubleChestBlocked, evacuatedChestTypeAfterAdj, stolenByHopper, stolenByMinecartHopper,
            pistonBlocked, chestIntactAfterExplosion, remainingDiamonds);
        MiniText.sendCommandFeedback(
            source,
            "<green><bold>[WildReset Chest Protection Verify]</bold></green> <white>더블상자 병합 차단=" + doubleChestBlocked
            + ", 호퍼 탈취=" + stolenByHopper + "개, 호퍼카트 탈취=" + stolenByMinecartHopper
            + "개, 피스톤 이동 차단=" + pistonBlocked + ", 폭발 후 상자 보존=" + chestIntactAfterExplosion
            + " (잔여 다이아=" + remainingDiamonds + "/32개)</white>",
            Map.of()
        );
        return 1;
    }
}
