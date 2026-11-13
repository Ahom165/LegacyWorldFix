package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

        private static final int PARALLELISM = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

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
         * conversion pool - the vanilla upgrade pass afterwards becomes a fast no-op.
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

                        List<Callable<Long>> tasks = pairs.stream().<Callable<Long>>map(pair -> () -> {
                                // pair = {mcr, mca, dimensionRoot}: each region file belongs to
                                // exactly one dimension, which the DFU context tag needs.
                                String dimensionId = dimensionIdFor(pair[2]);
                                long chunks = RegionIo.convertMcRegionToAnvil(
                                        pair[0],
                                        pair[1],
                                        tag -> transformAndUpgradeChunk(tag, dimensionId, generatorId)
                                );
                                Files.delete(pair[0]);
                                return chunks;
                        }).toList();
                        long chunks = runInParallel(tasks);

                        int regions = pairs.size();

                        // level.dat is rewritten last: if anything above failed, the world is
                        // still an honest McRegion world and the vanilla refusal stays in place.
                        patchLevelDat(worldDir.resolve("level.dat"), worldDir.getFileName().toString());
                        Path levelDatOld = worldDir.resolve("level.dat_old");
                        if (Files.isRegularFile(levelDatOld)) {
                                patchLevelDat(levelDatOld, worldDir.getFileName().toString());
                        }

                        writeMarker(worldDir, "converted-from-mcregion.txt",
                                "Converted from McRegion (version 19132) to Anvil (version 19133) by LegacyWorldFix",
                                regions, chunks);
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Converted {} region file(s) / {} chunk(s) from McRegion to Anvil in {} ms (data fixes applied in place) - vanilla upgrade pass is now a no-op",
                                regions,
                                chunks,
                                (System.nanoTime() - start) / 1_000_000L
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

                        patchLevelDat(worldDir.resolve("level.dat"), worldDir.getFileName().toString());
                        Path levelDatOld = worldDir.resolve("level.dat_old");
                        if (Files.isRegularFile(levelDatOld)) {
                                patchLevelDat(levelDatOld, worldDir.getFileName().toString());
                        }

                        writeMarker(worldDir, "converted-from-alpha.txt",
                                "Converted from pre-region Infdev/Alpha chunk files to Anvil (version 19133) by LegacyWorldFix",
                                0, chunks);
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Converted {} pre-region chunk(s) to Anvil in {} ms (data fixes applied in place) - vanilla upgrade pass is now a no-op",
                                chunks,
                                (System.nanoTime() - start) / 1_000_000L
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
         * Runs independent conversion tasks with a small worker pool. Region files
         * are the unit of work: they never share state, so parallelism is safe and
         * scales with cores on big worlds (the historical code was strictly
         * sequential).
         */
        static long runInParallel(List<Callable<Long>> tasks) throws IOException {
                if (tasks.isEmpty()) {
                        return 0L;
                }
                if (tasks.size() == 1 || PARALLELISM == 1) {
                        long total = 0L;
                        for (Callable<Long> task : tasks) {
                                try {
                                        total += task.call();
                                } catch (IOException e) {
                                        throw e;
                                } catch (Exception e) {
                                        throw new IOException(e);
                                }
                        }
                        return total;
                }

                ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, runnable -> {
                        Thread thread = new Thread(runnable, "LegacyWorldFix-Converter");
                        thread.setDaemon(true);
                        return thread;
                });
                try {
                        long total = 0L;
                        try {
                                for (Future<Long> future : pool.invokeAll(tasks)) {
                                        try {
                                                total += future.get();
                                        } catch (ExecutionException e) {
                                                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                                        }
                                }
                        } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted during conversion", e);
                        }
                        return total;
                } finally {
                        pool.shutdown();
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
         */
        static CompoundTag transformChunkNbt(CompoundTag oldRoot) throws IOException {
                CompoundTag oldLevel = oldRoot.getCompoundOrEmpty("Level");
                byte[] blocks = byteArray(oldLevel, "Blocks", 32768);
                byte[] data = byteArray(oldLevel, "Data", 16384);
                byte[] skyLight = byteArray(oldLevel, "SkyLight", 16384);
                byte[] blockLight = byteArray(oldLevel, "BlockLight", 16384);

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

                // 16x16x128 flat storage -> 8 sections of 16x16x16.
                ListTag sections = new ListTag();
                for (int sectionY = 0; sectionY < 8; sectionY++) {
                        byte[] sectionBlocks = new byte[4096];
                        byte[] sectionData = new byte[2048];
                        byte[] sectionSkyLight = new byte[2048];
                        byte[] sectionBlockLight = new byte[2048];

                        for (int yLocal = 0; yLocal < 16; yLocal++) {
                                int y = sectionY * 16 + yLocal;
                                for (int z = 0; z < 16; z++) {
                                        for (int x = 0; x < 16; x++) {
                                                int oldIndex = (x << 11) | (z << 7) | y;
                                                int newIndex = (yLocal << 8) | (z << 4) | x;

                                                sectionBlocks[newIndex] = blocks[oldIndex];
                                                setNibble(sectionData, newIndex, getNibble(data, oldIndex));
                                                setNibble(sectionSkyLight, newIndex, getNibble(skyLight, oldIndex));
                                                setNibble(sectionBlockLight, newIndex, getNibble(blockLight, oldIndex));
                                        }
                                }
                        }

                        CompoundTag section = new CompoundTag();
                        section.putByte("Y", (byte) sectionY);
                        section.putByteArray("Blocks", sectionBlocks);
                        section.putByteArray("Data", sectionData);
                        section.putByteArray("SkyLight", sectionSkyLight);
                        section.putByteArray("BlockLight", sectionBlockLight);
                        sections.add(sections.size(), section);
                }
                level.put("Sections", sections);

                // Entities: normalize pre-1.6 health (short) to the float type introduced in 1.6.
                ListTag entities = oldLevel.getListOrEmpty("Entities");
                for (int i = 0; i < entities.size(); i++) {
                        if (entities.get(i) instanceof CompoundTag entityTag) {
                                fixEntityHealth(entityTag);
                        }
                }
                level.put("Entities", entities);

                Tag tileEntities = oldLevel.get("TileEntities");
                if (tileEntities != null) {
                        level.put("TileEntities", tileEntities.copy());
                }

                CompoundTag newRoot = new CompoundTag();
                newRoot.put("Level", level);
                return newRoot;
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

        private static void patchLevelDat(Path levelDat, String defaultLevelName) throws IOException {
                CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("Data");
                data.putInt("version", ANVIL_STORAGE_VERSION);

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
