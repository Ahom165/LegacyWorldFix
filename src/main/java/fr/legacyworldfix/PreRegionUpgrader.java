package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;

/**
 * Converts worlds saved *before* the region format (Infdev and Alpha, roughly
 * June 2010 to Beta 1.2 / February 2011). Such a world has no {@code region/}
 * folder: every chunk is one gzipped NBT file named {@code c.<x>.<z>.dat}
 * (base36 coordinates, signed) inside two levels of base36 folders.
 *
 * <p>Real chunk files from that era wrap every field in a {@code Level}
 * compound (root -&gt; Level -&gt; xPos/Blocks/Data/...); files carrying the fields
 * directly at the root are also accepted, so both shapes convert.
 *
 * <p>Each chunk is upgraded with the exact same McRegion -&gt; Anvil
 * transformation used for {@code .mcr} payloads (flat 16x16x128 arrays split
 * into 16 sections, entity health normalization) and then pushed through the
 * vanilla datafixer chain, so every chunk lands on disk already at the
 * current data version - one pass, no intermediate McRegion files, and thanks to
 * the DataVersion stamps on chunks and level.dat the vanilla world-upgrade pass
 * afterwards is skipped entirely. The Nether
 * exists since Alpha 1.2 (Halloween update) and is stored the same way
 * inside {@code DIM-1}; every {@code DIM*} folder is converted alongside the
 * overworld, with its real dimension id feeding the datafixer context.
 */
final class PreRegionUpgrader {
        record LegacyChunk(int x, int z, Path file, Path dimensionRoot) {
        }

        private PreRegionUpgrader() {
        }

        /** Detects the pre-region storage layout: base36 folders containing c.&lt;x&gt;.&lt;z&gt;.dat files. */
        static boolean hasLegacyChunkFiles(Path worldDir) {
                return !findChunkFiles(worldDir).isEmpty();
        }

        static List<LegacyChunk> findChunkFiles(Path worldDir) {
                List<LegacyChunk> chunks = new ArrayList<>();
                for (Path dimensionRoot : dimensionRoots(worldDir)) {
                        chunks.addAll(findChunkFilesIn(dimensionRoot));
                }

                return chunks;
        }
        /**
         * The overworld chunk root plus every {@code DIM*} folder. The Nether
         * ({@code DIM-1}) exists since Alpha 1.2, so real Beta 1.2 saves can carry
         * pre-region chunk files in there too. The returned paths are direct
         * children of {@code worldDir}.
         */
        private static List<Path> dimensionRoots(Path worldDir) {
                List<Path> roots = new ArrayList<>();
                roots.add(worldDir);
                try (Stream<Path> entries = Files.list(worldDir)) {
                        entries.filter(Files::isDirectory)
                                .filter(dir -> dir.getFileName().toString().startsWith("DIM"))
                                .forEach(roots::add);
                } catch (IOException e) {
                        LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Failed to list {} for pre-region dimension folders: {}", worldDir, e.toString());
                }

                return roots;
        }

        /** Alpha/Infdev layout inside one dimension: &lt;root&gt;/&lt;b36 x &amp; 63&gt;/&lt;b36 z &amp; 63&gt;/c.&lt;b36 x&gt;.&lt;b36 z&gt;.dat */
        private static List<LegacyChunk> findChunkFilesIn(Path dimensionRoot) {
                List<LegacyChunk> chunks = new ArrayList<>();
                try (var entries = Files.newDirectoryStream(dimensionRoot)) {
                        for (Path folder : entries) {
                                String folderName = folder.getFileName().toString();
                                if (!isBase36FolderName(folderName) || !Files.isDirectory(folder)) {
                                        continue;
                                }

                                try (var subFolders = Files.newDirectoryStream(folder)) {
                                        for (Path subFolder : subFolders) {
                                                if (!Files.isDirectory(subFolder)) {
                                                        continue;
                                                }

                                                try (DirectoryStream<Path> files = Files.newDirectoryStream(subFolder, "c.*.*.dat")) {
                                                        for (Path file : files) {
                                                                LegacyChunk chunk = parseChunkFileName(file, dimensionRoot);
                                                                if (chunk != null) {
                                                                        chunks.add(chunk);
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }
                } catch (IOException e) {
                        LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Failed to scan {} for pre-region chunk files: {}", dimensionRoot, e.toString());
                }

                return chunks;
        }

        /**
         * One region file being assembled, shared by all the per-chunk tasks that
         * target it. Chunks are decompressed, transformed, DFU-upgraded and
         * re-compressed in parallel; only the cheap in-memory append runs under the
         * region's lock, and the file itself is written once at the end.
         */
        private record RegionTarget(Path file, RegionIo.AnvilRegionWriter writer, List<Path> convertedFiles) {
                RegionTarget(Path file) {
                        this(file, new RegionIo.AnvilRegionWriter(), Collections.synchronizedList(new ArrayList<>()));
                }
        }

        /**
         * Converts every legacy chunk file into Anvil region files under
         * {@code region/}. Work is parallelized per chunk: a dense Alpha map touches
         * only a handful of region files, so per-region granularity would leave
         * cores idle - the expensive part (gzip read + shape transform + vanilla
         * datafixers) must run one chunk at a time across the pool.
         *
         * @return the number of chunks converted
         */
        static long convertToAnvil(Path worldDir, String generatorId) throws IOException {
                List<LegacyChunk> chunks = findChunkFiles(worldDir);
                if (chunks.isEmpty()) {
                        return 0L;
                }

                // Live progress: one line every ~10% so long conversions show their
                // actual rate on the user's machine (and make parallelism visible).
                // The same counter feeds the client launch screen.
                int total = chunks.size();
                LegacyMcRegionUpgrader.beginChunkProgress(total);
                int step = Math.max(1, total / 10);
                long startNanos = System.nanoTime();

                Map<Path, RegionTarget> targets = new ConcurrentHashMap<>();
                List<Callable<Long>> tasks = chunks.stream().<Callable<Long>>map(chunk -> () -> {
                        CompoundTag root;
                        try {
                                root = RegionIo.readGzippedNbt(chunk.file());
                        } catch (IOException e) {
                                LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping unreadable pre-region chunk {}: {}", chunk.file(), e.toString());
                                return 0L;
                        }

                        CompoundTag transformed;
                        try {
                                transformed = transform(root, chunk, generatorId);
                        } catch (Exception e) {
                                LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping corrupt pre-region chunk {}: {}", chunk.file(), e.toString());
                                return 0L;
                        }

                        Path regionFile = StorageLayout.regionDir(chunk.dimensionRoot())
                                .resolve("r." + (chunk.x() >> 5) + "." + (chunk.z() >> 5) + ".mca");
                        RegionTarget target = targets.computeIfAbsent(regionFile, RegionTarget::new);
                        byte[] compressed = RegionIo.zlibNbt(transformed);
                        synchronized (target) {
                                target.writer().writeCompressedChunk(slotOf(chunk.x(), chunk.z()), timestampOf(chunk.file()), compressed);
                                target.convertedFiles().add(chunk.file());
                        }
                        int finished = LegacyMcRegionUpgrader.chunkProgressDone();
                        if (finished % step == 0 || finished == total) {
                                long ms = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                                LegacyWorldFix.LOGGER.info("[LegacyWorldFix] Converting pre-region chunks: {}/{} (~{} chunks/s)",
                                        finished, total, finished * 1000L / ms);
                        }
                        return 1L;
                }).toList();

                LegacyMcRegionUpgrader.runInParallel(tasks);

                // Region files are saved and their source chunk files deleted in
                // parallel, one task per region: the writers are independent and the
                // deletes are pure filesystem work (hundreds of small files on
                // Windows NTFS add up). Every chunk of a region is already in the
                // writer's memory at this point, so nothing races. A failure in one
                // task rethrows after all tasks finished, leaving the world an honest
                // pre-region world (level.dat untouched) whose retry converts the
                // remaining files; saves that succeeded keep their regions.
                LegacyMcRegionUpgrader.beginWriteProgress();
                List<Callable<Long>> writes = targets.values().stream()
                        .<Callable<Long>>map(target -> () -> {
                                target.writer().saveTo(target.file());
                                deleteLegacyChunkFiles(target.convertedFiles());
                                return (long) target.writer().count();
                        })
                        .toList();
                return LegacyMcRegionUpgrader.runInParallel(writes);
        }

        /**
         * Removes the successfully converted {@code c.<x>.<z>.dat} files and
         * prunes the empty base36 folders around them (up to the dimension root),
         * so the save does not keep a stale pre-region tree next to the fresh
         * region files. Unreadable or corrupt chunk files are deliberately kept:
         * they still carry data the conversion could not take.
         */
        private static void deleteLegacyChunkFiles(List<Path> files) {
                for (Path file : files) {
                        try {
                                Files.delete(file);
                        } catch (IOException e) {
                                LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Could not remove converted pre-region chunk file {}: {}", file, e.toString());
                                continue;
                        }

                        Path dir = file.getParent();
                        for (int depth = 0; depth < 2 && dir != null && isEmptyDirectory(dir) && isBase36FolderName(dir.getFileName().toString()); depth++) {
                                Path parent = dir.getParent();
                                try {
                                        Files.deleteIfExists(dir);
                                } catch (IOException e) {
                                        break;
                                }
                                dir = parent;
                        }
                }
        }

        private static boolean isEmptyDirectory(Path dir) {
                try (var entries = Files.newDirectoryStream(dir)) {
                        return !entries.iterator().hasNext();
                } catch (IOException e) {
                        return false;
                }
        }

        /**
         * Normalizes a pre-region chunk NBT into the region-style layout and runs
         * the shared McRegion -&gt; Anvil transformation.
         *
         * <p>Real Infdev/Alpha/Beta 1.2 chunk files wrap everything in a
         * {@code Level} compound (root -&gt; Level -&gt; xPos/Blocks/...); accepting that
         * shape is what makes actual 2010/early-2011 saves convert. Files that
         * carry the fields directly at the root are handled as well.
         */
        static CompoundTag transform(CompoundTag alphaRoot, LegacyChunk chunk, String generatorId) throws IOException {
                CompoundTag wrapped = alphaRoot.getCompoundOrEmpty("Level");
                CompoundTag level = wrapped.isEmpty() ? alphaRoot : wrapped;

                if (!level.contains("xPos")) {
                        level.putInt("xPos", chunk.x());
                }
                if (!level.contains("zPos")) {
                        level.putInt("zPos", chunk.z());
                }
                if (!level.contains("LastUpdate")) {
                        level.putLong("LastUpdate", 0L);
                }
                // Every chunk that reached disk in Infdev/Alpha was fully generated;
                // without this flag vanilla would try to repopulate built chunks.
                if (!level.contains("TerrainPopulated")) {
                        level.putBoolean("TerrainPopulated", true);
                }
                // Very early chunks may predate the heightmap tag: derive it from the
                // block array. The old light arrays are intentionally NOT filled in -
                // the transformation drops light entirely (the vanilla upgrade chain
                // deletes it too, and the modern light engine rebuilds it from the
                // block data on load).
                fillIfMissing(level, "Data", 16384, (byte) 0);
                if (!level.contains("HeightMap")) {
                        level.putByteArray("HeightMap", computeHeightMap(level));
                }

                // A chunk whose block array is missing or malformed cannot be converted
                // honestly; skipping it lets vanilla regenerate that chunk instead of
                // writing a silent all-air chunk over the map.
                int blockBytes = level.getByteArray("Blocks").map(array -> array.length).orElse(0);
                if (blockBytes != 32768) {
                        throw new IOException("chunk has no valid 32768-byte Blocks array (found " + blockBytes + " bytes)");
                }

                CompoundTag root = new CompoundTag();
                root.put("Level", level);
                return LegacyMcRegionUpgrader.transformAndUpgradeChunk(
                        root,
                        LegacyMcRegionUpgrader.dimensionIdFor(chunk.dimensionRoot()),
                        generatorId
                );
        }

        private static void fillIfMissing(CompoundTag level, String name, int size, byte fill) {
                byte[] existing = level.getByteArray(name).orElse(new byte[0]);
                if (existing.length == size) {
                        return;
                }

                byte[] array = new byte[size];
                if (fill != 0) {
                        java.util.Arrays.fill(array, fill);
                }
                level.putByteArray(name, array);
        }

        private static byte[] computeHeightMap(CompoundTag level) {
                byte[] blocks = level.getByteArray("Blocks").orElse(new byte[0]);
                byte[] heightMap = new byte[256];
                if (blocks.length != 32768) {
                        return heightMap;
                }

                for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                                for (int y = 127; y >= 0; y--) {
                                        if (blocks[(x << 11) | (z << 7) | y] != 0) {
                                                heightMap[z << 4 | x] = (byte) (y + 1);
                                                break;
                                        }
                                }
                        }
                }
                return heightMap;
        }

        private static int slotOf(int cx, int cz) {
                return (cx & 31) + (cz & 31) * 32;
        }

        private static int timestampOf(Path file) {
                try {
                        return (int) (Files.getLastModifiedTime(file).toMillis() / 1000L);
                } catch (IOException e) {
                        return (int) (System.currentTimeMillis() / 1000L);
                }
        }

        private static LegacyChunk parseChunkFileName(Path file, Path dimensionRoot) {
                String name = file.getFileName().toString();
                // c.<base36 x>.<base36 z>.dat - coordinates may be negative ("-1a").
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                if (parts.length != 2) {
                        return null;
                }

                try {
                        int x = Integer.parseInt(parts[0].toLowerCase(java.util.Locale.ROOT), 36);
                        int z = Integer.parseInt(parts[1].toLowerCase(java.util.Locale.ROOT), 36);
                        return new LegacyChunk(x, z, file, dimensionRoot);
                } catch (NumberFormatException e) {
                        return null;
                }
        }

        private static boolean isBase36FolderName(String name) {
                int length = name.length();
                if (length < 1 || length > 2) {
                        return false;
                }

                for (int i = 0; i < length; i++) {
                        char c = name.charAt(i);
                        boolean valid = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
                        if (!valid) {
                                return false;
                        }
                }
                return true;
        }
}
