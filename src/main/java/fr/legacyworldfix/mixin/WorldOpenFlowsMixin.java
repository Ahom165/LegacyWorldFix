package fr.legacyworldfix.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import fr.legacyworldfix.LegacyMcRegionUpgrader;
import fr.legacyworldfix.LegacyWorldFix;
import fr.legacyworldfix.client.LegacyConversionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the legacy-world conversion off the world list (where it used to block
 * the "Loading world list" screen) to the moment the player actually launches
 * the world. When {@code openWorld} is entered for a world that is still
 * legacy (or that sits in a storage layout this build no longer reads), the
 * vanilla flow is cancelled, a progress screen shows the conversion running on
 * a worker thread, and the vanilla flow is restarted once the world reads as
 * Anvil - so the world opens instead of being refused.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

        @Shadow
        @Final
        private Minecraft minecraft;

        @Shadow
        @Final
        private LevelStorageSource levelSource;

        @Inject(method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
        private void legacyworldfix$convertOnLaunch(String levelName, Runnable onDispose, CallbackInfo ci) {
                Path worldDir = this.levelSource.getLevelPath(levelName);
                if (worldDir == null || !Files.isDirectory(worldDir)) {
                        return;
                }

                boolean needsWork;
                try {
                        needsWork = LegacyMcRegionUpgrader.detectFormat(worldDir).isLegacy()
                                || LegacyMcRegionUpgrader.needsLayoutMigration(worldDir);
                } catch (Throwable t) {
                        LegacyWorldFix.LOGGER.warn(
                                "[LegacyWorldFix] Could not inspect world {} before launch; letting vanilla handle it",
                                levelName,
                                t
                        );
                        return;
                }
                if (!needsWork) {
                        return;
                }

                this.minecraft.setScreenAndShow(new LegacyConversionScreen(
                        (WorldOpenFlows) (Object) this,
                        levelName,
                        onDispose,
                        worldDir
                ));
                ci.cancel();
        }
}
