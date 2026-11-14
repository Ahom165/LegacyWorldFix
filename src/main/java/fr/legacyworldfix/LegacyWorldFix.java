package fr.legacyworldfix;

import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.datafix.DataFixers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegacyWorldFix implements ModInitializer {
        public static final String MOD_ID = "legacyworldfix";
        public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

        @Override
        public void onInitialize() {
                LOGGER.info("[LegacyWorldFix] Loaded - worlds saved before 1.6.4 (Indev/Classic, Infdev/Alpha, McRegion, early Anvil) can be upgraded again");

                // The vanilla datafixer is a lazy singleton: the first conversion that
                // touches it would also pay its construction (~1 s of rule building)
                // plus the lazy per-type rule composition. Kicking it off right now
                // hides that cost behind the title screen on the client, and on the
                // dedicated server it overlaps the world scan instead of serializing
                // behind it. Class initialisation is thread-safe, so whichever thread
                // gets there first pays once and everyone else just waits on the lock.
                Thread prebuild = new Thread(() -> {
                        try {
                                long start = System.nanoTime();
                                DataFixers.getDataFixer();
                                LOGGER.info("[LegacyWorldFix] Datafixer ready in {} ms (prebuilt in background)",
                                        (System.nanoTime() - start) / 1_000_000L);
                        } catch (Throwable t) {
                                LOGGER.warn("[LegacyWorldFix] Background datafixer prebuild failed; the conversion will build it on demand", t);
                        }
                }, "LegacyWorldFix-DFU-Prebuild");
                prebuild.setDaemon(true);
                prebuild.start();

                // On a dedicated server, convert every legacy world before the server entry
                // point reads level.dat, so the "must be opened in an older version" refusal
                // never even has a chance to trigger.
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                        Path universe = Paths.get("").toAbsolutePath();
                        int converted = LegacyMcRegionUpgrader.convertAllInDirectory(universe);
                        if (converted > 0) {
                                LOGGER.info("[LegacyWorldFix] Converted {} legacy world(s) in {} before server start", converted, universe);
                        }
                }
        }
}
