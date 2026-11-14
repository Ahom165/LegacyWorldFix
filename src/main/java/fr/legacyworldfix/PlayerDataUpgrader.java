package fr.legacyworldfix;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/**
 * Player-data part of the legacy upgrade, mirroring what the vanilla 26.x
 * {@code LevelDatToSavedDataFileFix} does to players - and what 26.x can no
 * longer do for itself on worlds whose upgrade pass this mod short-circuits:
 *
 * <ul>
 *   <li>the singleplayer player embedded in {@code level.dat} moves to
 *       {@code players/data/<uuid>.dat} (the folder {@code
 *       LevelResource.PLAYER_DATA_DIR} of the running build) with the PLAYER
 *       datafixer chain applied from the tag's own version - that is where
 *       numeric item ids get their namespaces - and level.dat records the uuid
 *       under {@code singleplayer_uuid}, which is exactly the key {@code
 *       PlayerList#loadPlayerData} resolves the singleplayer file by. Players
 *       without a uuid (every pre-1.8 save) get the vanilla fallback nil
 *       uuid;</li>
 *   <li>legacy name-based player files ({@code players/<name>.dat}, written by
 *       Beta 1.8 - 1.7 era saves) are migrated too: 26.x reads only
 *       {@code players/data/<uuid>.dat} and lost the name-based fallback
 *       entirely, so without this the data sits there forever unread;</li>
 *   <li>worlds an older mod release extracted into a folder this build no
 *       longer reads ({@code playerdata/}) get their player files moved into
 *       the layout above - that is the repair for worlds converted by mod
 *       1.8.0 and older, whose inventory the running game could not find.</li>
 * </ul>
 *
 * <p>Everything here is idempotent and best-effort: a failure leaves the
 * world untouched (the player data stays wherever it was) and only logs.
 */
final class PlayerDataUpgrader {

        /** The uuid the vanilla file fix falls back to when the tag has none. */
        private static final UUID FALLBACK_SINGLE_PLAYER_UUID = new UUID(0L, 0L);

        private PlayerDataUpgrader() {
        }

        /**
         * One idempotent pass over a world's player data. Runs for legacy
         * worlds right before their conversion (the embedded player leaves
         * level.dat before the LEVEL chain runs, exactly like vanilla) and for
         * already-converted worlds as the repair path.
         *
         * @return true when level.dat had to be rewritten
         */
        static boolean repairWorld(Path worldDir) throws IOException {
                boolean changed = false;

                CompoundTag root = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("Data");

                // 1. The level.dat player, whatever the world's storage format.
                if (!data.getCompoundOrEmpty("Player").isEmpty()) {
                        if (extractEmbeddedPlayer(worldDir, data)) {
                                changed = true;
                        }
                }

                // 2. Legacy name-based files the running game can no longer read.
                int migrated = convertLegacyNameFiles(worldDir);
                if (migrated > 0) {
                        LegacyWorldFix.LOGGER.info(
                                "[LegacyWorldFix] Migrated {} legacy name-based player file(s) of {} into the layout this build reads",
                                migrated,
                                worldDir.getFileName()
                        );
                }

                // 3. Backfill the singleplayer uuid when the owner's file is unambiguous:
                //    no embedded player (extracted above or never present), no recorded
                //    uuid, and exactly one player file on disk - a singleplayer world.
                if (!data.contains("Player") && data.getIntArray("singleplayer_uuid").orElse(null) == null) {
                        UUID single = solePlayerUuid(worldDir);
                        if (single != null) {
                                data.putIntArray("singleplayer_uuid", UUIDUtil.uuidToIntArray(single));
                                changed = true;
                                LegacyWorldFix.LOGGER.info(
                                        "[LegacyWorldFix] Recorded {} as the singleplayer owner of {}",
                                        single,
                                        worldDir.getFileName()
                                );
                        }
                }

                if (changed) {
                        NbtIo.writeCompressed(root, worldDir.resolve("level.dat"));
                }
                return changed;
        }

        /**
         * Vanilla-exact extraction of the level.dat player: PLAYER datafixer
         * chain from the tag's own version (missing counts as the oldest
         * schema - the DFU clamps), file written under the tag uuid or the nil
         * fallback, {@code singleplayer_uuid} recorded, {@code Player} removed
         * from the level data. Safe on already-fixed tags too: the PLAYER
         * chain is convergent (proven identical output on raw and pre-fixed
         * input, see docs/PLAYER-PROOF-1.8.1.txt).
         */
        static boolean extractEmbeddedPlayer(Path worldDir, CompoundTag data) throws IOException {
                CompoundTag player = data.getCompoundOrEmpty("Player");
                if (player.isEmpty()) {
                        return false;
                }

                CompoundTag upgraded;
                try {
                        int playerVersion = NbtUtils.getDataVersion(player, -1);
                        upgraded = playerVersion >= LegacyMcRegionUpgrader.currentDataVersion()
                                ? player.copy()
                                : DataFixTypes.PLAYER.update(DataFixers.getDataFixer(), player.copy(), playerVersion, LegacyMcRegionUpgrader.currentDataVersion());
                } catch (RuntimeException e) {
                        LegacyWorldFix.LOGGER.warn(
                                "[LegacyWorldFix] Could not upgrade the level.dat player in place ({}); it stays in level.dat",
                                e.toString()
                        );
                        return false;
                }

                UUID uuid = FALLBACK_SINGLE_PLAYER_UUID;
                int[] rawUuid = upgraded.getIntArray("UUID").orElse(null);
                if (rawUuid != null && rawUuid.length == 4) {
                        uuid = UUIDUtil.uuidFromIntArray(rawUuid);
                }

                Path playerFile = StorageLayout.playerDataDir(worldDir).resolve(uuid + ".dat");
                if (!Files.isRegularFile(playerFile)) {
                        Files.createDirectories(playerFile.getParent());
                        NbtUtils.addDataVersion(upgraded, LegacyMcRegionUpgrader.currentDataVersion());
                        NbtIo.writeCompressed(upgraded, playerFile);
                }

                data.remove("Player");
                data.putIntArray("singleplayer_uuid", UUIDUtil.uuidToIntArray(uuid));
                LegacyWorldFix.LOGGER.info(
                        "[LegacyWorldFix] Extracted the level.dat player of {} to {}",
                        worldDir.getFileName(),
                        playerFile
                );
                return true;
        }

        /**
         * {@code players/<name>.dat} files (Beta 1.8 - 1.7.6 era, name-based)
         * -> {@code players/data/<offline-uuid>.dat}. The offline uuid is the
         * deterministic {@code OfflinePlayer:<name>} one; the save owner is
         * wired up through {@code singleplayer_uuid} regardless, so the
         * original SP player gets their data even if the uuid guess mismatches.
         */
        static int convertLegacyNameFiles(Path worldDir) throws IOException {
                Path playersDir = worldDir.resolve("players");
                if (!Files.isDirectory(playersDir)) {
                        return 0;
                }

                int migrated = 0;
                List<Path> legacyFiles = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(playersDir, "*.dat")) {
                        for (Path file : stream) {
                                String name = file.getFileName().toString();
                                // players/<name>.dat sits next to the players/data folder of
                                // the modern layout; skip the backup shapes the game writes
                                // (<id>.dat_old, <id>_corrupted_<date>.dat) - underscores are
                                // legal inside player names, so no blanket "_" rule.
                                if (name.endsWith("_old.dat") || name.contains("_corrupted_")) {
                                        continue;
                                }
                                legacyFiles.add(file);
                        }
                }

                for (Path file : legacyFiles) {
                        String playerName = file.getFileName().toString();
                        playerName = playerName.substring(0, playerName.length() - 4);
                        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        Path target = StorageLayout.playerDataDir(worldDir).resolve(offlineUuid + ".dat");
                        if (Files.isRegularFile(target)) {
                                continue;
                        }
                        try {
                                CompoundTag player = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                                int playerVersion = NbtUtils.getDataVersion(player, -1);
                                if (playerVersion < LegacyMcRegionUpgrader.currentDataVersion()) {
                                        player = DataFixTypes.PLAYER.update(DataFixers.getDataFixer(), player, playerVersion, LegacyMcRegionUpgrader.currentDataVersion());
                                }
                                Files.createDirectories(target.getParent());
                                NbtUtils.addDataVersion(player, LegacyMcRegionUpgrader.currentDataVersion());
                                NbtIo.writeCompressed(player, target);
                                migrated++;
                        } catch (RuntimeException | IOException e) {
                                LegacyWorldFix.LOGGER.warn(
                                        "[LegacyWorldFix] Could not migrate legacy player file {} ({}); it stays where it is",
                                        file,
                                        e.toString()
                                );
                        }
                }
                return migrated;
        }

        /** The one player file in the layout the build reads, or null when ambiguous/absent. */
        private static UUID solePlayerUuid(Path worldDir) throws IOException {
                Path dataDir = StorageLayout.playerDataDir(worldDir);
                if (!Files.isDirectory(dataDir)) {
                        return null;
                }

                UUID sole = null;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.dat")) {
                        for (Path file : stream) {
                                String fileName = file.getFileName().toString();
                                if (fileName.endsWith("_old.dat") || fileName.contains("_corrupted_")) {
                                        continue; // backups are not candidates
                                }
                                if (sole != null) {
                                        return null; // more than one player: cannot guess the owner
                                }
                                try {
                                        sole = UUID.fromString(file.getFileName().toString().substring(0, 36));
                                } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                                        return null;
                                }
                        }
                }
                return sole;
        }
}
