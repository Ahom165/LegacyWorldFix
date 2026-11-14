package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/**
 * Entry point and dispatcher for all pre-Anvil world conversions that Mojang
 * removed from recent versions.
 *
 * <p>Since 26.x the game only reads Anvil ({@code version: 19133}) and refuses
 * anything older with "This world must be opened in an older version (like
 * 1.6.4) to be safely converted". Three legacy shapes are handled, in
 * increasing order of age:
 *
 * <ul>
 *   <li><b>McRegion</b> (Beta 1.3 - 1.6.4 era, {@code version: 19132}): the
 *       original case - {@code region/*.mcr} containers are rewritten as
 *       {@code .mca};</li>
 *   <li><b>Infdev / Alpha</b> (June 2010 - Beta 1.2): loose gzipped
 *       {@code c.<x>.<z>.dat} chunk files in base36 folders, upgraded
 *       directly to Anvil by {@link PreRegionUpgrader};</li>
 *   <li><b>Indev / Classic</b> (December 2009 - February 2010): one fixed-size
 *       map inside level.dat, sliced into chunks by {@link IndevConverter}.</li>
 * </ul>
 *
 * <p>All three converge on the same chunk transformation
 * ({@link #transformChunkNbt}): the flat 16x16x128 block array (order x,z,y)
 * is split into sixteen 4096-byte sections (order y,z,x) and pre-1.6 short
 * entity health values are normalized to the float representation introduced
 * in 1.6. The result is byte-for-byte the kind of early-Anvil world that 1.2
 * to 1.8 used to produce, which the 26.x "file fixing" + DataFixerUpper chain
 * (lowest schema 99) still upgrades to the current data version.
 */
public final class LegacyMcRegionUpgrader {
        public static final int MCREGION_STORAGE_VERSION = 19132;
        public static final int ANVIL_STORAGE_VERSION = 19133;

        /**
         * One worker per logical core: the conversion blocks the calling thread
         * anyway, so there is no reason to keep a core idle, and the datafixer
         * pass is pure CPU that scales with cores. Bounded for safety.
         */
        private static final int PARALLELISM = Math.max(2, Math.min(32, Runtime.getRuntime().availableProcessors()));

        /** Storage shapes this mod knows how to handle. */
        public enum LegacyFormat {
                NONE,
                /** Already Anvil (19133 or later) - nothing to do. */
                ANVIL,
                /** Beta 1.3 - 1.6.4 era region container (19132). */
                MCREGION,
                /** Infdev / Alpha loose chunk files. */
                PRE_REGION,
                /** Indev / Classic fixed map inside level.dat. */
                INDEV
        }

        private static final Map<Path, Object> WORLD_LOCKS = new ConcurrentHashMap<>();

        private LegacyMcRegionUpgrader() {
        }

        /** Cheap check kept for compatibility: does this world still use the McRegion storage version? */
        public static boolean isMcRegionWorld(Path worldDir) {
                return detectFormat(worldDir) == LegacyFormat.MCREGION;
        }

        /** True when the world still uses any storage this mod can upgrade. */
        public static boolean isLegacyWorld(Path worldDir) {
                LegacyFormat format = detectFormat(worldDir);
                return format == LegacyFormat.MCREGION || format == LegacyFormat.PRE_REGION || format == LegacyFormat.INDEV;
        }

        /**
         * Classifies the world without touching anything. Never throws: unknown or
         * unreadable worlds count as {@link LegacyFormat#NONE} so vanilla keeps full
         * control over them.
         */
        public static LegacyFormat detectFormat(Path worldDir) {
                Path levelDat = worldDir.resolve("level.dat");
                if (!Files.isRegularFile(levelDat)) {
                        return LegacyFormat.NONE;
                }

                CompoundTag root;
                try {
                        root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
                } catch (IOException | RuntimeException e) {
                        LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Could not read {}", levelDat, e);
                        return LegacyFormat.NONE;
                }

                // Indev / Classic: the whole world is one fixed map at the root.
                if (IndevConverter.isIndevLevel(root)) {
                        return LegacyFormat.INDEV;
                }

                CompoundTag data = root.getCompoundOrEmpty("Data");
                if (data.isEmpty()) {
                        return LegacyFormat.NONE;
                }

                int version = data.getIntOr("version", -1);
                if (version == MCREGION_STORAGE_VERSION) {
                        return LegacyFormat.MCREGION;
                }
                if (version == ANVIL_STORAGE_VERSION) {
                        return LegacyFormat.ANVIL;
                }
                if (version > ANVIL_STORAGE_VERSION) {
                        return LegacyFormat.ANVIL;
                }

                // version == -1 (tag absent, Infdev/Alpha) or any other unknown value:
                // decide from the directory layout. McRegion .mcr containers win over
                // loose chunk files, since both could theoretically coexist mid-migration.
                if (hasMcRegionContainers(worldDir)) {
                        return LegacyFormat.MCREGION;
                }
                if (PreRegionUpgrader.hasLegacyChunkFiles(worldDir)) {
                        return LegacyFormat.PRE_REGION;
                }
                return LegacyFormat.NONE;
        }

        private static boolean hasMcRegionContainers(Path worldDir) {
                try (Stream<Path> entries = Files.list(worldDir)) {
                        Path topRegion = worldDir.resolve("region");
                        if (Files.isDirectory(topRegion) && hasMcrFiles(topRegion)) {
                                return true;
                        }
                        return entries.filter(Files::isDirectory)
                                .filter(dir -> dir.getFileName().toString().startsWith("DIM"))
                                .anyMatch(dir -> hasMcrFiles(dir.resolve("region")));
                } catch (IOException e) {
                        return false;
                }
        }

        private static boolean hasMcrFiles(Path regionDir) {
                if (!Files.isDirectory(regionDir)) {
                        return false;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mcr")) {
                        return stream.iterator().hasNext();
                } catch (IOException e) {
                        return false;
                }
        }

        /**
         * Converts the world in place if (and only if) it still uses a legacy
         * storage format. Failures leave level.dat untouched, so the world remains
         * an honest legacy world and vanilla keeps refusing it (no half states).
         *
         * @return true when the world can be handed to vanilla afterwards
         */
        public static boolean convertWorld(Path worldDir) {
                Object lock = WORLD_LOCKS.computeIfAbsent(worldDir.toAbsolutePath().normalize(), key -> new Object());
                synchronized (lock) {
                        LegacyFormat format = detectFormat(worldDir);
                        String generatorId = generatorIdFor(readLevelDat(worldDir));
                        return switch (format) {
                                case MCREGION -> convertMcRegionWorld(worldDir, generatorId);
                                case PRE_REGION -> convertPreRegionWorld(worldDir, generatorId);
                                case INDEV -> IndevConverter.convertWorld(worldDir, readLevelDat(worldDir));
                                default -> true;
                        };
                }
        }

        /**
         * Chunk-generator id for the DFU context tag: superflat did not exist
         * before 1.1, so every legacy world is noise-based except an explicit
         * "flat" LevelType in level.dat (Beta 1.3 - 1.1 McRegion saves).
         */
        static String generatorIdFor(CompoundTag levelDatRoot) {
                String levelType = levelDatRoot.getCompoundOrEmpty("Data")
                        .getStringOr("LevelType", "")
                        .toLowerCase(java.util.Locale.ROOT);
                return levelType.contains("flat") ? "minecraft:flat" : "minecraft:noise";
        }

        /**
         * Vanilla dimension id for a storage root: the world directory itself for
         * the overworld, or a {@code DIM*} folder ({@code DIM-1} exists since
         * Alpha 1.2). Other DIM names come from old mods; vanilla ignores them
         * anyway, so they get overworld treatment.
         */
        static String dimensionIdFor(Path dimensionRoot) {
                String name = dimensionRoot.getFileName().toString();
                if (name.equals("DIM-1")) {
                        return "minecraft:the_nether";
                }
                if (name.equals("DIM1")) {
                        return "minecraft:the_end";
                }
                return "minecraft:overworld";
        }

        /**
         * Runs the datafixer pass on a freshly converted Anvil chunk, so chunks are
         * written to disk already at the current data version. This replicates
         * exactly what the vanilla world upgrader would do per chunk
         * ({@code SimpleRegionStorage#upgradeChunkTag}): a chunk without DataVersion
         * counts as version -1 (DFU clamps it to its oldest schema), the dimension
         * and generator go into the {@code __context} tag consumed by the 1.18
         * blending/height fixes, and the upgraded tag is stamped with the current
         * data version. The DFU is thread-safe, so this runs inside the parallel
         * conversion pool - with every chunk stamped, the vanilla upgrade pass is skipped entirely.
         *
         * <p>If the upgrade unexpectedly fails, the chunk is returned unchanged
         * (no DataVersion), and vanilla's own upgrader still converts it later:
         * slower, but identical output.
         */
        static CompoundTag applyVanillaDataFixes(CompoundTag chunkRoot, String dimensionId, String generatorId) {
                int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
                int version = NbtUtils.getDataVersion(chunkRoot, -1);
                if (version >= currentVersion) {
                        return chunkRoot;
                }

                CompoundTag context = new CompoundTag();
                context.putString("dimension", dimensionId);
                context.putString("generator", generatorId);
                chunkRoot.put("__context", context);
                try {
                        CompoundTag upgraded = DataFixTypes.CHUNK.update(DataFixers.getDataFixer(), chunkRoot, version, currentVersion);
                        upgraded.remove("__context");
                        NbtUtils.addDataVersion(upgraded, currentVersion);
                        return upgraded;
                } catch (RuntimeException e) {
                        LegacyWorldFix.LOGGER.warn(
                                "[LegacyWorldFix] In-place data upgrade failed for a chunk at data version {}; written without DataVersion so the vanilla upgrader handles it instead",
                                version,
                                e
                        );
                        return chunkRoot;
                } finally {
                        chunkRoot.remove("__context");
                }
        }

        /**
         * Full per-chunk pipeline: legacy shape &gt; Anvil shape &gt; current data
         * version. The single entry every converter (McRegion, pre-region, Indev)
         * uses for each of its chunks.
         */
        static CompoundTag transformAndUpgradeChunk(CompoundTag oldRoot, String dimensionId, String generatorId) throws IOException {
                return applyVanillaDataFixes(transformChunkNbt(oldRoot), dimensionId, generatorId);
        }

        /** Worker pool size shared by every converter. */
        static int parallelism() {
                return PARALLELISM;
        }

        /** Original case: McRegion (.mcr) containers, level.dat version 19132. */
        private static boolean convertMcRegionWorld(Path worldDir, String generatorId) {
                long start = System.nanoTime();
                LegacyWorldFix.LOGGER.info(
                        "[LegacyWorldFix] McRegion world detected (level.dat version {}): {}",
                        MCREGION_STORAGE_VERSION,
                        worldDir
                );

                try {
                        List<Path[]> pairs = new ArrayList<>();
                        for (Path regionDir : findRegionDirectories(worldDir)) {
                                try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mcr")) {
                                        for (Path mcr : stream) {
                                                String fileName = mcr.getFileName().toString();
                                                Path mca = mcr.resolveSibling(fileName.substring(0, fileName.length() - 4) + ".mca");
                                                pairs.add(new Path[]{mcr, mca, regionDir.getParent()});
                                        }
                                }
                        }

                        // Phase 1 (I/O bound, sequential): copy the raw compressed chunk
                        // payloads out of every .mcr container. No decode happens here.
                        Map<Path, List<RegionIo.RawChunk>> rawChunksBySource = new LinkedHashMap<>();
                        for (Path[] pair : pairs) {
                                rawChunksBySource.put(pair[0], RegionIo.readRawChunks(pair[0]));
                        }

                        // Phase 2 (CPU bound, parallel): decode + McRegion -> Anvil shape
                        // + vanilla datafixers + zlib, one task per chunk. Per-chunk
                        // granularity is what keeps every core busy even when the world
                        // spreads over only a handful of region files.
                        List<Callable<RegionIo.CompressedChunk>> tasks = new ArrayList<>();
                        int rawTotal = 0;
                        for (Path[] pair : pairs) {
                                rawTotal += rawChunksBySource.getOrDefault(pair[0], List.of()).size();
                        }
                        final int total = rawTotal;
                        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                        int step = Math.max(1, total / 10);
                        long phaseStart = System.nanoTime();
                        for (Path[] pair : pairs) {
                                List<RegionIo.RawChunk> group = rawChunksBySource.get(pair[0]);
                                if (group == null) {
                                        continue;
                                }

                                String dimensionId = dimensionIdFor(pair[2]);
                                for (RegionIo.RawChunk raw : group) {
                                        tasks.add(() -> {
                                                try {
                                                        CompoundTag chunk = RegionIo.decodeChunk(raw);
                                                        CompoundTag upgraded = transformAndUpgradeChunk(chunk, dimensionId, generatorId);
                                                        int finished = done.incrementAndGet();
                                                        if (finished % step == 0 || finished == total) {
                                                                long ms = Math.max(1L, (System.nanoTime() - phaseStart) / 1_000_000L);
                                                                LegacyWorldFix.LOGGER.info("[LegacyWorldFix] Converting McRegion chunks: {}/{} (~{} chunks/s)",
                                                                        finished, total, finished * 1000L / ms);
                                                        }
                                                        return RegionIo.compressChunk(pair[0], raw, upgraded);
                                                } catch (Exception e) {
                                                        LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping unreadable chunk in {} (slot {}): {}", pair[0], raw.slot(), e.toString());
                                                        return null;
                                                }
                                        });
                                }
                        }
                        List<RegionIo.CompressedChunk> converted = runInParallelCollect(tasks);

                        // Phase 3 (I/O bound, parallel): assemble and save each Anvil
                        // region file, then drop the .mcr it came from. One task per
                        // region file - the writers are independent, and a failure
                        // rethrows after every task finished so the world stays an
                        // honest McRegion world (level.dat untouched) and a retry
                        // converts whatever is left.
                        Map<Path, List<RegionIo.CompressedChunk>> bySource = new HashMap<>();
                        for (RegionIo.CompressedChunk chunk : converted) {
                                if (chunk != null) {
                                        bySource.computeIfAbsent(chunk.source(), key -> new ArrayList<>()).add(chunk);
                                }
                        }

                        List<Callable<Long>> writes = new ArrayList<>();
                        for (Path[] pair : pairs) {
                                writes.add(() -> {
                                        RegionIo.AnvilRegionWriter writer = new RegionIo.AnvilRegionWriter();
                                        for (RegionIo.CompressedChunk chunk : bySource.getOrDefault(pair[0], List.of())) {
                                                writer.writeCompressedChunk(chunk.slot(), chunk.timestamp(), chunk.payload());
                                        }
                                        writer.saveTo(pair[1]);
                                        Files.delete(pair[0]);
                                        return (long) writer.count();
                                });
                        }
                        long chunks = runInParallel(writes);

                        int regions = pairs.size();

                        // level.dat is rewritten last: if anything above failed, the world is
                        // still an honest McRegion world and the vanilla refusal stays in place.
                        patchLevelDat(worldDir, worldDir.resolve("level.dat"), worldDir.getFileName().toString(), true);
                        Path levelDatOld = worldDir.resolve("level.dat_old");
                        if (Files.isRegularFile(levelDatOld)) {
                                patchLevelDat(worldDir, levelDatOld, worldDir.getFileName().toString(), false);
                        }

                        writeMarker(worldDir, "converted-from-mcregion.txt",
                                "Converted from McRegion (version 19132) to Anvil (version 19133) by LegacyWorldFix",
                                regions, chunks);
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Converted {} region file(s) / {} chunk(s) from McRegion to Anvil in {} ms (~{} ms/chunk on {} threads, data fixes applied in place) - the vanilla upgrade pass is skipped",
                                regions,
                                chunks,
                                (System.nanoTime() - start) / 1_000_000L,
                                chunks == 0 ? 0 : (System.nanoTime() - start) / 1_000_000L / chunks,
                                PARALLELISM
                        );
                        return true;
                } catch (Exception e) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Failed to convert McRegion world {}. level.dat was left untouched; fix the cause (or restore a backup) and retry.",
                                worldDir,
                                e
                        );
                        return false;
                }
        }

        /** Infdev / Alpha loose chunk files, converted straight to Anvil by PreRegionUpgrader. */
        private static boolean convertPreRegionWorld(Path worldDir, String generatorId) {
                long start = System.nanoTime();
                LegacyWorldFix.LOGGER.info("[LegacyWorldFix] Pre-region world detected (Infdev/Alpha chunk files): {}", worldDir);

                try {
                        long chunks = PreRegionUpgrader.convertToAnvil(worldDir, generatorId);

                        patchLevelDat(worldDir, worldDir.resolve("level.dat"), worldDir.getFileName().toString(), true);
                        Path levelDatOld = worldDir.resolve("level.dat_old");
                        if (Files.isRegularFile(levelDatOld)) {
                                patchLevelDat(worldDir, levelDatOld, worldDir.getFileName().toString(), false);
                        }

                        writeMarker(worldDir, "converted-from-alpha.txt",
                                "Converted from pre-region Infdev/Alpha chunk files to Anvil (version 19133) by LegacyWorldFix",
                                0, chunks);
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Converted {} pre-region chunk(s) to Anvil in {} ms (~{} ms/chunk on {} threads, data fixes applied in place) - the vanilla upgrade pass is skipped",
                                chunks,
                                (System.nanoTime() - start) / 1_000_000L,
                                chunks == 0 ? 0 : (System.nanoTime() - start) / 1_000_000L / chunks,
                                PARALLELISM
                        );
                        return true;
                } catch (Exception e) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Failed to convert pre-region world {}. level.dat was left untouched; fix the cause (or restore a backup) and retry.",
                                worldDir,
                                e
                        );
                        return false;
                }
        }

        /** Converts every legacy world directly inside the given directory (server universe / saves folder). */
        public static int convertAllInDirectory(Path directory) {
                if (!Files.isDirectory(directory)) {
                        return 0;
                }

                int converted = 0;
                try (Stream<Path> entries = Files.list(directory)) {
                        for (Path candidate : entries.filter(Files::isDirectory).toList()) {
                                if (isLegacyWorld(candidate) && convertWorld(candidate)) {
                                        converted++;
                                }
                        }
                } catch (IOException e) {
                        LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Failed to scan {} for legacy worlds", directory, e);
                }

                return converted;
        }

        /**
         * Runs independent conversion tasks with a small worker pool and sums
         * their chunk counts.
         */
        static long runInParallel(List<Callable<Long>> tasks) throws IOException {
                long total = 0L;
                for (Long result : runInParallelCollect(tasks)) {
                        total += result;
                }

                return total;
        }

        /**
         * Runs independent conversion tasks with a worker pool and collects every
         * non-null result. The chunk (or its decoded payload) is the unit of work:
         * tasks never share mutable state, so parallelism is safe and scales with
         * cores (the historical code was strictly sequential).
         *
         * <p>The first task deliberately runs on the calling thread: it warms up
         * the vanilla datafixer (first rule composition for the from-&gt;to pair,
         * JIT) so the whole pool does not stampede the DFU's lazily-built rule
         * cache at once - that would both duplicate the composition work and race
         * on the cache's non-thread-safe {@code computeIfAbsent}.
         *
         * <p>The worker pool is shared and never shut down (daemon threads): a
         * conversion pass creates no threads, so multi-world servers pay the
         * thread churn once instead of per world and per phase.
         */
        private static final ExecutorService CONVERTER_POOL = Executors.newFixedThreadPool(PARALLELISM, runnable -> {
                Thread thread = new Thread(runnable, "LegacyWorldFix-Converter");
                thread.setDaemon(true);
                return thread;
        });

        static <T> List<T> runInParallelCollect(List<Callable<T>> tasks) throws IOException {
                if (tasks.isEmpty()) {
                        return List.of();
                }
                if (tasks.size() == 1 || PARALLELISM == 1) {
                        try {
                                List<T> results = new ArrayList<>(tasks.size());
                                for (Callable<T> task : tasks) {
                                        results.add(task.call());
                                }
                                return results;
                        } catch (IOException e) {
                                throw e;
                        } catch (Exception e) {
                                throw new IOException(e);
                        }
                }

                ExecutorService pool = CONVERTER_POOL;
                try {
                        try {
                                List<T> results = new ArrayList<>(tasks.size());
                                try {
                                        results.add(tasks.getFirst().call());
                                } catch (IOException e) {
                                        throw e;
                                } catch (Exception e) {
                                        throw new IOException(e);
                                }
                                if (tasks.size() > 1) {
                                        for (Future<T> future : pool.invokeAll(tasks.subList(1, tasks.size()))) {
                                                try {
                                                        results.add(future.get());
                                                } catch (ExecutionException e) {
                                                        throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                                                }
                                        }
                                }
                                return results;
                        } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted during conversion", e);
                        }
                } finally {
                        // Shared daemon pool: no shutdown, it outlives this conversion
                        // and is reused by the next world or phase.
                }
        }

        /** Reads level.dat, tolerating absence for the Indev path (its caller validates). */
        private static CompoundTag readLevelDat(Path worldDir) {
                try {
                        return NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
                } catch (IOException | RuntimeException e) {
                        return new CompoundTag();
                }
        }

        /**
         * Transforms one McRegion chunk NBT (root with "Level") into an early-Anvil chunk NBT.
         * Also used by the Infdev/Alpha and Indev converters, whose generated chunks go
         * through exactly the same pipeline as converted McRegion chunks.
         *
         * <p>Block and sky light arrays are deliberately NOT carried over: the
         * vanilla upgrade chain itself deletes them ({@code ChunkDeleteLightFix},
         * {@code ChunkLightRemoveFix}) because the modern light engine rebuilds
         * them from the block data on load. Dropping them at the source removes
         * ~4 KiB per section that would otherwise be copied through every one of
         * the ~500 datafixers, which cuts the DFU time by ~25% and produces
         * smaller region files, with byte-identical semantic output (verified by
         * running the full chain on both shapes and comparing).
         */
        static CompoundTag transformChunkNbt(CompoundTag oldRoot) throws IOException {
                CompoundTag oldLevel = oldRoot.getCompoundOrEmpty("Level");
                byte[] blocks = byteArray(oldLevel, "Blocks", 32768);
                byte[] data = byteArray(oldLevel, "Data", 16384);

                CompoundTag level = new CompoundTag();
                level.putInt("xPos", oldLevel.getIntOr("xPos", 0));
                level.putInt("zPos", oldLevel.getIntOr("zPos", 0));
                level.putLong("LastUpdate", oldLevel.getLongOr("LastUpdate", 0L));
                if (oldLevel.contains("TerrainPopulated")) {
                        level.putBoolean("TerrainPopulated", oldLevel.getBooleanOr("TerrainPopulated", true));
                }

                Tag heightMap = oldLevel.get("HeightMap");
                if (heightMap != null) {
                        level.put("HeightMap", heightMap.copy());
                }

                // 16x16x128 flat storage -> 8 sections of 16x16x16. Light arrays are
                // intentionally omitted (see the class-level note: the vanilla chain
                // deletes them anyway and the light engine rebuilds them on load).
                ListTag allSections = new ListTag();
                for (int sectionY = 0; sectionY < 8; sectionY++) {
                        byte[] sectionBlocks = new byte[4096];
                        byte[] sectionData = new byte[2048];

                        for (int yLocal = 0; yLocal < 16; yLocal++) {
                                int y = sectionY * 16 + yLocal;
                                for (int z = 0; z < 16; z++) {
                                        for (int x = 0; x < 16; x++) {
                                                int oldIndex = (x << 11) | (z << 7) | y;
                                                int newIndex = (yLocal << 8) | (z << 4) | x;

                                                sectionBlocks[newIndex] = blocks[oldIndex];
                                                setNibble(sectionData, newIndex, getNibble(data, oldIndex));
                                        }
                                }
                        }

                        CompoundTag section = new CompoundTag();
                        section.putByte("Y", (byte) sectionY);
                        section.putByteArray("Blocks", sectionBlocks);
                        section.putByteArray("Data", sectionData);
                        allSections.add(allSections.size(), section);
                }

                // All-air sections never enter the datafixer chain. Modern vanilla
                // itself omits those sections when it saves chunks (a section that
                // is nothing but air is not written to the region file), so a tag
                // without them is the ordinary current-version shape, and loading a
                // chunk whose sky sections are absent is the everyday path. Skipping
                // them at the source removes up to half of the data the ~500
                // datafixers walk through (measured: 80 -> 37 ms/chunk single-thread
                // on a full Alpha chunk, with the surviving sections byte-identical).
                // A chunk without any terrain keeps all eight sections: an empty
                // Sections list is a shape this conversion has no proof for, and
                // all-air chunks are a rare border case not worth it.
                ListTag sections = new ListTag();
                for (int i = 0; i < allSections.size(); i++) {
                        if (allSections.get(i) instanceof CompoundTag section && !isAllAir(section)) {
                                sections.add(sections.size(), section);
                        }
                }
                level.put("Sections", sections.isEmpty() ? allSections : sections);

                // Entities: normalize pre-1.6 health (short) to the float type introduced in 1.6.
                ListTag entities = oldLevel.getListOrEmpty("Entities");
                for (int i = 0; i < entities.size(); i++) {
                        if (entities.get(i) instanceof CompoundTag entityTag) {
                                fixEntityHealth(entityTag);
                        }
                }
                // An EMPTY Entities list is deliberately not written into the DFU
                // input: the chain's ~160 entity fixers pay their typed-list rewrite
                // (optic match + structural type comparisons) for a list they leave
                // empty, while an absent key short-circuits. Output equivalence is
                // proven modulo absent-vs-empty-list (docs/BENCH-PERF-1.6.0.txt):
                // vanilla reads a missing list as empty and writes the lists back on
                // the chunk's first save.
                if (!entities.isEmpty()) {
                        level.put("Entities", entities);
                }

                Tag tileEntities = oldLevel.get("TileEntities");
                if (tileEntities != null && !(tileEntities instanceof ListTag tileEntityList && tileEntityList.isEmpty())) {
                        level.put("TileEntities", tileEntities.copy());
                }

                CompoundTag newRoot = new CompoundTag();
                newRoot.put("Level", level);
                return newRoot;
        }

        /** True when a freshly split section carries no block at all (all 4096 bytes zero). */
        private static boolean isAllAir(CompoundTag section) {
                byte[] blocks = section.getByteArray("Blocks").orElse(new byte[0]);
                for (byte block : blocks) {
                        if (block != 0) {
                                return false;
                        }
                }
                return true;
        }

        private static void fixEntityHealth(CompoundTag entity) {
                normalizeHealth(entity);

                if (entity.get("Riding") instanceof CompoundTag ridingTag) {
                        fixEntityHealth(ridingTag);
                }

                if (entity.get("Passengers") instanceof ListTag passengers) {
                        for (int i = 0; i < passengers.size(); i++) {
                                if (passengers.get(i) instanceof CompoundTag passengerTag) {
                                        fixEntityHealth(passengerTag);
                                }
                        }
                }
        }

        private static void normalizeHealth(CompoundTag tag) {
                if (tag.get("Health") instanceof NumericTag numericTag && !(tag.get("Health") instanceof FloatTag)
                        && !(tag.get("Health") instanceof DoubleTag)
                ) {
                        tag.putFloat("Health", numericTag.floatValue());
                }

                if (tag.contains("HealF")) {
                        tag.remove("HealF");
                }
        }

        /**
         * Rewrites level.dat the way the vanilla 26.x "file fixing" pass would:
         * the storage version goes to Anvil, the whole LEVEL datafixer chain runs
         * in place, the current DataVersion is stamped, and the state the current
         * version expects in separate saved-data files ({@code world_gen_settings.dat},
         * {@code players/data/<uuid>.dat}) is extracted there. With the DataVersion
         * stamp vanilla skips its entire upgrade flow on load ("Starting upgrade
         * for world" never appears): no filefix copy with hardlinks, no region
         * rescan - the world opens right away.
         *
         * <p>If the LEVEL upgrade unexpectedly fails, nothing is extracted and
         * level.dat keeps no DataVersion, so vanilla runs its own (slow but
         * identical) pass later.
         */
        private static void patchLevelDat(Path worldDir, Path levelDat, String defaultLevelName, boolean writeSavedDataFiles) throws IOException {
                CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("Data");
                data.putInt("version", ANVIL_STORAGE_VERSION);

                // Capture the legacy generator fields before the DFU folds them into
                // world_gen_settings - the flat check decides the overworld generator.
                String legacyLevelType = data.getStringOr("LevelType", "");
                long legacySeed = data.getLongOr("RandomSeed", 0L);
                boolean legacyMapFeatures = data.getBooleanOr("MapFeatures", true);

                int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
                int dataVersion = data.getIntOr("DataVersion", -1);
                boolean levelFixed = true;
                if (dataVersion < currentVersion) {
                        try {
                                CompoundTag upgraded = DataFixTypes.LEVEL.update(DataFixers.getDataFixer(), data, dataVersion, currentVersion);
                                root.put("Data", upgraded);
                                data = upgraded;
                        } catch (RuntimeException e) {
                                LegacyWorldFix.LOGGER.warn(
                                        "[LegacyWorldFix] Could not upgrade {} in place ({}); the vanilla upgrade pass will handle level.dat instead",
                                        levelDat,
                                        e.toString()
                                );
                                levelFixed = false;
                        }
                }

                if (levelFixed) {
                        if (writeSavedDataFiles) {
                                writeWorldGenSettings(worldDir, data, legacyLevelType, legacySeed, legacyMapFeatures, currentVersion);
                                movePlayerToPlayerData(worldDir, data, currentVersion);
                        }
                        NbtUtils.addDataVersion(data, currentVersion);
                        // Same "Version" tag vanilla's addVersionsToLevelData writes, so
                        // the world list shows the world as a current-version world.
                        var worldVersion = SharedConstants.getCurrentVersion();
                        CompoundTag versionTag = new CompoundTag();
                        versionTag.putString("Name", worldVersion.name());
                        versionTag.putInt("Id", currentVersion);
                        versionTag.putBoolean("Snapshot", !worldVersion.stable());
                        versionTag.putString("Series", worldVersion.dataVersion().series());
                        data.put("Version", versionTag);
                }

                // Pre-Beta 1.8 saves have no LevelName; the world list needs one.
                if (data.getStringOr("LevelName", "").isEmpty()) {
                        data.putString("LevelName", defaultLevelName);
                }

                if (data.contains("Player")) {
                        CompoundTag player = data.getCompoundOrEmpty("Player");
                        if (player.size() > 0) {
                                fixEntityHealth(player);
                        }
                }

                NbtIo.writeCompressed(root, levelDat);
        }

        /**
         * Replicates the worldgen part of the vanilla {@code LevelDatToSavedDataFileFix}:
         * since 1.21.5 the world seed and dimension generators live in
         * {@code data/minecraft/world_gen_settings.dat}, not in level.dat, and the
         * game refuses to load a world without that file ("Overworld settings
         * missing"). The DFU leaves only {@code {RandomSeed}} behind in level.dat,
         * so we build the full structure: the legacy seed plus the three standard
         * dimensions, with a flat overworld generator when the legacy LevelType
         * says the world was superflat. The key is then removed from level.dat,
         * exactly like the file fix does.
         */
        private static void writeWorldGenSettings(Path worldDir, CompoundTag data, String legacyLevelType, long legacySeed, boolean legacyMapFeatures, int currentVersion) throws IOException {
                CompoundTag worldGenSettings = new CompoundTag();
                worldGenSettings.putBoolean("bonus_chest", false);
                long seed = data.getCompoundOrEmpty("world_gen_settings").getLongOr("RandomSeed", legacySeed);
                worldGenSettings.putLong("seed", seed);
                worldGenSettings.putBoolean("generate_structures", legacyMapFeatures);

                CompoundTag dimensions = new CompoundTag();
                dimensions.put("minecraft:the_nether", noiseDimension("minecraft:the_nether", "nether", false));
                dimensions.put("minecraft:the_end", noiseDimension("minecraft:the_end", "end", true));
                if (legacyLevelType.toLowerCase(java.util.Locale.ROOT).contains("flat")) {
                        dimensions.put("minecraft:overworld", flatOverworldDimension());
                } else {
                        dimensions.put("minecraft:overworld", noiseDimension("minecraft:overworld", "overworld", false));
                }
                worldGenSettings.put("dimensions", dimensions);

                CompoundTag file = new CompoundTag();
                file.put("data", worldGenSettings);
                NbtUtils.addDataVersion(file, currentVersion);

                Path target = worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
                Files.createDirectories(target.getParent());
                NbtIo.writeCompressed(file, target);
                data.remove("world_gen_settings");
        }

        /** Standard noise-based dimension generator, as written by the vanilla file fix. */
        private static CompoundTag noiseDimension(String dimensionType, String settingsName, boolean endBiomeSource) {
                CompoundTag biomeSource = new CompoundTag();
                biomeSource.putString("type", endBiomeSource ? "minecraft:the_end" : "minecraft:multi_noise");
                if (!endBiomeSource) {
                        biomeSource.putString("preset", "minecraft:" + settingsName);
                }

                CompoundTag generator = new CompoundTag();
                generator.putString("type", "minecraft:noise");
                generator.putString("settings", "minecraft:" + settingsName);
                generator.put("biome_source", biomeSource);

                CompoundTag dimension = new CompoundTag();
                dimension.putString("type", dimensionType);
                dimension.put("generator", generator);
                return dimension;
        }

        /** Superflat overworld generator for legacy worlds whose LevelType says "flat". */
        private static CompoundTag flatOverworldDimension() {
                ListTag layers = new ListTag();
                String[] blockIds = {"minecraft:bedrock", "minecraft:dirt", "minecraft:grass_block"};
                int[] heights = {1, 2, 1};
                for (int i = 0; i < blockIds.length; i++) {
                        CompoundTag layer = new CompoundTag();
                        layer.putString("block", blockIds[i]);
                        layer.putInt("height", heights[i]);
                        layers.add(layers.size(), layer);
                }

                CompoundTag settings = new CompoundTag();
                settings.put("layers", layers);
                settings.putString("biome", "minecraft:plains");
                settings.putBoolean("features", false);
                settings.putBoolean("lakes", false);

                CompoundTag generator = new CompoundTag();
                generator.putString("type", "minecraft:flat");
                generator.put("settings", settings);

                CompoundTag dimension = new CompoundTag();
                dimension.putString("type", "minecraft:overworld");
                dimension.put("generator", generator);
                return dimension;
        }

        /**
         * Replicates the player part of the vanilla file fix: the level.dat player
         * moves to {@code players/data/<uuid>.dat} with the PLAYER datafixer chain
         * applied (that is where item ids get their namespaces), and level.dat
         * records the UUID under {@code singleplayer_uuid}. Players without a UUID
         * (every pre-1.8 save) get the vanilla fallback all-zero UUID.
         */
        private static void movePlayerToPlayerData(Path worldDir, CompoundTag data, int currentVersion) throws IOException {
                CompoundTag player = data.getCompoundOrEmpty("Player");
                if (player.isEmpty()) {
                        return;
                }

                CompoundTag upgraded;
                try {
                        int playerVersion = NbtUtils.getDataVersion(player, -1);
                        upgraded = DataFixTypes.PLAYER.update(DataFixers.getDataFixer(), player, playerVersion, currentVersion);
                } catch (RuntimeException e) {
                        LegacyWorldFix.LOGGER.warn(
                                "[LegacyWorldFix] Could not upgrade the level.dat player in place ({}); it stays in level.dat",
                                e.toString()
                        );
                        return;
                }

                java.util.UUID uuid = new java.util.UUID(0L, 0L);
                int[] rawUuid = upgraded.getIntArray("UUID").orElse(null);
                if (rawUuid != null && rawUuid.length == 4) {
                        uuid = net.minecraft.core.UUIDUtil.uuidFromIntArray(rawUuid);
                }

                Path playerFile = worldDir.resolve("players").resolve("data").resolve(uuid + ".dat");
                Files.createDirectories(playerFile.getParent());
                NbtUtils.addDataVersion(upgraded, currentVersion);
                NbtIo.writeCompressed(upgraded, playerFile);

                data.remove("Player");
                data.putIntArray("singleplayer_uuid", net.minecraft.core.UUIDUtil.uuidToIntArray(uuid));
        }

        /**
         * Mirrors a successful on-disk conversion into the in-memory tag that the
         * caller of makeLevelSummary already holds, so the very world list refresh
         * that triggered us sees the world as Anvil instead of re-reading level.dat.
         *
         * <p>The tag received here is the <b>Data</b> compound itself: both vanilla
         * call sites of makeLevelSummary wrap {@code root.getCompoundOrEmpty("Data")}
         * in their Dynamic and {@link net.minecraft.world.level.storage.LevelVersion}
         * reads {@code version} at that level.
         */
        public static void patchInMemoryTag(CompoundTag dataLevelTag, Path worldDir) {
                dataLevelTag.putInt("version", ANVIL_STORAGE_VERSION);
                if (dataLevelTag.getStringOr("LevelName", "").isEmpty()) {
                        dataLevelTag.putString("LevelName", worldDir.getFileName().toString());
                }
        }

        private static List<Path> findRegionDirectories(Path worldDir) throws IOException {
                List<Path> result = new ArrayList<>();
                Path topRegion = worldDir.resolve("region");
                if (Files.isDirectory(topRegion)) {
                        result.add(topRegion);
                }

                try (Stream<Path> entries = Files.list(worldDir)) {
                        entries.filter(Files::isDirectory)
                                .filter(dir -> dir.getFileName().toString().startsWith("DIM"))
                                .map(dir -> dir.resolve("region"))
                                .filter(Files::isDirectory)
                                .forEach(result::add);
                }

                return result;
        }

        private static void writeMarker(Path worldDir, String fileName, String title, int regions, long chunks) throws IOException {
                Path markerDir = worldDir.resolve("legacyworldfix");
                Files.createDirectories(markerDir);
                StringBuilder content = new StringBuilder()
                        .append(title).append('\n')
                        .append("Date: ").append(Instant.now()).append('\n');
                if (regions > 0) {
                        content.append("Region files: ").append(regions).append('\n');
                }
                content.append("Chunks: ").append(chunks).append('\n');
                Files.writeString(markerDir.resolve(fileName), content.toString());
        }

        private static byte[] byteArray(CompoundTag tag, String name, int expectedSize) throws IOException {
                Tag raw = tag.get(name);
                if (raw == null) {
                        return new byte[expectedSize];
                }

                if (!(raw instanceof ByteArrayTag arrayTag) || arrayTag.getAsByteArray().length != expectedSize) {
                        throw new IOException("Unexpected '" + name + "' array in legacy chunk");
                }

                return arrayTag.getAsByteArray();
        }

        static int getNibble(byte[] array, int index) {
                if (array.length == 0) {
                        return 0;
                }

                int value = array[index >> 1] & 0xFF;
                return (index & 1) == 0 ? value & 0x0F : value >> 4;
        }

        static void setNibble(byte[] array, int index, int value) {
                int i = index >> 1;
                if ((index & 1) == 0) {
                        array[i] = (byte) ((array[i] & 0xF0) | (value & 0x0F));
                } else {
                        array[i] = (byte) ((array[i] & 0x0F) | ((value & 0x0F) << 4));
                }
        }
}
