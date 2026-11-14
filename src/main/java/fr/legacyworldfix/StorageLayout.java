package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * World storage layout of the running game build, and the moves between the
 * two layouts 26.x builds have used for Anvil worlds.
 *
 * <p>Early 26.x builds read chunks from {@code <world>/region} for the
 * overworld and {@code <world>/DIM-1/region}, {@code <world>/DIM1/region} for
 * the Nether and the End - the historical layout this mod's conversions have
 * always written. Newer 26.x builds keep {@code level.dat} at the world root
 * but moved every dimension under
 * {@code <world>/dimensions/minecraft/<dimension>/} ({@code region/},
 * {@code entities/} and {@code data/} inside), and ship
 * {@code DimensionStorageFileFix}, whose whole job is to move old-layout
 * folders into the per-dimension layout during the vanilla upgrade pass
 * ("Cannot move file from /region to dimensions/minecraft/overworld/region").
 *
 * <p>There is no version-number signal for the switch: both builds share the
 * "26.3" id and data version 5023, so the layout is detected by the presence
 * of that file-fix class on the classpath - the game only carries the
 * migration into the per-dimension layout when it also reads it. A world whose
 * level.dat is stamped with the current DataVersion skips the vanilla upgrade
 * pass entirely, so the mod must place the converted chunks in the layout the
 * running build reads itself, and migrate worlds an older mod/game pair
 * already converted into the old layout.
 */
final class StorageLayout {

        private static final boolean PER_DIMENSION = detectPerDimensionLayout();

        private StorageLayout() {
        }

        /** True when the running game reads chunks from {@code dimensions/minecraft/<dimension>/}. */
        static boolean perDimension() {
                return PER_DIMENSION;
        }

        private static boolean detectPerDimensionLayout() {
                try {
                        Class.forName("net.minecraft.util.filefix.fixes.DimensionStorageFileFix");
                        return true;
                } catch (ClassNotFoundException | LinkageError e) {
                        return false;
                }
        }

        /**
         * Folder where the running game reads the chunk region files of a legacy
         * dimension root (the world directory itself, or a {@code DIM*} folder).
         * Unknown mod dimensions keep their own folder: no build reads them, so
         * converting them in place stays the honest, harmless choice.
         */
        static Path regionDir(Path dimensionRoot) {
                if (!PER_DIMENSION) {
                        return dimensionRoot.resolve("region");
                }
                return perDimensionFolder(dimensionRoot).resolve("region");
        }

        /** {@code <world>/dimensions/minecraft/<dimension>} for the dimensions vanilla knows. */
        private static Path perDimensionFolder(Path dimensionRoot) {
                String name = dimensionRoot.getFileName().toString();
                // DIM* folders are direct children of the world directory; the
                // per-dimension layout hangs off the world directory itself, so
                // DIM-1 does not become DIM-1/dimensions/minecraft/the_nether.
                Path worldDir = name.startsWith("DIM") ? dimensionRoot.getParent() : dimensionRoot;
                if (name.equals("DIM-1")) {
                        return worldDir.resolve("dimensions").resolve("minecraft").resolve("the_nether");
                }
                if (name.equals("DIM1")) {
                        return worldDir.resolve("dimensions").resolve("minecraft").resolve("the_end");
                }
                if (name.startsWith("DIM")) {
                        // Not a vanilla dimension id: keep the files where they are.
                        return dimensionRoot;
                }
                return worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld");
        }

        /** Folder where the running game reads player files ({@code <uuid>.dat}). */
        static Path playerDataDir(Path worldDir) {
                if (!PER_DIMENSION) {
                        return worldDir.resolve("players").resolve("data");
                }
                return worldDir.resolve("playerdata");
        }

        /**
         * Moves a world an older mod/game pair already converted (old layout,
         * level.dat stamped) into the per-dimension layout, mirroring exactly
         * what vanilla's own {@code DimensionStorageFileFix} would move - but
         * deterministically, without depending on the vanilla upgrade pass that
         * the DataVersion stamps skip. Idempotent: every move only happens when
         * its source exists, carries region files and its target does not exist
         * yet (a target that already exists means the game itself has been
         * running in the new layout, and its data wins).
         *
         * @return true when at least one folder was moved
         */
        static boolean migrateToPerDimensionLayoutIfNeeded(Path worldDir) throws IOException {
                if (!PER_DIMENSION) {
                        return false;
                }
                if (!isConvertedAnvilWorld(worldDir)) {
                        return false;
                }

                boolean moved = false;
                moved |= moveRegionDir(worldDir.resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"));
                moved |= moveRegionDir(worldDir.resolve("DIM-1").resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("region"));
                moved |= moveRegionDir(worldDir.resolve("DIM1").resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region"));
                // PlayerStorageFileFix moves the same way on this layout.
                moved |= moveFolder(worldDir.resolve("players").resolve("data"), worldDir.resolve("playerdata"));
                moved |= moveFolder(worldDir.resolve("players").resolve("advancements"), worldDir.resolve("advancements"));
                moved |= moveFolder(worldDir.resolve("players").resolve("stats"), worldDir.resolve("stats"));

                if (moved) {
                        Path markerDir = worldDir.resolve("legacyworldfix");
                        Files.createDirectories(markerDir);
                        Files.writeString(markerDir.resolve("migrated-to-dimension-layout.txt"),
                                "Chunk/player folders moved into the per-dimension layout (dimensions/minecraft/<dimension>) by LegacyWorldFix\n"
                                + "Date: " + java.time.Instant.now() + "\n");
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] World {} uses the current per-dimension storage layout - converted folders moved accordingly",
                                worldDir);
                }
                return moved;
        }

        /**
         * A world this mod (or vanilla) already brought to Anvil: level.dat says
         * 19133 or later. Legacy worlds are handled by the conversion itself and
         * must never be folder-migrated (their region folders hold .mcr files
         * the game must not see as .mca).
         */
        private static boolean isConvertedAnvilWorld(Path worldDir) throws IOException {
                Path levelDat = worldDir.resolve("level.dat");
                if (!Files.isRegularFile(levelDat)) {
                        return false;
                }
                try {
                        CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(levelDat, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        return root.getCompoundOrEmpty("Data").getIntOr("version", -1) >= LegacyMcRegionUpgrader.ANVIL_STORAGE_VERSION;
                } catch (IOException | RuntimeException e) {
                        return false;
                }
        }

        private static boolean moveRegionDir(Path source, Path target) throws IOException {
                if (!hasRegionFiles(source, "*.mca")) {
                        return false;
                }
                return moveFolder(source, target);
        }

        private static boolean hasRegionFiles(Path dir, String glob) throws IOException {
                if (!Files.isDirectory(dir)) {
                        return false;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
                        return files.iterator().hasNext();
                }
        }

        private static boolean moveFolder(Path source, Path target) throws IOException {
                if (!Files.isDirectory(source) || Files.exists(target)) {
                        return false;
                }
                Files.createDirectories(target.getParent());
                Files.move(source, target);
                return true;
        }

        /** Human-readable summary for the conversion logs and the marker files. */
        static String describe() {
                return PER_DIMENSION ? "per-dimension (dimensions/minecraft/<dimension>)" : "legacy (region/, DIM-1/)";
        }

        /** Every region folder a converted world may have produced, for cleanup checks. */
        static List<Path> possibleRegionDirs(Path worldDir) {
                List<Path> dirs = new ArrayList<>();
                dirs.add(regionDir(worldDir));
                dirs.add(regionDir(worldDir.resolve("DIM-1")));
                dirs.add(regionDir(worldDir.resolve("DIM1")));
                return dirs;
        }
}
