package fr.legacyworldfix;

import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegacyWorldFix implements ModInitializer {
        public static final String MOD_ID = "legacyworldfix";
        public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

        @Override
        public void onInitialize() {
                LOGGER.info("[LegacyWorldFix] Loaded - worlds saved before 1.6.4 (McRegion / early Anvil) can be upgraded again");

                // On a dedicated server, convert every legacy world before the server entry
                // point reads level.dat, so the "must be opened in an older version" refusal
                // never even has a chance to trigger.
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                        Path universe = Paths.get("").toAbsolutePath();
                        int converted = LegacyMcRegionUpgrader.convertAllInDirectory(universe);
                        if (converted > 0) {
                                LOGGER.info("[LegacyWorldFix] Converted {} McRegion world(s) in {} before server start", converted, universe);
                        }
                }
        }
}
