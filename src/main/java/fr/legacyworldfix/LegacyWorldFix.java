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

                // The vanilla datafixer is a lazy singleton with lazy rule
                // composition: the first conversion that touches it pays the
                // construction (~0.7 s of rule building) AND several seconds of
                // per-type rule composition for the 99 -> current version range
                // (measured 4.3 s for CHUNK). Both now run in this background
                // thread - on the client behind the title screen, on the dedicated
                // server overlapping the world scan - so the first real chunk never
                // sees them. Class initialisation is thread-safe, and the DFU
                // caches the composed rules: whichever thread gets there first
                // pays once and everyone else waits on the warm cache.
                Thread prebuild = new Thread(() -> {
                        try {
                                long start = System.nanoTime();
                                DataFixers.getDataFixer();
                                long built = System.nanoTime();
                                LegacyMcRegionUpgrader.warmupDataFixerPipelines();
                                LOGGER.info(
                                        "[LegacyWorldFix] Datafixer ready in {} ms (built {} ms, rules composed {} ms, prebuilt in background)",
                                        (System.nanoTime() - start) / 1_000_000L,
                                        (built - start) / 1_000_000L,
                                        (System.nanoTime() - built) / 1_000_000L);
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
