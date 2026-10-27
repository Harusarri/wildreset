package kr.harusarri.wildreset;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CleanChunkCache {
    public static final String NBT_WILDRESET_ENTITIES = "wildreset_entities";
    public static final String NBT_WILDRESET_SCHEMA = "wildreset_schema_version";
    public static final int CACHE_SCHEMA_VERSION = 10;

    private static RegionFileStorage cacheStorage;
    private static Path currentCacheDir;
    private static final Object STORAGE_LOCK = new Object();

    private static final Map<ResourceKey<Level>, Map<Long, Map<Structure, StructureStart>>> STRUCTURE_STARTS_CACHE =
        new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, CompletableFuture<BaseChunkData>>> BASE_TERRAIN_CACHE =
        new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, CompletableFuture<DecoratedChunkData>>> DECORATED_CHUNK_CACHE =
        new ConcurrentHashMap<>();
    private static final ThreadLocal<StructureManager> THREAD_ISOLATED_STRUCTURE_MANAGER = new ThreadLocal<>();

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static Method createNoiseChunkMethod;
    private static Method doFillMethod;
    private static Method buildSurfaceMethod;
    private static Method generateCarversMethod;
    private static boolean reflectionInitialized = false;

    private CleanChunkCache() {}

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            for (Method m : NoiseBasedChunkGenerator.class.getDeclaredMethods()) {
                if (m.getName().equals("createNoiseChunk") && m.getParameterCount() == 5) {
                    m.setAccessible(true);
                    createNoiseChunkMethod = m;
                } else if (m.getName().equals("doFill") && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    doFillMethod = m;
                } else if (m.getName().equals("buildSurface") && m.getParameterCount() == 6) {
                    m.setAccessible(true);
                    buildSurfaceMethod = m;
                } else if (m.getName().equals("generateCarvers") && m.getParameterCount() == 7) {
                    m.setAccessible(true);
                    generateCarversMethod = m;
                }
            }
        } catch (Throwable t) {
            WildResetMod.LOGGER.warn("[WildReset] Reflection setup for NoiseBasedChunkGenerator fallback", t);
        }
    }

    public static Path getCacheRootDir(ServerLevel level) {
        String dimFolder = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("wildreset_cache").resolve(dimFolder);
    }

    private static RegionFileStorage getStorage(ServerLevel level) throws IOException {
        synchronized (STORAGE_LOCK) {
            Path regionDir = getCacheRootDir(level).resolve("region_v8");
            if (cacheStorage == null || !regionDir.equals(currentCacheDir)) {
                if (cacheStorage != null) {
                    try {
                        cacheStorage.close();
                    } catch (Exception ignored) {}
                }
                Files.createDirectories(regionDir);
                RegionStorageInfo info = new RegionStorageInfo("wildreset_cache", level.dimension(), "chunk");
                try {
                    Constructor<RegionFileStorage> ctor = RegionFileStorage.class.getDeclaredConstructor(
                        RegionStorageInfo.class, Path.class, boolean.class
                    );
                    ctor.setAccessible(true);
                    cacheStorage = ctor.newInstance(info, regionDir, false);
                } catch (ReflectiveOperationException e) {
                    throw new IOException("Failed to instantiate RegionFileStorage", e);
                }
                currentCacheDir = regionDir;
            }
            return cacheStorage;
        }
    }

    public static void close() {
        synchronized (STORAGE_LOCK) {
            if (cacheStorage != null) {
                try {
                    cacheStorage.flush();
                    cacheStorage.close();
                } catch (Exception ignored) {}
                cacheStorage = null;
                currentCacheDir = null;
            }
        }
        STRUCTURE_STARTS_CACHE.clear();
        BASE_TERRAIN_CACHE.clear();
        DECORATED_CHUNK_CACHE.clear();
    }

    public static int clearDiskCache(ServerLevel level) {
        int deleted = 0;
        synchronized (STORAGE_LOCK) {
            close();
            Path regionDir = getCacheRootDir(level).resolve("region_v8");
            if (Files.isDirectory(regionDir)) {
                try (var stream = Files.list(regionDir)) {
                    for (Path p : stream.toList()) {
                        if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                            deleted++;
                        }
                    }
                } catch (IOException e) {
                    WildResetMod.LOGGER.warn("[WildReset] Failed to clear disk cache in {}", regionDir, e);
                }
            }
        }
        return deleted;
    }

    public static void clearTransientGenerationCaches() {
        STRUCTURE_STARTS_CACHE.clear();
        BASE_TERRAIN_CACHE.clear();
        DECORATED_CHUNK_CACHE.clear();
    }

    public static void trimTransientGenerationCachesIfNeeded() {
        for (Map<Long, Map<Structure, StructureStart>> m : STRUCTURE_STARTS_CACHE.values()) {
            if (m.size() > 256) {
                m.clear();
            }
        }
        for (ConcurrentHashMap<Long, CompletableFuture<BaseChunkData>> m : BASE_TERRAIN_CACHE.values()) {
            if (m.size() > 64) {
                m.clear();
            }
        }
        for (ConcurrentHashMap<Long, CompletableFuture<DecoratedChunkData>> m : DECORATED_CHUNK_CACHE.values()) {
            if (m.size() > 32) {
                m.clear();
            }
        }
    }

    public static StructureManager getThreadIsolatedStructureManager() {
        return THREAD_ISOLATED_STRUCTURE_MANAGER.get();
    }

    private static boolean isAnyStructureChunk(ChunkGeneratorStructureState generatorState, int cx, int cz) {
        for (Holder<StructureSet> holder : generatorState.possibleStructureSets()) {
            StructurePlacement placement = holder.value().placement();
            if (placement.isStructureChunk(generatorState, cx, cz)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Structure, StructureStart> getOrComputeStructureStarts(ServerLevel level, int cx, int cz) {
        Map<Long, Map<Structure, StructureStart>> dimMap = STRUCTURE_STARTS_CACHE.computeIfAbsent(
            level.dimension(), d -> new ConcurrentHashMap<>()
        );
        long key = ChunkPos.pack(cx, cz);
        Map<Structure, StructureStart> existing = dimMap.get(key);
        if (existing != null) {
            return existing;
        }
        return dimMap.computeIfAbsent(key, k -> {
            ChunkGeneratorStructureState generatorState = level.getChunkSource().getGeneratorState();
            if (!isAnyStructureChunk(generatorState, cx, cz)) {
                return Collections.emptyMap();
            }
            ChunkPos chunkPos = new ChunkPos(cx, cz);
            ProtoChunk startProto = new ProtoChunk(
                chunkPos,
                UpgradeData.EMPTY,
                level,
                level.palettedContainerFactory(),
                null
            );
            startProto.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState randomState = level.getChunkSource().randomState();
            try {
                IsolatedWorldGenLevel startWorldGen = new IsolatedWorldGenLevel(level, startProto, null, generator, randomState);
                IsolatedStructureManager startStructureManager = new IsolatedStructureManager(startWorldGen, level.getSeed());
                startWorldGen.setStructureManager(startStructureManager);
                generator.createStructures(
                    level.registryAccess(),
                    generatorState,
                    startStructureManager,
                    startProto,
                    level.getStructureTemplateManager(),
                    level.dimension()
                );
            } catch (Throwable t) {
                WildResetMod.LOGGER.warn("[WildReset] Failed to compute structure starts for {}", chunkPos, t);
            }
            Map<Structure, StructureStart> starts = startProto.getAllStarts();
            if (starts.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<Structure, StructureStart> validStarts = new HashMap<>(starts.size());
            for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                StructureStart s = entry.getValue();
                if (s != null && s.isValid()) {
                    s.getBoundingBox();
                    validStarts.put(entry.getKey(), s);
                }
            }
            return validStarts.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(validStarts);
        });
    }

    private static ProtoChunk cloneProtoChunk(ServerLevel level, ProtoChunk source) {
        synchronized (source) {
            LevelChunkSection[] srcSections = source.getSections();
            LevelChunkSection[] copiedSections = new LevelChunkSection[srcSections.length];
            for (int i = 0; i < srcSections.length; i++) {
                copiedSections[i] = srcSections[i].copy();
                // Ensure Lithium's RandomTickingSectionDataHelper initializes its internal byte[] data array
                copiedSections[i].recalcBlockCounts();
            }
            ProtoChunk copy = new ProtoChunk(
                source.getPos(),
                UpgradeData.EMPTY,
                copiedSections,
                new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(),
                level,
                level.palettedContainerFactory(),
                null
            );
            copy.setPersistedStatus(source.getPersistedStatus());
            copy.setAllStarts(source.getAllStarts());
            copy.setAllReferences(source.getAllReferences());
            for (BlockEntity be : source.getBlockEntities().values()) {
                if (be != null) {
                    BlockState state = copy.getBlockState(be.getBlockPos());
                    if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock eb) {
                        CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
                        BlockEntity clonedBe = BlockEntity.loadStatic(be.getBlockPos(), state, saved, level.registryAccess());
                        if (clonedBe != null) {
                            copy.setBlockEntity(clonedBe);
                        }
                    }
                }
            }
            for (CompoundTag entityTag : source.getEntities()) {
                copy.addEntity(entityTag.copy());
            }
            for (Map.Entry<Heightmap.Types, Heightmap> entry : source.getHeightmaps()) {
                copy.setHeightmap(entry.getKey(), entry.getValue().getRawData().clone());
            }
            return copy;
        }
    }

    private static final class BaseChunkData {
        private final ChunkPos pos;
        private final ProtoChunk baseProto;
        private final LongOpenHashSet baseStructurePositions;
        private final List<BoundingBox> rigidInteriorBoxes;

        BaseChunkData(
            ChunkPos pos,
            ProtoChunk baseProto,
            LongOpenHashSet baseStructurePositions,
            List<BoundingBox> rigidInteriorBoxes
        ) {
            this.pos = pos;
            this.baseProto = baseProto;
            this.baseStructurePositions = baseStructurePositions;
            this.rigidInteriorBoxes = rigidInteriorBoxes;
        }

        BlockState getBlockState(ServerLevel level, BlockPos blockPos) {
            return baseProto.getBlockState(blockPos);
        }

        int getHeight(ServerLevel level, Heightmap.Types type, int lx, int lz) {
            return baseProto.getHeight(type, lx, lz);
        }

        Holder<Biome> getNoiseBiome(ServerLevel level, int qx, int qy, int qz) {
            return baseProto.getNoiseBiome(qx, qy, qz);
        }

        PalettedContainerRO<Holder<Biome>> getSectionBiomes(int sectionIndex) {
            return baseProto.getSection(sectionIndex).getBiomes();
        }

        boolean isStructurePosition(long packedPos) {
            return baseStructurePositions.contains(packedPos);
        }

        boolean isInsideRigidPieceInterior(BlockPos p) {
            for (int i = 0, n = rigidInteriorBoxes.size(); i < n; i++) {
                if (rigidInteriorBoxes.get(i).isInside(p.getX(), p.getY(), p.getZ())) {
                    return true;
                }
            }
            return false;
        }

        ProtoChunk createWorkingCopy(ServerLevel level) {
            return cloneProtoChunk(level, baseProto);
        }
    }

    private static BaseChunkData getOrComputeBaseChunk(ServerLevel level, int cx, int cz) {
        ConcurrentHashMap<Long, CompletableFuture<BaseChunkData>> dimCache = BASE_TERRAIN_CACHE.computeIfAbsent(
            level.dimension(), d -> new ConcurrentHashMap<>()
        );
        if (dimCache.size() > 8192) {
            dimCache.clear();
        }
        long key = ChunkPos.pack(cx, cz);
        CompletableFuture<BaseChunkData> existing = dimCache.get(key);
        if (existing != null) {
            return existing.join();
        }
        CompletableFuture<BaseChunkData> created = new CompletableFuture<>();
        CompletableFuture<BaseChunkData> prev = dimCache.putIfAbsent(key, created);
        if (prev != null) {
            return prev.join();
        }
        try {
            BaseChunkData data = computeBaseChunkData(level, cx, cz);
            created.complete(data);
            return data;
        } catch (Throwable t) {
            dimCache.remove(key, created);
            created.completeExceptionally(t);
            throw t;
        }
    }

    private static boolean isFeaturePoolPiece(StructurePiece piece) {
        return piece instanceof PoolElementStructurePiece poolPiece
            && poolPiece.getElement() instanceof FeaturePoolElement;
    }

    private static BaseChunkData computeBaseChunkData(ServerLevel level, int cx, int cz) {
        initReflection();
        ChunkPos pos = new ChunkPos(cx, cz);
        ProtoChunk proto = new ProtoChunk(
            pos,
            UpgradeData.EMPTY,
            level,
            level.palettedContainerFactory(),
            null
        );
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        proto.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
        proto.setAllStarts(getOrComputeStructureStarts(level, cx, cz));

        proto.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        // Scan full 8-chunk structure range and inflate by Beardifier.BEARD_KERNEL_RADIUS (12 blocks)
        // so Beardifier terrain adaptation around village/structure edges matches Vanilla 100%.
        for (int sx = cx - 8; sx <= cx + 8; sx++) {
            for (int sz = cz - 8; sz <= cz + 8; sz++) {
                Map<Structure, StructureStart> starts = getOrComputeStructureStarts(level, sx, sz);
                if (starts.isEmpty()) continue;
                long refPacked = ChunkPos.pack(sx, sz);
                for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                    StructureStart s = entry.getValue();
                    if (s != null && s.isValid() && s.getBoundingBox().intersects(minBlockX - 12, minBlockZ - 12, minBlockX + 27, minBlockZ + 27)) {
                        proto.addReferenceForStructure(entry.getKey(), refPacked);
                    }
                }
            }
        }

        IsolatedWorldGenLevel worldGenLevel = new IsolatedWorldGenLevel(level, proto, null, generator, randomState);
        IsolatedStructureManager structureManager = new IsolatedStructureManager(worldGenLevel, level.getSeed());
        worldGenLevel.setStructureManager(structureManager);

        try {
            proto.setPersistedStatus(ChunkStatus.BIOMES);
            generator.createBiomes(randomState, Blender.empty(), structureManager, proto).join();
        } catch (Throwable ignored) {}

        boolean filledViaNoise = false;
        if (generator instanceof NoiseBasedChunkGenerator noiseGen
            && createNoiseChunkMethod != null
            && doFillMethod != null
            && buildSurfaceMethod != null) {
            try {
                NoiseGeneratorSettings settings = noiseGen.generatorSettings().value();
                NoiseSettings clamped = settings.noiseSettings().clampToHeightAccessor(proto.getHeightAccessorForGeneration());
                Object noiseChunk = createNoiseChunkMethod.invoke(
                    noiseGen, proto, structureManager, Blender.empty(), randomState, clamped
                );
                doFillMethod.invoke(noiseGen, noiseChunk, proto);
                for (LevelChunkSection sec : proto.getSections()) {
                    sec.recalcBlockCounts();
                }

                Set<Holder<Biome>> biomes = new HashSet<>();
                for (LevelChunkSection section : proto.getSections()) {
                    section.getBiomes().forEachInPalette(biomes::add);
                }
                MaterialRule surfaceRule = settings.materialRule().value();
                buildSurfaceMethod.invoke(
                    noiseGen, proto, noiseChunk, randomState, worldGenLevel.getBiomeManager(), biomes, surfaceRule
                );
                // Prime WORLD_SURFACE_WG and OCEAN_FLOOR_WG before carvers so cave entrances do not depress WORLD_SURFACE_WG
                Heightmap.primeHeightmaps(proto, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
                if (generateCarversMethod != null) {
                    generateCarversMethod.invoke(
                        noiseGen, proto, Blender.empty(), noiseChunk, randomState, worldGenLevel.getBiomeManager(), null, surfaceRule
                    );
                }
                if (noiseChunk instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {}
                }
                filledViaNoise = true;
            } catch (Throwable t) {
                WildResetMod.LOGGER.debug("[WildReset] Falling back to column terrain generation for {}", pos, t);
            }
        }

        if (!filledViaNoise) {
            int minY = level.getMinY();
            int maxY = minY + level.getHeight();
            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = pos.getMinBlockX() + lx;
                    int wz = pos.getMinBlockZ() + lz;
                    NoiseColumn col = generator.getBaseColumn(wx, wz, proto, randomState);
                    int topSolidY = minY;
                    for (int y = minY; y < maxY; y++) {
                        BlockState st = col.getBlock(y);
                        if (!st.isAir()) {
                            mpos.set(wx, y, wz);
                            proto.setBlockState(mpos, st, 0);
                            if (st.is(Blocks.STONE) || st.is(Blocks.DEEPSLATE)) {
                                topSolidY = y;
                            }
                        }
                    }
                    if (topSolidY >= generator.getSeaLevel() - 1) {
                        mpos.set(wx, topSolidY, wz);
                        proto.setBlockState(mpos, Blocks.GRASS_BLOCK.defaultBlockState(), 0);
                        for (int d = 1; d <= 3 && topSolidY - d > minY; d++) {
                            mpos.set(wx, topSolidY - d, wz);
                            proto.setBlockState(mpos, Blocks.DIRT.defaultBlockState(), 0);
                        }
                    }
                }
            }
            Heightmap.primeHeightmaps(proto, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
        }

        for (LevelChunkSection sec : proto.getSections()) {
            sec.recalcBlockCounts();
        }
        proto.setPersistedStatus(ChunkStatus.FEATURES);
        Heightmap.primeHeightmaps(proto, ChunkStatus.FINAL_HEIGHTMAPS);

        List<BoundingBox> rigidInteriorBoxes = new ArrayList<>();
        LongOpenHashSet baseStructurePositions = new LongOpenHashSet();

        levelRigidStructurePieceInteriors(level, proto, pos, structureManager, rigidInteriorBoxes);
        placeBaseStructures(level, proto, pos, worldGenLevel, structureManager, generator, baseStructurePositions, rigidInteriorBoxes);

        for (LevelChunkSection sec : proto.getSections()) {
            sec.recalcBlockCounts();
        }
        return new BaseChunkData(pos, proto, baseStructurePositions, rigidInteriorBoxes);
    }

    private static void levelRigidStructurePieceInteriors(
        ServerLevel level,
        ProtoChunk proto,
        ChunkPos pos,
        StructureManager structureManager,
        List<BoundingBox> rigidInteriorBoxes
    ) {
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        int minY = level.getMinY();
        int maxY = minY + level.getHeight() - 1;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        boolean anyCleared = false;

        for (Structure structure : proto.getAllReferences().keySet()) {
            boolean adaptTerrain = structure.terrainAdaptation() == TerrainAdjustment.BEARD_THIN
                || structure.terrainAdaptation() == TerrainAdjustment.BEARD_BOX;
            List<StructureStart> starts = structureManager.startsForStructure(pos.x(), pos.z(), structure);
            for (StructureStart start : starts) {
                if (start == null || !start.isValid()) continue;
                synchronized (start) {
                    for (StructurePiece piece : start.getPieces()) {
                        if (!(piece instanceof PoolElementStructurePiece poolPiece)) continue;
                        if (poolPiece.getElement() instanceof EmptyPoolElement || poolPiece.getElement() instanceof FeaturePoolElement) {
                            continue;
                        }
                        if (poolPiece.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                            continue;
                        }
                        BoundingBox box = poolPiece.getBoundingBox();
                        if (!box.intersects(minBlockX, minBlockZ, minBlockX + 15, minBlockZ + 15)) {
                            continue;
                        }
                        int groundY = box.minY() + poolPiece.getGroundLevelDelta();
                        if (groundY <= box.maxY()) {
                            rigidInteriorBoxes.add(new BoundingBox(
                                box.minX(), groundY, box.minZ(),
                                box.maxX(), box.maxY(), box.maxZ()
                            ));
                        }
                        if (!adaptTerrain) continue;

                        int x0 = Math.max(minBlockX, box.minX());
                        int x1 = Math.min(minBlockX + 15, box.maxX());
                        int z0 = Math.max(minBlockZ, box.minZ());
                        int z1 = Math.min(minBlockZ + 15, box.maxZ());
                        int y0 = Math.max(groundY, minY);
                        int y1 = Math.min(box.maxY() + 3, maxY);

                        for (int wx = x0; wx <= x1; wx++) {
                            for (int wz = z0; wz <= z1; wz++) {
                                boolean clearedColumn = false;
                                for (int y = y0; y <= y1; y++) {
                                    mpos.set(wx, y, wz);
                                    BlockState cur = proto.getBlockState(mpos);
                                    if (!cur.isAir() && cur.getFluidState().isEmpty()) {
                                        proto.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                                        clearedColumn = true;
                                        anyCleared = true;
                                    }
                                }
                                if (clearedColumn && groundY - 1 >= minY) {
                                    mpos.set(wx, groundY - 1, wz);
                                    BlockState floor = proto.getBlockState(mpos);
                                    if (floor.is(Blocks.DIRT) || floor.is(Blocks.STONE)) {
                                        proto.setBlockState(mpos, Blocks.GRASS_BLOCK.defaultBlockState(), 0);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (anyCleared) {
            Heightmap.primeHeightmaps(proto, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            Heightmap.primeHeightmaps(proto, ChunkStatus.FINAL_HEIGHTMAPS);
        }
    }

    private static void placeBaseStructures(
        ServerLevel level,
        ProtoChunk proto,
        ChunkPos pos,
        IsolatedWorldGenLevel worldGenLevel,
        StructureManager structureManager,
        ChunkGenerator generator,
        LongOpenHashSet baseStructurePositions,
        List<BoundingBox> rigidInteriorBoxes
    ) {
        if (proto.getAllReferences().isEmpty()) {
            return;
        }
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        BoundingBox writableBox = new BoundingBox(
            minBlockX, level.getMinY(), minBlockZ,
            minBlockX + 15, level.getMinY() + level.getHeight() - 1, minBlockZ + 15
        );
        Registry<Structure> structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Map<Integer, List<Structure>> structuresByStep = structureRegistry.stream()
            .collect(Collectors.groupingBy(s -> s.step().ordinal()));

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(level.getSeed(), minBlockX, minBlockZ);

        worldGenLevel.setRecordingStructurePositions(baseStructurePositions);
        worldGenLevel.setGeneratingStructure(true);
        try {
            int maxSteps = GenerationStep.Decoration.values().length;
            for (int step = 0; step < maxSteps; step++) {
                List<Structure> list = structuresByStep.getOrDefault(step, Collections.emptyList());
                int index = 0;
                for (Structure structure : list) {
                    random.setFeatureSeed(decorationSeed, index, step);
                    List<StructureStart> starts = structureManager.startsForStructure(pos.x(), pos.z(), structure);
                    for (StructureStart start : starts) {
                        if (start == null || !start.isValid()) continue;
                        synchronized (start) {
                            List<StructurePiece> pieces = start.getPieces();
                            if (pieces.isEmpty()) continue;
                            BoundingBox firstBox = pieces.get(0).getBoundingBox();
                            BlockPos center = firstBox.getCenter();
                            BlockPos pivot = new BlockPos(center.getX(), firstBox.minY(), center.getZ());
                            for (StructurePiece piece : pieces) {
                                if (isFeaturePoolPiece(piece)) {
                                    continue;
                                }
                                if (piece.getBoundingBox().intersects(writableBox)) {
                                    piece.postProcess(worldGenLevel, structureManager, generator, random, writableBox, pos, pivot);
                                }
                            }
                            structure.afterPlace(
                                worldGenLevel,
                                structureManager,
                                generator,
                                random,
                                writableBox,
                                pos,
                                new PiecesContainer(pieces)
                            );
                        }
                    }
                    index++;
                }
            }
        } catch (Throwable t) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to place base structures for {}", pos, t);
        } finally {
            worldGenLevel.setGeneratingStructure(false);
            worldGenLevel.setRecordingStructurePositions(null);
        }

        Heightmap.primeHeightmaps(proto, ChunkStatus.FINAL_HEIGHTMAPS);
        // Elevate WORLD_SURFACE_WG and OCEAN_FLOOR_WG over placed RIGID building footprints so Step 9 biome
        // features (patch_grass, flowers, trees) target the building roofs instead of ground floors inside rooms.
        if (!rigidInteriorBoxes.isEmpty()) {
            Heightmap worldSurfaceWg = proto.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            Heightmap oceanFloorWg = proto.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
            for (BoundingBox box : rigidInteriorBoxes) {
                int x0 = Math.max(minBlockX, box.minX());
                int x1 = Math.min(minBlockX + 15, box.maxX());
                int z0 = Math.max(minBlockZ, box.minZ());
                int z1 = Math.min(minBlockZ + 15, box.maxZ());
                int y0 = Math.max(box.minY() - 1, level.getMinY());
                int y1 = Math.min(box.maxY(), level.getMinY() + level.getHeight() - 1);
                for (int wx = x0; wx <= x1; wx++) {
                    int lx = wx & 15;
                    for (int wz = z0; wz <= z1; wz++) {
                        int lz = wz & 15;
                        for (int y = y0; y <= y1; y++) {
                            mpos.set(wx, y, wz);
                            BlockState st = proto.getBlockState(mpos);
                            if (!st.isAir()) {
                                worldSurfaceWg.update(lx, y, lz, st);
                                oceanFloorWg.update(lx, y, lz, st);
                            }
                        }
                    }
                }
            }
        }
    }

    private static final class DecoratedChunkData {
        private final ChunkPos pos;
        private final ProtoChunk proto;
        private final BaseChunkData baseData;
        private final LongOpenHashSet structurePositions;
        private final LongOpenHashSet geodePositions;
        private final Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingGeodeSpillover;
        private final Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingSpillover;

        DecoratedChunkData(
            ChunkPos pos,
            ProtoChunk proto,
            BaseChunkData baseData,
            LongOpenHashSet structurePositions,
            LongOpenHashSet geodePositions,
            Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingGeodeSpillover,
            Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingSpillover
        ) {
            this.pos = pos;
            this.proto = proto;
            this.baseData = baseData;
            this.structurePositions = structurePositions;
            this.geodePositions = geodePositions;
            this.outgoingGeodeSpillover = outgoingGeodeSpillover;
            this.outgoingSpillover = outgoingSpillover;
        }

        ProtoChunk createWorkingCopy(ServerLevel level) {
            return cloneProtoChunk(level, proto);
        }

        BlockState getBlockState(ServerLevel level, BlockPos blockPos) {
            return proto.getBlockState(blockPos);
        }

        BlockState getBaseBlockState(ServerLevel level, BlockPos blockPos) {
            return baseData.getBlockState(level, blockPos);
        }

        boolean isStructurePosition(long packedPos) {
            return structurePositions.contains(packedPos) || baseData.isStructurePosition(packedPos);
        }

        boolean isInsideRigidPieceInterior(BlockPos p) {
            return baseData.isInsideRigidPieceInterior(p);
        }

        LongOpenHashSet getGeodePositions() {
            return geodePositions;
        }

        Long2ObjectLinkedOpenHashMap<BlockState> getGeodeSpilloverFor(long targetChunkPacked) {
            return outgoingGeodeSpillover.get(targetChunkPacked);
        }

        Long2ObjectLinkedOpenHashMap<BlockState> getSpilloverFor(long targetChunkPacked) {
            return outgoingSpillover.get(targetChunkPacked);
        }
    }

    private static DecoratedChunkData getOrComputeDecoratedChunk(ServerLevel level, int cx, int cz) {
        ConcurrentHashMap<Long, CompletableFuture<DecoratedChunkData>> dimCache = DECORATED_CHUNK_CACHE.computeIfAbsent(
            level.dimension(), d -> new ConcurrentHashMap<>()
        );
        if (dimCache.size() > 8192) {
            dimCache.clear();
        }
        long key = ChunkPos.pack(cx, cz);
        CompletableFuture<DecoratedChunkData> existing = dimCache.get(key);
        if (existing != null) {
            return existing.join();
        }
        CompletableFuture<DecoratedChunkData> created = new CompletableFuture<>();
        CompletableFuture<DecoratedChunkData> prev = dimCache.putIfAbsent(key, created);
        if (prev != null) {
            return prev.join();
        }
        try {
            DecoratedChunkData data = computeDecoratedChunk(level, cx, cz);
            created.complete(data);
            return data;
        } catch (Throwable t) {
            dimCache.remove(key, created);
            created.completeExceptionally(t);
            throw t;
        }
    }

    private static DecoratedChunkData computeDecoratedChunk(ServerLevel level, int cx, int cz) {
        ChunkPos pos = new ChunkPos(cx, cz);
        BaseChunkData[][] baseGrid = new BaseChunkData[3][3];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                baseGrid[dx + 1][dz + 1] = getOrComputeBaseChunk(level, cx + dx, cz + dz);
            }
        }

        BaseChunkData centerBase = baseGrid[1][1];
        ProtoChunk proto = centerBase.createWorkingCopy(level);
        proto.getEntities().clear();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        IsolatedWorldGenLevel worldGenLevel = new IsolatedWorldGenLevel(level, proto, baseGrid, generator, randomState);
        IsolatedStructureManager structureManager = new IsolatedStructureManager(worldGenLevel, level.getSeed(), false);
        worldGenLevel.setStructureManager(structureManager);

        THREAD_ISOLATED_STRUCTURE_MANAGER.set(structureManager);
        try {
            generator.applyBiomeDecoration(worldGenLevel, proto, structureManager);

            WorldgenRandom mobRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
            mobRandom.setDecorationSeed(level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
            NaturalSpawner.spawnMobsForChunkGeneration(
                worldGenLevel,
                pos.getWorldPosition().atY(worldGenLevel.getMaxY()),
                pos,
                mobRandom
            );
        } catch (Throwable t) {
            WildResetMod.LOGGER.warn("[WildReset] Failed to apply biome decoration / mob spawns for {}", pos, t);
        } finally {
            THREAD_ISOLATED_STRUCTURE_MANAGER.remove();
        }

        return new DecoratedChunkData(
            pos,
            proto,
            centerBase,
            worldGenLevel.getCenterStructurePositions(),
            worldGenLevel.getCenterGeodePositions(),
            worldGenLevel.getOutgoingGeodeSpillover(),
            worldGenLevel.getOutgoingSpillover()
        );
    }

    public static CompoundTag readCachedCleanChunk(ServerLevel level, ChunkPos pos) {
        synchronized (STORAGE_LOCK) {
            try {
                CompoundTag tag = getStorage(level).read(pos);
                if (tag == null || tag.getIntOr(NBT_WILDRESET_SCHEMA, 0) != CACHE_SCHEMA_VERSION) {
                    return null;
                }
                return tag.copy();
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static void writeCachedCleanChunk(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        if (tag == null) return;
        synchronized (STORAGE_LOCK) {
            try {
                CompoundTag cleanCopy = tag.copy();
                cleanCopy.putInt(NBT_WILDRESET_SCHEMA, CACHE_SCHEMA_VERSION);
                getStorage(level).write(pos, cleanCopy);
            } catch (Exception e) {
                WildResetMod.LOGGER.warn("[WildReset] Failed to write cached clean chunk {}", pos, e);
            }
        }
    }

    public static int captureLoadedChunksAsCleanCache(ServerLevel level, int centerX, int centerZ, int radiusBlocks) {
        int minCx = (centerX - radiusBlocks) >> 4;
        int maxCx = (centerX + radiusBlocks) >> 4;
        int minCz = (centerZ - radiusBlocks) >> 4;
        int maxCz = (centerZ + radiusBlocks) >> 4;
        int count = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
                if (chunk != null) {
                    CompoundTag tag = SerializableChunkData.copyOf(level, chunk).write();
                    tag.put("block_entities", new ListTag());
                    writeCachedCleanChunk(level, new ChunkPos(cx, cz), tag);
                    count++;
                }
            }
        }
        synchronized (STORAGE_LOCK) {
            try {
                if (cacheStorage != null) cacheStorage.flush();
            } catch (Exception ignored) {}
        }
        return count;
    }

    public static CompoundTag getOrGenerateCleanChunkTag(ServerLevel level, ChunkPos pos) {
        CompoundTag cached = readCachedCleanChunk(level, pos);
        if (cached != null) {
            return cached;
        }
        CompoundTag generated = generateCleanChunkFromSeed(level, pos);
        writeCachedCleanChunk(level, pos, generated);
        return generated;
    }

    public static boolean isInsideRigidStructureInterior(ServerLevel level, ChunkPos cpos, BlockPos pos) {
        BaseChunkData base = getOrComputeBaseChunk(level, cpos.x(), cpos.z());
        return base.isInsideRigidPieceInterior(pos);
    }

    private static boolean isGeodeBlock(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK)
            || state.is(Blocks.BUDDING_AMETHYST)
            || state.is(Blocks.CALCITE)
            || state.is(Blocks.SMOOTH_BASALT)
            || state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.LARGE_AMETHYST_BUD)
            || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.SMALL_AMETHYST_BUD);
    }

    private static boolean isAmethystCrystalOrBud(BlockState state) {
        return state.is(Blocks.AMETHYST_CLUSTER)
            || state.is(Blocks.LARGE_AMETHYST_BUD)
            || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.SMALL_AMETHYST_BUD);
    }

    private static boolean isRockOrOreBlob(BlockState state) {
        return state.is(Blocks.STONE)
            || state.is(Blocks.DEEPSLATE)
            || state.is(Blocks.TUFF)
            || state.is(Blocks.GRANITE)
            || state.is(Blocks.DIORITE)
            || state.is(Blocks.ANDESITE)
            || state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
            || state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
            || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
            || state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
            || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
            || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
            || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
            || state.is(Blocks.RAW_IRON_BLOCK)
            || state.is(Blocks.RAW_COPPER_BLOCK)
            || state.is(Blocks.RAW_GOLD_BLOCK);
    }

    private static boolean isSoilOrCaveGroundBlob(BlockState state) {
        return state.is(Blocks.DIRT)
            || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM)
            || state.is(Blocks.MUD)
            || state.is(Blocks.CLAY)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.SAND)
            || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SAND)
            || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.DRIPSTONE_BLOCK)
            || state.is(Blocks.MOSS_BLOCK)
            || state.is(Blocks.SCULK)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.OBSIDIAN);
    }

    private static boolean isNaturalGroundOrRock(BlockState state) {
        return state.is(BlockTags.STONE_ORE_REPLACEABLES)
            || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
            || state.is(BlockTags.MOSS_REPLACEABLE)
            || isSoilOrCaveGroundBlob(state);
    }

    private static boolean isTreeTrunkOrMushroomBlock(BlockState state) {
        return state.is(BlockTags.LOGS)
            || state.is(Blocks.MANGROVE_ROOTS)
            || state.is(Blocks.MUDDY_MANGROVE_ROOTS)
            || state.is(Blocks.MUSHROOM_STEM)
            || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
            || state.is(Blocks.RED_MUSHROOM_BLOCK);
    }

    private static boolean isReplaceableVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.DEAD_BUSH)
            || state.is(Blocks.VINE)
            || state.is(Blocks.GLOW_LICHEN)
            || state.is(Blocks.MOSS_CARPET)
            || state.is(Blocks.SNOW)
            || state.is(BlockTags.FLOWERS)
            || state.is(BlockTags.SAPLINGS);
    }

    public static CompoundTag generateCleanChunkFromSeed(ServerLevel level, ChunkPos pos) {
        DecoratedChunkData[][] decoratedGrid = new DecoratedChunkData[3][3];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                decoratedGrid[dx + 1][dz + 1] = getOrComputeDecoratedChunk(level, pos.x() + dx, pos.z() + dz);
            }
        }

        DecoratedChunkData centerData = decoratedGrid[1][1];
        ProtoChunk center = centerData.createWorkingCopy(level);
        long centerKey = pos.pack();
        LongOpenHashSet protectedGeodePositions = new LongOpenHashSet(centerData.getGeodePositions());

        // Pass 1 (Step 2 LOCAL_MODIFICATIONS priority): Merge incoming Amethyst Geode spillover first
        // so neighbor-originating geodes cleanly carve through any Step 6/7 stone/tuff/dirt/dripstone/moss
        // blobs that center generated before knowing about the neighbor's geode.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                DecoratedChunkData neighborData = decoratedGrid[dx + 1][dz + 1];
                Long2ObjectLinkedOpenHashMap<BlockState> geodeIncoming = neighborData.getGeodeSpilloverFor(centerKey);
                if (geodeIncoming == null || geodeIncoming.isEmpty()) continue;

                for (Long2ObjectMap.Entry<BlockState> entry : geodeIncoming.long2ObjectEntrySet()) {
                    BlockState state = entry.getValue();
                    if (state == null) continue;
                    long packedPos = entry.getLongKey();
                    BlockPos p = BlockPos.of(packedPos);
                    if (level.isOutsideBuildHeight(p.getY())) continue;

                    BlockState cur = center.getBlockState(p);
                    if (cur.is(Blocks.BEDROCK) || cur.is(BlockTags.FEATURES_CANNOT_REPLACE) || cur.hasBlockEntity()) {
                        continue;
                    }
                    if (centerData.isStructurePosition(packedPos) && !cur.isAir()) {
                        continue;
                    }
                    if (isAmethystCrystalOrBud(state)) {
                        if (cur.isAir() || cur.is(Blocks.WATER)) {
                            center.setBlockState(p, state, 0);
                            protectedGeodePositions.add(packedPos);
                        }
                    } else if (state.isAir()) {
                        if (!isAmethystCrystalOrBud(cur)) {
                            center.setBlockState(p, state, 0);
                            protectedGeodePositions.add(packedPos);
                        }
                    } else {
                        center.setBlockState(p, state, 0);
                        protectedGeodePositions.add(packedPos);
                    }
                }
            }
        }

        // Pass 2: Merge remaining cross-border feature spillover with strict step/target validation
        // so underground stone/ore blobs never fuse into geodes or cavities, and surface grass/plants
        // never overwrite village building blocks or spawn inside RIGID structure interiors.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                DecoratedChunkData neighborData = decoratedGrid[dx + 1][dz + 1];
                Long2ObjectLinkedOpenHashMap<BlockState> incoming = neighborData.getSpilloverFor(centerKey);
                if (incoming == null || incoming.isEmpty()) continue;

                for (Long2ObjectMap.Entry<BlockState> entry : incoming.long2ObjectEntrySet()) {
                    BlockState state = entry.getValue();
                    if (state == null) continue;
                    long packedPos = entry.getLongKey();
                    BlockPos p = BlockPos.of(packedPos);
                    if (level.isOutsideBuildHeight(p.getY())) continue;

                    if (protectedGeodePositions.contains(packedPos)) {
                        continue;
                    }
                    if (centerData.isStructurePosition(packedPos)) {
                        continue;
                    }
                    if (centerData.isInsideRigidPieceInterior(p)
                        && !state.is(BlockTags.LEAVES)
                        && !isTreeTrunkOrMushroomBlock(state)) {
                        continue;
                    }

                    BlockState cur = center.getBlockState(p);
                    if (cur.is(Blocks.BEDROCK)
                        || cur.is(BlockTags.FEATURES_CANNOT_REPLACE)
                        || cur.hasBlockEntity()
                        || isGeodeBlock(cur)) {
                        continue;
                    }

                    // If center carved an underground cavity here (e.g., geode, dungeon, lake), never fill it back in
                    BlockState baseState = centerData.getBaseBlockState(level, p);
                    if (cur.isAir() && !baseState.isAir() && !state.isAir()) {
                        continue;
                    }

                    if (state.isAir()) {
                        if (!cur.isAir() && !cur.is(BlockTags.LOGS) && !cur.is(BlockTags.PLANKS) && !cur.is(BlockTags.LEAVES)) {
                            center.setBlockState(p, state, 0);
                        }
                    } else if (state.is(BlockTags.LEAVES)) {
                        if (cur.isAir() || isReplaceableVegetation(cur)) {
                            center.setBlockState(p, state, 0);
                        }
                    } else if (isTreeTrunkOrMushroomBlock(state)) {
                        if (cur.isAir() || cur.canBeReplaced() || cur.is(BlockTags.LEAVES) || isReplaceableVegetation(cur)) {
                            center.setBlockState(p, state, 0);
                        }
                    } else if (isRockOrOreBlob(state)) {
                        if (!cur.isAir()
                            && cur.getFluidState().isEmpty()
                            && (cur.is(BlockTags.STONE_ORE_REPLACEABLES) || cur.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES))) {
                            center.setBlockState(p, state, 0);
                        }
                    } else if (isSoilOrCaveGroundBlob(state)) {
                        if (!cur.isAir()
                            && cur.getFluidState().isEmpty()
                            && isNaturalGroundOrRock(cur)
                            && !cur.is(Blocks.DIRT_PATH)
                            && !cur.is(Blocks.FARMLAND)) {
                            center.setBlockState(p, state, 0);
                        }
                    } else if (!state.canOcclude()) {
                        // Non-occluding features (grass, tall grass, ferns, flowers, vines, glow lichen, moss carpet,
                        // dripstone, snow, mushrooms, sugar cane, seagrass, kelp, etc.) may only be placed into air
                        // (or water if the feature state itself is waterlogged/aquatic).
                        if (state.getFluidState().isEmpty()) {
                            if (cur.isAir()) {
                                center.setBlockState(p, state, 0);
                            }
                        } else {
                            if (cur.isAir() || cur.is(Blocks.WATER)) {
                                center.setBlockState(p, state, 0);
                            }
                        }
                    } else {
                        if (cur.isAir() || cur.canBeReplaced() || isNaturalGroundOrRock(cur)) {
                            center.setBlockState(p, state, 0);
                            if (state.hasBlockEntity() && center.getBlockEntity(p) == null && state.getBlock() instanceof EntityBlock eb) {
                                BlockEntity be = eb.newBlockEntity(p, state);
                                if (be != null) {
                                    center.setBlockEntity(be);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sanitize all fragile/attached blocks against the merged 3x3 chunk neighborhood
        // so no floating torches, wall torches, vines, glow lichen, moss blocks/carpets, dripstone, or plants remain.
        sanitizeUnsupportedAttachments(level, center, pos, decoratedGrid);

        for (LevelChunkSection sec : center.getSections()) {
            sec.recalcBlockCounts();
        }
        Heightmap.primeHeightmaps(center, EnumSet.allOf(Heightmap.Types.class));
        center.setPersistedStatus(ChunkStatus.FULL);
        center.setLightCorrect(false);

        CompoundTag written = SerializableChunkData.copyOf(level, center).write();
        written.putInt(NBT_WILDRESET_SCHEMA, CACHE_SCHEMA_VERSION);
        ListTag entityList = new ListTag();
        for (CompoundTag entityTag : center.getEntities()) {
            entityList.add(entityTag.copy());
        }
        written.put(NBT_WILDRESET_ENTITIES, entityList);
        trimTransientGenerationCachesIfNeeded();
        return written;
    }

    private static void sanitizeUnsupportedAttachments(
        ServerLevel level,
        ProtoChunk center,
        ChunkPos centerPos,
        DecoratedChunkData[][] decoratedGrid
    ) {
        SanitizerLevelView view = new SanitizerLevelView(level, center, centerPos, decoratedGrid);
        DecoratedChunkData centerData = decoratedGrid[1][1];
        int minX = centerPos.getMinBlockX();
        int minZ = centerPos.getMinBlockZ();
        int minY = level.getMinY();
        int maxY = minY + level.getHeight();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();

        // Pass 1 (Top -> Bottom): Clean up ceiling-hanging features (Vines, Cave Vines, Hanging Roots,
        // Spore Blossoms, downward Pointed Dripstone, hanging Lanterns/Chains) and isolated mid-air Moss Blocks.
        for (int y = maxY - 1; y >= minY; y--) {
            LevelChunkSection section = center.getSection(center.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                y = (y & ~15);
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, y & 15, lz);
                    if (state.isAir() || (state.canOcclude() && !state.is(Blocks.MOSS_BLOCK))) continue;
                    int wx = minX + lx;
                    int wz = minZ + lz;
                    mpos.set(wx, y, wz);

                    if (state.is(Blocks.MOSS_BLOCK)) {
                        // Remove 1-block isolated floating moss blocks in cave air that have no solid/moss support below or horizontally
                        BlockState below = view.getBlockState(npos.set(wx, y - 1, wz));
                        if (below.isAir() || !below.getFluidState().isEmpty()) {
                            boolean hasHorizontalSupport = false;
                            for (Direction dir : HORIZONTAL_DIRECTIONS) {
                                BlockState side = view.getBlockState(npos.set(wx + dir.getStepX(), y, wz + dir.getStepZ()));
                                if (!side.isAir() && side.getFluidState().isEmpty()
                                    && (side.is(Blocks.MOSS_BLOCK) || side.canOcclude())) {
                                    hasHorizontalSupport = true;
                                    break;
                                }
                            }
                            if (!hasHorizontalSupport) {
                                center.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                            }
                        }
                        continue;
                    }

                    if (state.is(Blocks.VINE)) {
                        BlockState updated = state;
                        BlockState above = view.getBlockState(npos.set(wx, y + 1, wz));
                        if (updated.getValue(VineBlock.UP) && !Block.isFaceFull(above.getCollisionShape(view, npos), Direction.DOWN)) {
                            updated = updated.setValue(VineBlock.UP, Boolean.FALSE);
                        }
                        boolean anyFace = updated.getValue(VineBlock.UP);
                        for (Direction dir : HORIZONTAL_DIRECTIONS) {
                            BooleanProperty prop = VineBlock.getPropertyForFace(dir);
                            if (updated.getValue(prop)) {
                                npos.set(wx + dir.getStepX(), y, wz + dir.getStepZ());
                                boolean sturdyWall = VineBlock.isAcceptableNeighbour(view, npos, dir);
                                boolean supportedByVineAbove = above.is(Blocks.VINE) && above.getValue(prop);
                                if (!sturdyWall && !supportedByVineAbove) {
                                    updated = updated.setValue(prop, Boolean.FALSE);
                                } else {
                                    anyFace = true;
                                }
                            }
                        }
                        if (!anyFace) {
                            center.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                        } else if (updated != state) {
                            center.setBlockState(mpos, updated, 0);
                        }
                        continue;
                    }

                    if (state.getBlock() instanceof SpeleothemBlock
                        && state.getValue(SpeleothemBlock.TIP_DIRECTION) == Direction.DOWN) {
                        if (!state.canSurvive(view, mpos)) {
                            center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                        }
                        continue;
                    }

                    if (state.is(Blocks.CAVE_VINES)
                        || state.is(Blocks.CAVE_VINES_PLANT)
                        || state.is(Blocks.WEEPING_VINES)
                        || state.is(Blocks.WEEPING_VINES_PLANT)
                        || state.is(Blocks.HANGING_ROOTS)
                        || state.is(Blocks.SPORE_BLOSSOM)) {
                        if (!state.canSurvive(view, mpos)) {
                            center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                        }
                    }
                }
            }
        }

        // Pass 2 (Bottom -> Top): Clean up floor-standing and wall-mounted attachments (Torches, Wall Torches,
        // Glow Lichen, Sculk Veins, Moss Carpets, Amethyst Clusters/Buds, upward Pointed Dripstone, Plants, Rails, Ladders)
        // and connect chunk-border structure fences/walls/stairs/panes.
        for (int y = minY; y < maxY; y++) {
            LevelChunkSection section = center.getSection(center.getSectionIndex(y));
            if (section.hasOnlyAir()) {
                y = (y | 15);
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, y & 15, lz);
                    if (state.isAir() || state.is(BlockTags.LEAVES)) continue;
                    int wx = minX + lx;
                    int wz = minZ + lz;
                    mpos.set(wx, y, wz);

                    if ((lx == 0 || lx == 15 || lz == 0 || lz == 15)
                        && (state.getBlock() instanceof CrossCollisionBlock
                            || state.getBlock() instanceof WallBlock
                            || state.getBlock() instanceof StairBlock)) {
                        BlockState shaped = Block.updateFromNeighbourShapes(state, view, mpos);
                        if (shaped != state) {
                            center.setBlockState(mpos, shaped, 0);
                            state = shaped;
                        }
                    }

                    if (state.canOcclude()) continue;

                    // Remove any stray wild grass/ferns inside RIGID structure piece interiors
                    if ((state.is(Blocks.SHORT_GRASS)
                            || state.is(Blocks.TALL_GRASS)
                            || state.is(Blocks.FERN)
                            || state.is(Blocks.LARGE_FERN))
                        && centerData.isInsideRigidPieceInterior(mpos)) {
                        center.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                        continue;
                    }

                    if (state.getBlock() instanceof MultifaceBlock) {
                        BlockState updated = state;
                        boolean anyValidFace = false;
                        for (Direction dir : Direction.values()) {
                            if (MultifaceBlock.hasFace(updated, dir)) {
                                npos.set(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ());
                                BlockState attachState = view.getBlockState(npos);
                                if (MultifaceBlock.canAttachTo(view, dir, npos, attachState)) {
                                    anyValidFace = true;
                                } else {
                                    updated = updated.setValue(MultifaceBlock.getFaceProperty(dir), Boolean.FALSE);
                                }
                            }
                        }
                        if (!anyValidFace) {
                            center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                        } else if (updated != state) {
                            center.setBlockState(mpos, updated, 0);
                        }
                        continue;
                    }

                    if (state.is(Blocks.CAVE_VINES_PLANT)) {
                        BlockState below = view.getBlockState(npos.set(wx, y - 1, wz));
                        if (!below.is(Blocks.CAVE_VINES) && !below.is(Blocks.CAVE_VINES_PLANT)) {
                            BlockState tip = Blocks.CAVE_VINES.defaultBlockState()
                                .setValue(CaveVines.BERRIES, state.getValue(CaveVines.BERRIES));
                            if (tip.canSurvive(view, mpos)) {
                                center.setBlockState(mpos, tip, 0);
                            } else {
                                center.setBlockState(mpos, Blocks.AIR.defaultBlockState(), 0);
                            }
                        }
                        continue;
                    }

                    if (state.getBlock() instanceof DoublePlantBlock) {
                        if (!state.canSurvive(view, mpos)) {
                            center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                            continue;
                        }
                        if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
                            BlockState above = view.getBlockState(npos.set(wx, y + 1, wz));
                            if (!above.is(state.getBlock())) {
                                center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                            }
                        }
                        continue;
                    }

                    if (!state.canOcclude() && !state.is(BlockTags.LEAVES)) {
                        if (!state.canSurvive(view, mpos)) {
                            center.setBlockState(mpos, state.getFluidState().createLegacyBlock(), 0);
                        }
                    }
                }
            }
        }
    }

    private static final class SanitizerLevelView implements WorldGenLevel {
        private final ServerLevel level;
        private final ProtoChunk center;
        private final ChunkPos centerPos;
        private final DecoratedChunkData[][] decoratedGrid;
        private final Long2ObjectLinkedOpenHashMap<BlockState>[] centerGeodeOutgoingByNeighbor;
        private final Long2ObjectLinkedOpenHashMap<BlockState>[] centerOutgoingByNeighbor;

        @SuppressWarnings("unchecked")
        SanitizerLevelView(
            ServerLevel level,
            ProtoChunk center,
            ChunkPos centerPos,
            DecoratedChunkData[][] decoratedGrid
        ) {
            this.level = level;
            this.center = center;
            this.centerPos = centerPos;
            this.decoratedGrid = decoratedGrid;
            this.centerGeodeOutgoingByNeighbor = new Long2ObjectLinkedOpenHashMap[9];
            this.centerOutgoingByNeighbor = new Long2ObjectLinkedOpenHashMap[9];
            DecoratedChunkData centerData = decoratedGrid[1][1];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long nKey = ChunkPos.pack(centerPos.x() + dx, centerPos.z() + dz);
                    int idx = (dx + 1) * 3 + (dz + 1);
                    this.centerGeodeOutgoingByNeighbor[idx] = centerData.getGeodeSpilloverFor(nKey);
                    this.centerOutgoingByNeighbor[idx] = centerData.getSpilloverFor(nKey);
                }
            }
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos.getY())) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx == centerPos.x() && cz == centerPos.z()) {
                return center.getBlockState(pos);
            }
            int dx = cx - centerPos.x();
            int dz = cz - centerPos.z();
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                int idx = (dx + 1) * 3 + (dz + 1);
                long packed = pos.asLong();
                Long2ObjectLinkedOpenHashMap<BlockState> geodeFromCenter = centerGeodeOutgoingByNeighbor[idx];
                if (geodeFromCenter != null) {
                    BlockState spilled = geodeFromCenter.get(packed);
                    if (spilled != null) {
                        return spilled;
                    }
                }
                Long2ObjectLinkedOpenHashMap<BlockState> fromCenter = centerOutgoingByNeighbor[idx];
                if (fromCenter != null) {
                    BlockState spilled = fromCenter.get(packed);
                    if (spilled != null) {
                        return spilled;
                    }
                }
                DecoratedChunkData neighbor = decoratedGrid[dx + 1][dz + 1];
                if (neighbor != null) {
                    return neighbor.getBlockState(level, pos);
                }
            }
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if ((pos.getX() >> 4) == centerPos.x() && (pos.getZ() >> 4) == centerPos.z()) {
                return center.getBlockEntity(pos);
            }
            return null;
        }

        @Override
        public long getSeed() { return level.getSeed(); }
        @Override
        public ServerLevel getLevel() { return level; }
        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(level.getDifficulty(), level.getGameTime(), 0L, 0.0F); }
        @Override
        public long nextSubTickCount() { return 0L; }
        @Override
        public LevelData getLevelData() { return level.getLevelData(); }
        @Override
        public MinecraftServer getServer() { return level.getServer(); }
        @Override
        public ChunkSource getChunkSource() { return level.getChunkSource(); }
        @Override
        public RandomSource getRandom() { return level.getRandom(); }
        @Override
        public void playSound(Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}
        @Override
        public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {}
        @Override
        public void levelEvent(Entity entity, int type, BlockPos pos, int data) {}
        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
        @Override
        public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) { return new ScheduledTick<>(type, pos, 0L, priority, 0L); }
        @Override
        public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) { return new ScheduledTick<>(type, pos, 0L, 0L); }
        @Override
        public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override
        public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) { return center; }
        @Override
        public boolean hasChunk(int x, int z) { return Math.abs(x - centerPos.x()) <= 1 && Math.abs(z - centerPos.z()) <= 1; }
        @Override
        public int getHeight(Heightmap.Types type, int x, int z) { return center.getHeight(type, x & 15, z & 15) + 1; }
        @Override
        public int getSkyDarken() { return 0; }
        @Override
        public BiomeManager getBiomeManager() { return level.getBiomeManager(); }
        @Override
        public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) { return level.getUncachedNoiseBiome(qx, qy, qz); }
        @Override
        public Holder<Biome> getBiome(BlockPos pos) { return level.getUncachedNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2); }
        @Override
        public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) { return level.getUncachedNoiseBiome(qx, qy, qz); }
        @Override
        public boolean isClientSide() { return false; }
        @Override
        public int getSeaLevel() { return level.getSeaLevel(); }
        @Override
        public DimensionType dimensionType() { return level.dimensionType(); }
        @Override
        public RegistryAccess registryAccess() { return level.registryAccess(); }
        @Override
        public FeatureFlagSet enabledFeatures() { return level.enabledFeatures(); }
        @Override
        public EnvironmentAttributeReader environmentAttributes() { return level.environmentAttributes(); }
        @Override
        public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
        @Override
        public WorldBorder getWorldBorder() { return level.getWorldBorder(); }
        @Override
        public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) { return this; }
        @Override
        public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return Collections.emptyList(); }
        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) { return false; }
        @Override
        public boolean removeBlock(BlockPos pos, boolean moved) { return false; }
        @Override
        public boolean destroyBlock(BlockPos pos, boolean drop, Entity entity, int recursionLeft) { return false; }
        @Override
        public boolean addFreshEntity(Entity entity) { return false; }
        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }
        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }
        @Override
        public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) { return Collections.emptyList(); }
        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> typeTest, AABB box, Predicate<? super T> predicate) { return Collections.emptyList(); }
        @Override
        public List<? extends Player> players() { return Collections.emptyList(); }
        @Override
        public int getHeight() { return level.getHeight(); }
        @Override
        public int getMinY() { return level.getMinY(); }
    }

    private static final class IsolatedStructureManager extends StructureManager {
        private final IsolatedWorldGenLevel worldGenLevel;
        private final boolean featurePoolPiecesOnly;

        IsolatedStructureManager(IsolatedWorldGenLevel level, long seed) {
            this(level, seed, false);
        }

        IsolatedStructureManager(IsolatedWorldGenLevel level, long seed, boolean featurePoolPiecesOnly) {
            super(level, new WorldOptions(seed, true, false), null);
            this.worldGenLevel = level;
            this.featurePoolPiecesOnly = featurePoolPiecesOnly;
        }

        @Override
        public StructureCheckResult checkStructurePresence(
            ChunkPos pos,
            Structure structure,
            StructurePlacement placement,
            boolean createReference
        ) {
            return StructureCheckResult.CHUNK_LOAD_NEEDED;
        }

        @Override
        public void addReference(StructureStart start) {
            start.addReference();
        }

        @Override
        public void fillStartsForStructure(
            Structure structure,
            LongSet references,
            Consumer<StructureStart> consumer
        ) {
            super.fillStartsForStructure(structure, references, start -> {
                synchronized (start) {
                    if (!featurePoolPiecesOnly) {
                        consumer.accept(start);
                        return;
                    }
                    List<StructurePiece> featurePieces = null;
                    for (StructurePiece piece : start.getPieces()) {
                        if (isFeaturePoolPiece(piece)) {
                            if (featurePieces == null) {
                                featurePieces = new ArrayList<>(4);
                            }
                            featurePieces.add(piece);
                        }
                    }
                    if (featurePieces != null && !featurePieces.isEmpty()) {
                        StructureStart filtered = new StructureStart(
                            start.getStructure(),
                            start.getChunkPos(),
                            start.getReferences(),
                            new PiecesContainer(featurePieces)
                        );
                        worldGenLevel.setGeneratingStructure(true);
                        try {
                            consumer.accept(filtered);
                        } finally {
                            worldGenLevel.setGeneratingStructure(false);
                        }
                    }
                }
            });
        }
    }

    private static final class NeighborProxySection extends LevelChunkSection {
        private final IsolatedWorldGenLevel worldGenLevel;
        private final int chunkX;
        private final int sectionMinY;
        private final int chunkZ;
        private final int sectionIndex;
        private final PalettedContainerRO<Holder<Biome>> neighborBiomes;

        NeighborProxySection(
            IsolatedWorldGenLevel worldGenLevel,
            int chunkX,
            int sectionMinY,
            int chunkZ,
            int sectionIndex,
            PalettedContainerRO<Holder<Biome>> neighborBiomes
        ) {
            super(worldGenLevel.level.palettedContainerFactory());
            this.worldGenLevel = worldGenLevel;
            this.chunkX = chunkX;
            this.sectionMinY = sectionMinY;
            this.chunkZ = chunkZ;
            this.sectionIndex = sectionIndex;
            this.neighborBiomes = neighborBiomes;
        }

        @Override
        public void acquire() {}

        @Override
        public void release() {}

        @Override
        public boolean hasOnlyAir() {
            return false;
        }

        @Override
        public PalettedContainerRO<Holder<Biome>> getBiomes() {
            return neighborBiomes != null ? neighborBiomes : super.getBiomes();
        }

        @Override
        public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            return neighborBiomes != null ? neighborBiomes.get(qx, qy, qz) : super.getNoiseBiome(qx, qy, qz);
        }

        @Override
        public BlockState getBlockState(int lx, int ly, int lz) {
            BlockPos pos = new BlockPos((chunkX << 4) + lx, sectionMinY + ly, (chunkZ << 4) + lz);
            return worldGenLevel.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(int lx, int ly, int lz) {
            return getBlockState(lx, ly, lz).getFluidState();
        }

        @Override
        public BlockState setBlockState(int lx, int ly, int lz, BlockState state) {
            return setBlockState(lx, ly, lz, state, false);
        }

        @Override
        public BlockState setBlockState(int lx, int ly, int lz, BlockState state, boolean useLocks) {
            BlockPos pos = new BlockPos((chunkX << 4) + lx, sectionMinY + ly, (chunkZ << 4) + lz);
            BlockState prev = worldGenLevel.getBlockState(pos);
            worldGenLevel.recordNeighborWrite(chunkX, chunkZ, pos, prev, state);
            return prev;
        }
    }

    private static final class NeighborProxyProtoChunk extends ProtoChunk {
        private final IsolatedWorldGenLevel worldGenLevel;

        NeighborProxyProtoChunk(
            IsolatedWorldGenLevel worldGenLevel,
            ChunkPos pos,
            BaseChunkData neighborBase
        ) {
            super(pos, UpgradeData.EMPTY, worldGenLevel.level, worldGenLevel.level.palettedContainerFactory(), null);
            this.worldGenLevel = worldGenLevel;
            for (int i = 0; i < this.sections.length; i++) {
                int sectionMinY = SectionPos.sectionToBlockCoord(getSectionYFromSectionIndex(i));
                PalettedContainerRO<Holder<Biome>> biomes = neighborBase != null
                    ? neighborBase.getSectionBiomes(i)
                    : null;
                this.sections[i] = new NeighborProxySection(
                    worldGenLevel,
                    pos.x(),
                    sectionMinY,
                    pos.z(),
                    i,
                    biomes
                );
            }
        }

        @Override
        public LevelChunkSection getSection(int index) {
            return this.sections[index];
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return worldGenLevel.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return worldGenLevel.getFluidState(pos);
        }

        @Override
        public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
            BlockState prev = worldGenLevel.getBlockState(pos);
            worldGenLevel.recordNeighborWrite(chunkPos.x(), chunkPos.z(), pos, prev, state);
            return prev;
        }
    }

    private static final class IsolatedWorldGenLevel implements WorldGenLevel {
        private final ServerLevel level;
        private final ProtoChunk center;
        private final ChunkPos centerPos;
        private final int minBlockX;
        private final int minBlockZ;
        private final BaseChunkData[][] baseGrid;
        private final ChunkGenerator generator;
        private final RandomState randomState;
        private StructureManager structureManager;
        private final Map<Long, ProtoChunk> neighborStartChunks = new HashMap<>();
        private final Long2ObjectOpenHashMap<BlockState> spilloverLookup = new Long2ObjectOpenHashMap<>();
        private final Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingGeodeSpillover = new HashMap<>();
        private final Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> outgoingSpillover = new HashMap<>();
        private final LongOpenHashSet centerStructurePositions = new LongOpenHashSet();
        private final LongOpenHashSet centerGeodePositions = new LongOpenHashSet();
        private final LongOpenHashSet neighborGeodePositions = new LongOpenHashSet();
        private LongOpenHashSet externalStructureRecorder;
        private boolean generatingStructure = false;
        private boolean generatingGeode = false;
        private final RandomSource random;
        private final BiomeManager biomeManager;
        private final EnvironmentAttributeSystem environmentAttributes;
        private final AtomicLong subTickCount = new AtomicLong();

        IsolatedWorldGenLevel(
            ServerLevel level,
            ProtoChunk center,
            BaseChunkData[][] baseGrid,
            ChunkGenerator generator,
            RandomState randomState
        ) {
            this.level = level;
            this.center = center;
            this.centerPos = center.getPos();
            this.minBlockX = centerPos.getMinBlockX();
            this.minBlockZ = centerPos.getMinBlockZ();
            this.baseGrid = baseGrid;
            this.generator = generator;
            this.randomState = randomState;
            this.structureManager = level.structureManager();
            this.random = RandomSource.create(level.getSeed() ^ centerPos.pack());
            this.biomeManager = level.getBiomeManager().withDifferentSource(this);
            this.environmentAttributes = EnvironmentAttributeSystem.builder().addStaticLayers(this).build();
            if (baseGrid != null && baseGrid[1][1] != null) {
                this.centerStructurePositions.addAll(baseGrid[1][1].baseStructurePositions);
            }
        }

        void setStructureManager(StructureManager structureManager) {
            this.structureManager = structureManager;
        }

        void setRecordingStructurePositions(LongOpenHashSet recorder) {
            this.externalStructureRecorder = recorder;
        }

        void setGeneratingStructure(boolean generatingStructure) {
            this.generatingStructure = generatingStructure;
        }

        @Override
        public void setCurrentlyGenerating(Supplier<String> currentlyGenerating) {
            if (currentlyGenerating == null) {
                this.generatingGeode = false;
                if (this.externalStructureRecorder == null) {
                    this.generatingStructure = false;
                }
                return;
            }
            try {
                String desc = currentlyGenerating.get();
                this.generatingGeode = desc != null && desc.contains("geode");
                if (this.externalStructureRecorder == null) {
                    this.generatingStructure = desc != null && desc.contains("worldgen/structure");
                }
            } catch (Throwable ignored) {
                this.generatingGeode = false;
                if (this.externalStructureRecorder == null) {
                    this.generatingStructure = false;
                }
            }
        }

        LongOpenHashSet getCenterStructurePositions() {
            return centerStructurePositions;
        }

        LongOpenHashSet getCenterGeodePositions() {
            return centerGeodePositions;
        }

        Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> getOutgoingGeodeSpillover() {
            return outgoingGeodeSpillover;
        }

        Map<Long, Long2ObjectLinkedOpenHashMap<BlockState>> getOutgoingSpillover() {
            return outgoingSpillover;
        }

        void recordNeighborWrite(int cx, int cz, BlockPos pos, BlockState prev, BlockState state) {
            long packedPos = pos.asLong();
            boolean isGeode = generatingGeode || isGeodeBlock(state);
            if (!isGeode && neighborGeodePositions.contains(packedPos)) {
                return;
            }

            int dx = cx - centerPos.x();
            int dz = cz - centerPos.z();
            if (!generatingStructure && baseGrid != null && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                BaseChunkData neighborBase = baseGrid[dx + 1][dz + 1];
                if (neighborBase != null) {
                    if (neighborBase.isInsideRigidPieceInterior(pos)
                        && !state.is(BlockTags.LEAVES)
                        && !isTreeTrunkOrMushroomBlock(state)) {
                        return;
                    }
                    if (neighborBase.isStructurePosition(packedPos) && !isGeode) {
                        return;
                    }
                }
            }

            spilloverLookup.put(packedPos, state);
            long neighborChunkKey = ChunkPos.pack(cx, cz);
            if (isGeode) {
                neighborGeodePositions.add(packedPos);
                outgoingGeodeSpillover.computeIfAbsent(neighborChunkKey, k -> new Long2ObjectLinkedOpenHashMap<>())
                    .putAndMoveToLast(packedPos, state);
            } else if (!state.isAir() || (prev != null && !prev.isAir())) {
                outgoingSpillover.computeIfAbsent(neighborChunkKey, k -> new Long2ObjectLinkedOpenHashMap<>())
                    .putAndMoveToLast(packedPos, state);
            }
        }

        private ProtoChunk getOrCreateNeighborStartChunk(int cx, int cz) {
            long key = ChunkPos.pack(cx, cz);
            return neighborStartChunks.computeIfAbsent(key, k -> {
                ProtoChunk neighbor;
                int dx = cx - centerPos.x();
                int dz = cz - centerPos.z();
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    BaseChunkData neighborBase = baseGrid != null ? baseGrid[dx + 1][dz + 1] : null;
                    neighbor = new NeighborProxyProtoChunk(this, new ChunkPos(cx, cz), neighborBase);
                } else {
                    neighbor = new ProtoChunk(
                        new ChunkPos(cx, cz),
                        UpgradeData.EMPTY,
                        level,
                        level.palettedContainerFactory(),
                        null
                    );
                }
                neighbor.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
                neighbor.setAllStarts(getOrComputeStructureStarts(level, cx, cz));
                return neighbor;
            });
        }

        @Override
        public long getSeed() {
            return level.getSeed();
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            return Math.abs(cx - centerPos.x()) <= 1
                && Math.abs(cz - centerPos.z()) <= 1
                && !level.isOutsideBuildHeight(pos.getY());
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }

        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
            return new DifficultyInstance(level.getDifficulty(), level.getGameTime(), 0L, 0.0F);
        }

        @Override
        public long nextSubTickCount() {
            return subTickCount.getAndIncrement();
        }

        @Override
        public LevelData getLevelData() {
            return level.getLevelData();
        }

        @Override
        public MinecraftServer getServer() {
            return level.getServer();
        }

        @Override
        public ChunkSource getChunkSource() {
            return level.getChunkSource();
        }

        @Override
        public RandomSource getRandom() {
            return random;
        }

        @Override
        public void playSound(Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}

        @Override
        public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {}

        @Override
        public void levelEvent(Entity entity, int type, BlockPos pos, int data) {}

        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}

        @Override
        public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) {
            return new ScheduledTick<>(type, pos, level.getGameTime() + delay, priority, nextSubTickCount());
        }

        @Override
        public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
            return new ScheduledTick<>(type, pos, level.getGameTime() + delay, nextSubTickCount());
        }

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            return BlackholeTickAccess.emptyLevelList();
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            return BlackholeTickAccess.emptyLevelList();
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
            if (x == centerPos.x() && z == centerPos.z()) {
                return center;
            }
            return getOrCreateNeighborStartChunk(x, z);
        }

        @Override
        public boolean hasChunk(int x, int z) {
            return Math.abs(x - centerPos.x()) <= 8 && Math.abs(z - centerPos.z()) <= 8;
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx == centerPos.x() && cz == centerPos.z()) {
                return center.getHeight(type, x & 15, z & 15) + 1;
            }
            int dx = cx - centerPos.x();
            int dz = cz - centerPos.z();
            if (baseGrid != null && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                BaseChunkData neighborBase = baseGrid[dx + 1][dz + 1];
                if (neighborBase != null) {
                    return neighborBase.getHeight(level, type, x & 15, z & 15) + 1;
                }
            }
            int lx = Math.clamp(x - minBlockX, 0, 15);
            int lz = Math.clamp(z - minBlockZ, 0, 15);
            return center.getHeight(type, lx, lz) + 1;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        @Override
        public BiomeManager getBiomeManager() {
            return biomeManager;
        }

        @Override
        public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            int cx = qx >> 2;
            int cz = qz >> 2;
            if (cx == centerPos.x() && cz == centerPos.z()) {
                return center.getNoiseBiome(qx & 3, qy, qz & 3);
            }
            int dx = cx - centerPos.x();
            int dz = cz - centerPos.z();
            if (baseGrid != null && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                BaseChunkData neighborBase = baseGrid[dx + 1][dz + 1];
                if (neighborBase != null) {
                    return neighborBase.getNoiseBiome(level, qx & 3, qy, qz & 3);
                }
            }
            return level.getUncachedNoiseBiome(qx, qy, qz);
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biomeManager.getBiome(pos);
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) {
            return level.getUncachedNoiseBiome(qx, qy, qz);
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public int getSeaLevel() {
            return level.getSeaLevel();
        }

        @Override
        public DimensionType dimensionType() {
            return level.dimensionType();
        }

        @Override
        public RegistryAccess registryAccess() {
            return level.registryAccess();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return level.enabledFeatures();
        }

        @Override
        public EnvironmentAttributeReader environmentAttributes() {
            return environmentAttributes;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public WorldBorder getWorldBorder() {
            return level.getWorldBorder();
        }

        @Override
        public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            return getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        }

        @Override
        public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
            return Collections.emptyList();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos.getY())) {
                return null;
            }
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx != centerPos.x() || cz != centerPos.z()) {
                BlockState state = getBlockState(pos);
                if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock entityBlock) {
                    return entityBlock.newBlockEntity(pos, state);
                }
                return null;
            }
            BlockEntity existing = center.getBlockEntity(pos);
            if (existing != null) {
                return existing;
            }
            BlockState state = center.getBlockState(pos);
            if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock entityBlock) {
                BlockEntity created = entityBlock.newBlockEntity(pos, state);
                if (created != null) {
                    center.setBlockEntity(created);
                    return created;
                }
            }
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos.getY())) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx == centerPos.x() && cz == centerPos.z()) {
                return center.getBlockState(pos);
            }
            BlockState spilled = spilloverLookup.get(pos.asLong());
            if (spilled != null) {
                return spilled;
            }
            int dx = cx - centerPos.x();
            int dz = cz - centerPos.z();
            if (baseGrid != null && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                BaseChunkData neighborBase = baseGrid[dx + 1][dz + 1];
                if (neighborBase != null) {
                    return neighborBase.getBlockState(level, pos);
                }
            }
            int clampedLx = Math.clamp(pos.getX() - minBlockX, 0, 15);
            int clampedLz = Math.clamp(pos.getZ() - minBlockZ, 0, 15);
            int oceanFloorY = center.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, clampedLx, clampedLz) + 1;
            int worldSurfaceY = center.getHeight(Heightmap.Types.WORLD_SURFACE_WG, clampedLx, clampedLz) + 1;
            int y = pos.getY();
            if (y >= worldSurfaceY) {
                return Blocks.AIR.defaultBlockState();
            }
            if (y >= oceanFloorY) {
                return Blocks.WATER.defaultBlockState();
            }
            return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            if (!ensureCanWrite(pos)) {
                return false;
            }
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx != centerPos.x() || cz != centerPos.z()) {
                BlockState prev = getBlockState(pos);
                recordNeighborWrite(cx, cz, pos, prev, state);
                return true;
            }
            long packedPos = pos.asLong();
            boolean isGeode = generatingGeode || isGeodeBlock(state);
            if (!isGeode && centerGeodePositions.contains(packedPos)) {
                return false;
            }
            if (!generatingStructure && baseGrid != null && baseGrid[1][1] != null) {
                BaseChunkData centerBase = baseGrid[1][1];
                if (centerBase.isInsideRigidPieceInterior(pos)
                    && !state.is(BlockTags.LEAVES)
                    && !isTreeTrunkOrMushroomBlock(state)) {
                    return false;
                }
                if (centerStructurePositions.contains(packedPos) && !isGeode && !state.is(Blocks.SNOW)) {
                    return false;
                }
            }
            if (generatingStructure) {
                centerStructurePositions.add(packedPos);
                if (externalStructureRecorder != null) {
                    externalStructureRecorder.add(packedPos);
                }
            }
            if (isGeode) {
                centerGeodePositions.add(packedPos);
            }
            BlockState prev = center.setBlockState(pos, state, flags);
            if (prev != null && prev.hasBlockEntity() && !prev.is(state.getBlock())) {
                center.removeBlockEntity(pos);
            }
            if (state.hasBlockEntity() && center.getBlockEntity(pos) == null && state.getBlock() instanceof EntityBlock entityBlock) {
                BlockEntity created = entityBlock.newBlockEntity(pos, state);
                if (created != null) {
                    center.setBlockEntity(created);
                }
            }
            return prev != null;
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean moved) {
            return setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean drop, Entity entity, int recursionLeft) {
            BlockState cur = getBlockState(pos);
            if (cur.isAir()) {
                return false;
            }
            return setBlock(pos, Blocks.AIR.defaultBlockState(), 3, recursionLeft);
        }

        @Override
        public boolean addFreshEntity(Entity entity) {
            int cx = entity.getBlockX() >> 4;
            int cz = entity.getBlockZ() >> 4;
            if (cx == centerPos.x() && cz == centerPos.z()) {
                center.addEntity(entity);
                return true;
            }
            return false;
        }

        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
            return predicate.test(getBlockState(pos));
        }

        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
            return predicate.test(getFluidState(pos));
        }

        @Override
        public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
            BlockEntity be = getBlockEntity(pos);
            if (be == null || be.getType() != type) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            T cast = (T) be;
            return Optional.of(cast);
        }

        @Override
        public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
            return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
        }

        @Override
        public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
            return Collections.emptyList();
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> typeTest, AABB box, Predicate<? super T> predicate) {
            return Collections.emptyList();
        }

        @Override
        public List<? extends Player> players() {
            return Collections.emptyList();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }
}
