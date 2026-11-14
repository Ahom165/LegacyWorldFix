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

        /**
         * Folder where the running game reads player files ({@code <uuid>.dat}).
         * Resolved from the running build's own {@code LevelResource.PLAYER_DATA_DIR}
         * constant - the game moved this folder more than once across releases
         * ({@code playerdata} -> {@code players/data} in 1.21.5) and both spots have
         * coexisted with the per-dimension chunk layout, so the two layouts must
         * never be coupled: this build reads {@code players/data} while still
         * reading chunks from {@code dimensions/minecraft/<dimension>/}.
         */
        static Path playerDataDir(Path worldDir) {
                return worldDir.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id());
        }

        /**
         * Moves a world an older mod/game pair already converted into the
         * layout this build reads, mirroring exactly what vanilla's own file
         * fixes would move - but deterministically, without depending on the
         * vanilla upgrade pass that the DataVersion stamps skip:
         *
         * <ul>
         *   <li>chunks into the per-dimension layout, like {@code
         *       DimensionStorageFileFix} does on builds that read it;</li>
         *   <li>player folders into the {@code players/...} layout, like
         *       {@code PlayerStorageFileFix} does ({@code playerdata} -&gt;
         *       {@code players/data}, plus advancements/stats - mod 1.8.0 and
         *       older wrote extracted players to {@code playerdata}, which
         *       this build ignores entirely).</li>
         * </ul>
         *
         * <p>Idempotent: every move only happens when its source exists and
         * its target does not (a target that already exists means the game
         * itself has been writing there, and its data wins). A folder move
         * falls back to per-file moves when the target already exists.
         *
         * @return true when at least one folder was moved
         */
        static boolean migrateToPerDimensionLayoutIfNeeded(Path worldDir) throws IOException {
                if (!isConvertedAnvilWorld(worldDir)) {
                        return false;
                }

                boolean moved = false;
                if (PER_DIMENSION) {
                        moved |= moveRegionDir(worldDir.resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"));
                        moved |= moveRegionDir(worldDir.resolve("DIM-1").resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("region"));
                        moved |= moveRegionDir(worldDir.resolve("DIM1").resolve("region"), worldDir.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region"));
                }
                // PlayerStorageFileFix direction: playerdata -> players/data.
                moved |= moveIntoPlayerFolder(worldDir.resolve("playerdata"), playerDataDir(worldDir));
                moved |= moveIntoPlayerFolder(worldDir.resolve("advancements"), worldDir.resolve("players").resolve("advancements"));
                moved |= moveIntoPlayerFolder(worldDir.resolve("stats"), worldDir.resolve("players").resolve("stats"));

                if (moved) {
                        Path markerDir = worldDir.resolve("legacyworldfix");
                        Files.createDirectories(markerDir);
                        Files.writeString(markerDir.resolve("migrated-storage-layout.txt"),
                                "Chunk/player folders moved into the layout the running build reads by LegacyWorldFix\n"
                                + "Date: " + java.time.Instant.now() + "\n");
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] World {} uses a previous storage layout - folders moved accordingly",
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

        /**
         * Read-only counterpart of {@link #migrateToPerDimensionLayoutIfNeeded}:
         * true when that method would move at least one folder. Lets the launch
         * flow decide whether the migration must run before the world opens,
         * without writing anything.
         */
        static boolean needsPerDimensionMigration(Path worldDir) {
                try {
                        if (!isConvertedAnvilWorld(worldDir)) {
                                return false;
                        }
                        if (PER_DIMENSION) {
                                if (hasRegionFiles(worldDir.resolve("region"), "*.mca")
                                        && !Files.exists(worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"))) {
                                        return true;
                                }
                                if (hasRegionFiles(worldDir.resolve("DIM-1").resolve("region"), "*.mca")
                                        && !Files.exists(worldDir.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("region"))) {
                                        return true;
                                }
                                if (hasRegionFiles(worldDir.resolve("DIM1").resolve("region"), "*.mca")
                                        && !Files.exists(worldDir.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region"))) {
                                        return true;
                                }
                        }
                        // PlayerStorageFileFix direction: a playerdata folder this
                        // build no longer reads means the extracted players are invisible.
                        return Files.isDirectory(worldDir.resolve("playerdata"))
                                && !Files.exists(playerDataDir(worldDir));
                } catch (IOException e) {
                        return false;
                }
        }

        /**
         * Moves every file of a legacy player folder into its target under
         * {@code players/} - the whole-folder move first, per-file moves when
         * the target already exists (game-written files there win).
         */
        private static boolean moveIntoPlayerFolder(Path source, Path target) throws IOException {
                if (!Files.isDirectory(source) || Files.exists(target)) {
                        // Whole-folder move impossible; still merge the files across.
                        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
                                return false;
                        }
                        boolean moved = false;
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(source, "*.dat")) {
                                for (Path file : files) {
                                        Path destination = target.resolve(file.getFileName().toString());
                                        if (!Files.exists(destination)) {
                                                Files.createDirectories(target);
                                                Files.move(file, destination);
                                                moved = true;
                                        }
                                }
                        }
                        if (moved && dirEmpty(source)) {
                                Files.deleteIfExists(source);
                        }
                        return moved;
                }
                Files.createDirectories(target.getParent());
                Files.move(source, target);
                return true;
        }

        private static boolean dirEmpty(Path dir) throws IOException {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                        return !stream.iterator().hasNext();
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
