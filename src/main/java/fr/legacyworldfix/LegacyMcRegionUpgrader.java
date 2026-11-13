package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/**
 * Re-implementation of the McRegion -> Anvil world conversion that Mojang
 * removed from recent versions (the code that used to live in
 * {@code McRegionUpgrader}).
 *
 * <p>A world saved by Minecraft 1.0 (November 2011) stores its chunks in
 * {@code region/*.mcr} files ("McRegion") and its level.dat says
 * {@code version: 19132}. Since 26.x the game only reads Anvil
 * ({@code version: 19133}) and refuses such worlds with
 * "This world must be opened in an older version (like 1.6.4) to be safely
 * converted". This class performs the conversion in place:
 *
 * <ul>
 *   <li>every {@code r.X.Z.mcr} region file is rewritten as {@code r.X.Z.mca}
 *       (the container format is identical, chunk payloads differ);</li>
 *   <li>each chunk's flat 32768-byte block array (order x,z,y - 128 high) is
 *       split into sixteen 4096-byte sections (order y,z,x);</li>
 *   <li>entity health values written by pre-1.6 versions (short) are
 *       normalized to the float representation introduced in 1.6, which is
 *       what the modern data fixer chain expects;</li>
 *   <li>level.dat (and level.dat_old) get {@code version: 19133}.</li>
 * </ul>
 *
 * <p>The result is byte-for-byte the kind of early-Anvil world that 1.2 to 1.8
 * used to produce, which the 26.x "file fixing" + DataFixerUpper chain (lowest
 * schema 99) is still able to upgrade all the way to the current data version.
 */
public final class LegacyMcRegionUpgrader {
        public static final int MCREGION_STORAGE_VERSION = 19132;
        public static final int ANVIL_STORAGE_VERSION = 19133;

        private static final Map<Path, Object> WORLD_LOCKS = new ConcurrentHashMap<>();

        private LegacyMcRegionUpgrader() {
        }

        /** Cheap check: does this world still use the McRegion storage version? */
        public static boolean isMcRegionWorld(Path worldDir) {
                Path levelDat = worldDir.resolve("level.dat");
                if (!Files.isRegularFile(levelDat)) {
                        return false;
                }

                try {
                        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
                        return root.getCompoundOrEmpty("Data").getIntOr("version", ANVIL_STORAGE_VERSION) == MCREGION_STORAGE_VERSION;
                } catch (IOException | RuntimeException e) {
                        LegacyWorldFix.LOGGER.warn("[LegacyWorldFix] Could not read {}", levelDat, e);
                        return false;
                }
        }

        /**
         * Converts the world in place if (and only if) it still is a McRegion world.
         *
         * @return true when the world can be handed to vanilla afterwards
         */
        public static boolean convertWorld(Path worldDir) {
                Object lock = WORLD_LOCKS.computeIfAbsent(worldDir.toAbsolutePath().normalize(), key -> new Object());
                synchronized (lock) {
                        if (!isMcRegionWorld(worldDir)) {
                                return true;
                        }

                        long start = System.nanoTime();
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] McRegion world detected (level.dat version {}): {}",
                                MCREGION_STORAGE_VERSION,
                                worldDir
                        );

                        try {
                                List<Path> regionDirs = findRegionDirectories(worldDir);
                                int regions = 0;
                                long chunks = 0;

                                for (Path regionDir : regionDirs) {
                                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mcr")) {
                                                for (Path mcr : stream) {
                                                        String fileName = mcr.getFileName().toString();
                                                        Path mca = mcr.resolveSibling(fileName.substring(0, fileName.length() - 4) + ".mca");
                                                        chunks += RegionIo.convertMcRegionToAnvil(mcr, mca, LegacyMcRegionUpgrader::transformChunkNbt);
                                                        Files.delete(mcr);
                                                        regions++;
                                                }
                                        }
                                }

                                // level.dat is rewritten last: if anything above failed, the world is
                                // still an honest McRegion world and the vanilla refusal stays in place.
                                patchLevelDat(worldDir.resolve("level.dat"));
                                Path levelDatOld = worldDir.resolve("level.dat_old");
                                if (Files.isRegularFile(levelDatOld)) {
                                        patchLevelDat(levelDatOld);
                                }

                                writeMarker(worldDir, regions, chunks);
                                LegacyWorldFix.LOGGER.info(
                                        "[LegacyWorldFix] Converted {} region file(s) / {} chunk(s) from McRegion to Anvil in {} ms - vanilla upgrade chain can take over now",
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
        }

        /** Converts every McRegion world directly inside the given directory (server universe / saves folder). */
        public static int convertAllInDirectory(Path directory) {
                if (!Files.isDirectory(directory)) {
                        return 0;
                }

                int converted = 0;
                try (Stream<Path> entries = Files.list(directory)) {
                        for (Path candidate : entries.filter(Files::isDirectory).toList()) {
                                if (isMcRegionWorld(candidate) && convertWorld(candidate)) {
                                        converted++;
                                }
                        }
                } catch (IOException e) {
                        LegacyWorldFix.LOGGER.error("[LegacyWorldFix] Failed to scan {} for McRegion worlds", directory, e);
                }

                return converted;
        }

        /** Transforms one McRegion chunk NBT (root with "Level") into an early-Anvil chunk NBT. */
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

        private static void patchLevelDat(Path levelDat) throws IOException {
                CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("Data");
                data.putInt("version", ANVIL_STORAGE_VERSION);

                if (data.contains("Player")) {
                        CompoundTag player = data.getCompoundOrEmpty("Player");
                        if (player.size() > 0) {
                                fixEntityHealth(player);
                        }
                }

                NbtIo.writeCompressed(root, levelDat);
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

        private static void writeMarker(Path worldDir, int regions, long chunks) throws IOException {
                Path markerDir = worldDir.resolve("legacyworldfix");
                Files.createDirectories(markerDir);
                Path marker = markerDir.resolve("converted-from-mcregion.txt");
                String content = "Converted from McRegion (version 19132) to Anvil (version 19133) by LegacyWorldFix\n"
                        + "Date: " + Instant.now() + "\n"
                        + "Region files: " + regions + "\n"
                        + "Chunks: " + chunks + "\n";
                Files.writeString(marker, content);
        }

        private static byte[] byteArray(CompoundTag tag, String name, int expectedSize) throws IOException {
                Tag raw = tag.get(name);
                if (raw == null) {
                        return new byte[expectedSize];
                }

                if (!(raw instanceof ByteArrayTag arrayTag) || arrayTag.getAsByteArray().length != expectedSize) {
                        throw new IOException("Unexpected '" + name + "' array in McRegion chunk");
                }

                return arrayTag.getAsByteArray();
        }

        private static int getNibble(byte[] array, int index) {
                if (array.length == 0) {
                        return 0;
                }

                int value = array[index >> 1] & 0xFF;
                return (index & 1) == 0 ? value & 0x0F : value >> 4;
        }

        private static void setNibble(byte[] array, int index, int value) {
                int i = index >> 1;
                if ((index & 1) == 0) {
                        array[i] = (byte) ((array[i] & 0xF0) | (value & 0x0F));
                } else {
                        array[i] = (byte) ((array[i] & 0x0F) | ((value & 0x0F) << 4));
                }
        }
}
