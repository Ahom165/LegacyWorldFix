package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/**
 * Converts Indev / Classic worlds (December 2009 to roughly February 2010).
 * Those saves have no chunks at all: the whole map is one fixed-size block
 * array inside level.dat, under root -&gt; "Map" (root -&gt; "MinecraftLevel" in
 * Classic, whose root name NBT parsing discards anyway):
 *
 * <pre>
 *   Map: {
 *     Width, Length, Height (shorts),
 *     Spawn: [x, y, z] (shorts),
 *     Blocks: byte[Width * Length * Height],   // index = (y * Length + z) * Width + x
 *     Data: byte[... / 2]                      // 4-bit block metadata
 *     Entities: [...], TileEntities: [...]
 *   }
 * </pre>
 *
 * <p>The map is sliced into 16x16 chunks, upgraded with the shared McRegion
 * -&gt; Anvil transformation and written as Anvil region files. The original
 * level.dat is preserved as {@code level.dat.indev.bak} and replaced by a
 * fresh Anvil-era one carrying spawn, time and the map name.
 *
 * <p>Block IDs are transferred as-is: the common IDs (stone, dirt, grass,
 * ores, water, lava, wood, torches, chests...) were already stable in Indev,
 * but a few exotic ones (the eleven colored "cloth" variants, the gear block)
 * no longer exist and simply fall back to air through the vanilla data fixers.
 * Outside of the map bounds the world becomes normally generated terrain.
 */
final class IndevConverter {
        private static final int MAX_HEIGHT = 128;

        private IndevConverter() {
        }

        static boolean isIndevLevel(CompoundTag root) {
                Tag map = root.get("Map");
                if (!(map instanceof CompoundTag mapTag)) {
                        return false;
                }

                Tag blocks = mapTag.get("Blocks");
                return blocks instanceof net.minecraft.nbt.ByteArrayTag || blocks instanceof IntArrayTag;
        }

        static boolean convertWorld(Path worldDir, CompoundTag indevRoot) {
                long start = System.nanoTime();
                LegacyWorldFix.LOGGER.info("[LegacyWorldFix] Indev/Classic world detected (fixed map in level.dat): {}", worldDir);

                try {
                        CompoundTag map = indevRoot.getCompoundOrEmpty("Map");
                        int width = numericInt(map.get("Width"));
                        int length = numericInt(map.get("Length"));
                        int height = numericInt(map.get("Height"));
                        if (width <= 0 || length <= 0 || height <= 0 || width > 1024 || length > 1024 || height > MAX_HEIGHT) {
                                throw new IOException("Implausible map size " + width + "x" + length + "x" + height);
                        }

                        byte[] blocks = blockArray(map);
                        byte[] data = metadataArray(map, width * length * height);
                        ListTag entities = map.getListOrEmpty("Entities");
                        ListTag tileEntities = map.getListOrEmpty("TileEntities");

                        int chunksX = (width + 15) / 16;
                        int chunksZ = (length + 15) / 16;

                        // Slice the map into chunks, one writer per target region file.
                        Map<Path, RegionIo.AnvilRegionWriter> writers = new HashMap<>();
                        Path regionDir = worldDir.resolve("region");
                        Files.createDirectories(regionDir);
                        int timestamp = (int) (System.currentTimeMillis() / 1000L);

                        for (int cx = 0; cx < chunksX; cx++) {
                                for (int cz = 0; cz < chunksZ; cz++) {
                                        CompoundTag chunkRoot = buildChunk(map, blocks, data, width, length, height, cx, cz, entities, tileEntities);
                                        Path regionFile = regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
                                        writers.computeIfAbsent(regionFile, key -> new RegionIo.AnvilRegionWriter())
                                                .writeChunk((cx & 31) + (cz & 31) * 32, timestamp, chunkRoot);
                                }
                        }

                        int chunks = 0;
                        for (Map.Entry<Path, RegionIo.AnvilRegionWriter> entry : writers.entrySet()) {
                                entry.getValue().saveTo(entry.getKey());
                                chunks += entry.getValue().count();
                        }

                        writeModernLevelDat(worldDir, indevRoot, map, width, length, height);

                        writeMarker(worldDir, chunks, width, length, height);
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Sliced {}x{}x{} Indev map into {} chunk(s) across {} region file(s) in {} ms - vanilla upgrade chain can take over now",
                                width,
                                length,
                                height,
                                chunks,
                                writers.size(),
                                (System.nanoTime() - start) / 1_000_000L
                        );
                        return true;
                } catch (Exception e) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Failed to convert Indev/Classic world {}. level.dat was left untouched; fix the cause (or restore a backup) and retry.",
                                worldDir,
                                e
                        );
                        return false;
                }
        }

        /** Builds one region-style chunk from the flat map arrays, then applies the shared McRegion -> Anvil transform. */
        private static CompoundTag buildChunk(
                CompoundTag map,
                byte[] blocks,
                byte[] data,
                int width,
                int length,
                int height,
                int cx,
                int cz,
                ListTag entities,
                ListTag tileEntities
        ) throws IOException {
                int baseX = cx * 16;
                int baseZ = cz * 16;
                byte[] chunkBlocks = new byte[32768];

                for (int z = 0; z < 16; z++) {
                        int gz = baseZ + z;
                        if (gz >= length) {
                                break;
                        }
                        for (int x = 0; x < 16; x++) {
                                int gx = baseX + x;
                                if (gx >= width) {
                                        break;
                                }
                                for (int y = 0; y < height; y++) {
                                        int mapIndex = (y * length + gz) * width + gx;
                                        chunkBlocks[(x << 11) | (z << 7) | y] = blocks[mapIndex];
                                }
                        }
                }

                // HeightMap: topmost non-air block per column (legacy byte encoding).
                byte[] heightMap = new byte[256];
                for (int z = 0; z < 16; z++) {
                        int gz = baseZ + z;
                        for (int x = 0; x < 16; x++) {
                                int gx = baseX + x;
                                if (gz >= length || gx >= width) {
                                        continue;
                                }
                                for (int y = height - 1; y >= 0; y--) {
                                        if (blocks[(y * length + gz) * width + gx] != 0) {
                                                heightMap[z << 4 | x] = (byte) (y + 1);
                                                break;
                                        }
                                }
                        }
                }

                // Block metadata: 4-bit values in the same index order as Blocks.
                byte[] chunkData = new byte[16384];
                for (int z = 0; z < 16; z++) {
                        int gz = baseZ + z;
                        if (gz >= length) {
                                break;
                        }
                        for (int x = 0; x < 16; x++) {
                                int gx = baseX + x;
                                if (gx >= width) {
                                        break;
                                }
                                for (int y = 0; y < height; y++) {
                                        int mapIndex = (y * length + gz) * width + gx;
                                        int value = (mapIndex & 1) == 0 ? data[mapIndex >> 1] & 0x0F : (data[mapIndex >> 1] >> 4) & 0x0F;
                                        int chunkIndex = (x << 11) | (z << 7) | y;
                                        if ((chunkIndex & 1) == 0) {
                                                chunkData[chunkIndex >> 1] = (byte) ((chunkData[chunkIndex >> 1] & 0xF0) | value);
                                        } else {
                                                chunkData[chunkIndex >> 1] = (byte) ((chunkData[chunkIndex >> 1] & 0x0F) | (value << 4));
                                        }
                                }
                        }
                }

                CompoundTag level = new CompoundTag();
                level.putInt("xPos", cx);
                level.putInt("zPos", cz);
                level.putLong("LastUpdate", 0L);
                level.putBoolean("TerrainPopulated", true);
                level.putByteArray("Blocks", chunkBlocks);
                level.putByteArray("Data", chunkData);
                level.putByteArray("BlockLight", new byte[16384]);
                // Full daylight above ground; the modern light engine refines it once the
                // chunk is loaded (light is recalculated, not trusted, for such old worlds).
                level.putByteArray("SkyLight", daylightArray(chunkBlocks));
                level.putByteArray("HeightMap", heightMap);

                ListTag chunkEntities = selectByPosition(entities, baseX, baseZ);
                level.put("Entities", chunkEntities);

                ListTag chunkTileEntities = selectByTilePosition(tileEntities, baseX, baseZ);
                level.put("TileEntities", chunkTileEntities);

                CompoundTag root = new CompoundTag();
                root.put("Level", level);
                return LegacyMcRegionUpgrader.transformChunkNbt(root);
        }

        /** 15 (fully lit) for every air block, 0 elsewhere - a sane starting point the light engine will refine. */
        private static byte[] daylightArray(byte[] chunkBlocks) {
                byte[] skyLight = new byte[16384];
                for (int index = 0; index < 32768; index++) {
                        if (chunkBlocks[index] == 0) {
                                LegacyMcRegionUpgrader.setNibble(skyLight, index, 0x0F);
                        }
                }
                return skyLight;
        }

        /** Copies the entities whose position falls into this chunk. */
        private static ListTag selectByPosition(ListTag entities, int baseX, int baseZ) {
                ListTag result = new ListTag();
                for (int i = 0; i < entities.size(); i++) {
                        if (!(entities.get(i) instanceof CompoundTag entity)) {
                                continue;
                        }

                        ListTag pos = entity.getListOrEmpty("Pos");
                        if (pos.size() < 2) {
                                continue;
                        }
                        double x = ((NumericTag) pos.get(0)).doubleValue();
                        double z = ((NumericTag) pos.get(1)).doubleValue();
                        if (Math.floor(x / 16.0) == baseX / 16 && Math.floor(z / 16.0) == baseZ / 16) {
                                result.add(entity.copy());
                        }
                }
                return result;
        }

        /** Copies the tile entities whose integer coordinates fall into this chunk. */
        private static ListTag selectByTilePosition(ListTag tileEntities, int baseX, int baseZ) {
                ListTag result = new ListTag();
                for (int i = 0; i < tileEntities.size(); i++) {
                        if (!(tileEntities.get(i) instanceof CompoundTag tileEntity)) {
                                continue;
                        }

                        int x = tileEntity.getIntOr("x", Integer.MIN_VALUE);
                        int z = tileEntity.getIntOr("z", Integer.MIN_VALUE);
                        if (x != Integer.MIN_VALUE && x >> 4 == baseX >> 4 && z >> 4 == baseZ >> 4) {
                                result.add(tileEntity.copy());
                        }
                }
                return result;
        }

        private static void writeModernLevelDat(Path worldDir, CompoundTag indevRoot, CompoundTag map, int width, int length, int height) throws IOException {
                Path levelDat = worldDir.resolve("level.dat");

                // Keep the original save around: it is the only copy of the raw Indev data.
                if (Files.isRegularFile(levelDat)) {
                        Files.copy(levelDat, worldDir.resolve("level.dat.indev.bak"), StandardCopyOption.REPLACE_EXISTING);
                }

                CompoundTag about = indevRoot.getCompoundOrEmpty("About");
                CompoundTag environment = indevRoot.getCompoundOrEmpty("Environment");

                CompoundTag data = new CompoundTag();
                data.putInt("version", LegacyMcRegionUpgrader.ANVIL_STORAGE_VERSION);
                String name = about.getStringOr("Name", "");
                data.putString("LevelName", name.isEmpty() ? "Indev level" : name);
                data.putLong("RandomSeed", 0L);
                data.putInt("GameType", 0);
                data.putString("generatorName", "default");
                data.putInt("generatorVersion", 1);
                data.putByte("MapFeatures", (byte) 0);
                data.putBoolean("hardcore", false);

                ListTag spawn = map.getListOrEmpty("Spawn");
                int spawnX = clampSpawn(spawn, 0, width);
                int spawnZ = clampSpawn(spawn, 2, length);
                int spawnY = spawn.size() > 1 ? numericInt(spawn.get(1)) : 64;
                data.putInt("SpawnX", spawnX);
                data.putInt("SpawnY", Math.min(Math.max(spawnY, 0), height));
                data.putInt("SpawnZ", spawnZ);

                data.putLong("Time", environment.contains("TimeOfDay") ? numericLong(environment.get("TimeOfDay")) : 0L);
                data.putLong("LastPlayed", System.currentTimeMillis());
                data.putLong("SizeOnDisk", (long) width * length * height * 2L);

                CompoundTag root = new CompoundTag();
                root.put("Data", data);
                NbtIo.writeCompressed(root, levelDat);
        }

        private static int clampSpawn(ListTag spawn, int index, int max) {
                if (spawn.size() <= index) {
                        return 0;
                }
                return Math.min(Math.max(numericInt(spawn.get(index)), 0), max - 1);
        }

        private static void writeMarker(Path worldDir, int chunks, int width, int length, int height) throws IOException {
                Path markerDir = worldDir.resolve("legacyworldfix");
                Files.createDirectories(markerDir);
                String content = "Converted from Indev/Classic fixed map (level.dat Map compound) to Anvil (version 19133) by LegacyWorldFix\n"
                        + "Date: " + Instant.now() + "\n"
                        + "Map size: " + width + "x" + length + "x" + height + "\n"
                        + "Chunks: " + chunks + "\n"
                        + "Original level.dat preserved as: level.dat.indev.bak\n";
                Files.writeString(markerDir.resolve("converted-from-indev.txt"), content);
        }

        private static byte[] blockArray(CompoundTag map) throws IOException {
                Tag raw = map.get("Blocks");
                if (raw instanceof net.minecraft.nbt.ByteArrayTag byteArrayTag) {
                        return byteArrayTag.getAsByteArray();
                }
                if (raw instanceof IntArrayTag intArrayTag) {
                        // Earliest Classic levels stored the map as an int array.
                        int[] values = intArrayTag.getAsIntArray();
                        byte[] converted = new byte[values.length];
                        for (int i = 0; i < values.length; i++) {
                                converted[i] = (byte) values[i];
                        }
                        return converted;
                }
                throw new IOException("Map has no usable Blocks array");
        }

        private static byte[] metadataArray(CompoundTag map, int blockCount) {
                byte[] raw = map.getByteArray("Data").orElse(new byte[0]);
                int expected = (blockCount + 1) / 2;
                if (raw.length == expected) {
                        return raw;
                }
                if (raw.length == blockCount) {
                        // The very first Classic levels stored one metadata byte per block.
                        byte[] converted = new byte[expected];
                        for (int i = 0; i < blockCount; i++) {
                                int value = raw[i] & 0x0F;
                                if ((i & 1) == 0) {
                                        converted[i >> 1] = (byte) ((converted[i >> 1] & 0xF0) | value);
                                } else {
                                        converted[i >> 1] = (byte) ((converted[i >> 1] & 0x0F) | (value << 4));
                                }
                        }
                        return converted;
                }
                return new byte[expected];
        }

        private static int numericInt(Tag tag) {
                return tag instanceof NumericTag numericTag ? numericTag.intValue() : 0;
        }

        private static long numericLong(Tag tag) {
                return tag instanceof NumericTag numericTag ? numericTag.longValue() : 0L;
        }
}
