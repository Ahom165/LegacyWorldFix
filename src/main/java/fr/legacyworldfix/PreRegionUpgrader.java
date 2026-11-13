package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Converts worlds saved *before* the region format (Infdev and Alpha, roughly
 * June 2010 to Beta 1.2 / February 2011). Such a world has no {@code region/}
 * folder: every chunk is one gzipped NBT file named {@code c.<x>.<z>.dat}
 * (base36 coordinates, signed) inside two levels of base36 folders.
 *
 * <p>Each chunk is upgraded with the exact same McRegion -&gt; Anvil
 * transformation used for {@code .mcr} payloads (flat 16x16x128 arrays split
 * into 16 sections, entity health normalization) and written directly into
 * fresh Anvil {@code .mca} region files - one pass, no intermediate McRegion
 * files. Only the overworld exists at this point in history; there are no
 * {@code DIM-1}/{@code DIM1} folders to handle.
 */
final class PreRegionUpgrader {
        record LegacyChunk(int x, int z, Path file) {
        }

        private PreRegionUpgrader() {
        }

        /** Detects the pre-region storage layout: base36 folders containing c.&lt;x&gt;.&lt;z&gt;.dat files. */
        static boolean hasLegacyChunkFiles(Path worldDir) {
                return !findChunkFiles(worldDir).isEmpty();
        }

        static List<LegacyChunk> findChunkFiles(Path worldDir) {
                List<LegacyChunk> chunks = new ArrayList<>();
                // Alpha/Infdev layout: world/<b36 x & 63>/<b36 z & 63>/c.<b36 x>.<b36 z>.dat
                try (var entries = Files.newDirectoryStream(worldDir)) {
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
                                                                LegacyChunk chunk = parseChunkFileName(file);
                                                                if (chunk != null) {
                                                                        chunks.add(chunk);
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }
                } catch (IOException e) {
                        LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Failed to scan {} for pre-region chunk files: {}", worldDir, e.toString());
                }

                return chunks;
        }

        /**
         * Converts every legacy chunk file into Anvil region files under
         * {@code region/}. Work is parallelized per region file.
         *
         * @return the number of chunks converted
         */
        static long convertToAnvil(Path worldDir) throws IOException {
                List<LegacyChunk> chunks = findChunkFiles(worldDir);
                if (chunks.isEmpty()) {
                        return 0L;
                }

                // Group by target region file: each group becomes one independent task.
                Map<Path, List<LegacyChunk>> byRegion = new HashMap<>();
                for (LegacyChunk chunk : chunks) {
                        Path regionFile = worldDir.resolve("region")
                                .resolve("r." + (chunk.x() >> 5) + "." + (chunk.z() >> 5) + ".mca");
                        byRegion.computeIfAbsent(regionFile, key -> new ArrayList<>()).add(chunk);
                }

                Path regionDir = worldDir.resolve("region");
                Files.createDirectories(regionDir);

                List<Callable<Long>> tasks = new ArrayList<>();
                for (List<LegacyChunk> group : byRegion.values()) {
                        tasks.add(() -> convertRegionGroup(worldDir, group));
                }

                return LegacyMcRegionUpgrader.runInParallel(tasks);
        }

        private static long convertRegionGroup(Path worldDir, List<LegacyChunk> group) throws IOException {
                RegionIo.AnvilRegionWriter writer = new RegionIo.AnvilRegionWriter();
                for (LegacyChunk chunk : group) {
                        CompoundTag root;
                        try {
                                root = RegionIo.readGzippedNbt(chunk.file());
                        } catch (IOException e) {
                                LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping unreadable pre-region chunk {}: {}", chunk.file(), e.toString());
                                continue;
                        }

                        try {
                                writer.writeChunk(slotOf(chunk.x(), chunk.z()), timestampOf(chunk.file()), transform(root, chunk));
                        } catch (Exception e) {
                                LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Skipping corrupt pre-region chunk {}: {}", chunk.file(), e.toString());
                        }
                }

                Path regionFile = worldDir.resolve("region")
                        .resolve("r." + (group.getFirst().x() >> 5) + "." + (group.getFirst().z() >> 5) + ".mca");
                writer.saveTo(regionFile);
                return writer.count();
        }

        /**
         * Wraps an alpha-style chunk NBT (all tags at the root) into the
         * region-style layout ({@code Level} compound) with the same leniency the
         * game's own historical converter had, then runs the shared McRegion -&gt;
         * Anvil transformation.
         */
        static CompoundTag transform(CompoundTag alphaRoot, LegacyChunk chunk) throws IOException {
                CompoundTag level = new CompoundTag();
                for (String key : alphaRoot.keySet()) {
                        Tag value = alphaRoot.get(key);
                        if (value != null) {
                                level.put(key, value);
                        }
                }

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
                // Very early chunks may predate the light/heightmap tags. Missing light
                // cannot be reconstructed here, so assume full daylight (the modern light
                // engine re-checks it) and derive the heightmap from the block array.
                fillIfMissing(level, "Data", 16384, (byte) 0);
                fillIfMissing(level, "BlockLight", 16384, (byte) 0);
                fillIfMissing(level, "SkyLight", 16384, (byte) 0xFF);
                if (!level.contains("HeightMap")) {
                        level.putByteArray("HeightMap", computeHeightMap(level));
                }

                CompoundTag root = new CompoundTag();
                root.put("Level", level);
                return LegacyMcRegionUpgrader.transformChunkNbt(root);
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

        private static LegacyChunk parseChunkFileName(Path file) {
                String name = file.getFileName().toString();
                // c.<base36 x>.<base36 z>.dat - coordinates may be negative ("-1a").
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                if (parts.length != 2) {
                        return null;
                }

                try {
                        int x = Integer.parseInt(parts[0].toLowerCase(java.util.Locale.ROOT), 36);
                        int z = Integer.parseInt(parts[1].toLowerCase(java.util.Locale.ROOT), 36);
                        return new LegacyChunk(x, z, file);
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
