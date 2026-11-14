package fr.legacyworldfix.mixin;

import java.nio.file.Path;
import com.mojang.serialization.Dynamic;
import fr.legacyworldfix.LegacyMcRegionUpgrader;
import fr.legacyworldfix.LegacyWorldFix;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {

        /**
         * Since 26.x, Mojang removed the McRegion -&gt; Anvil upgrader, so worlds
         * whose level.dat still says "version: 19132" are refused with "This
         * world must be opened in an older version (like 1.6.4) to be safely
         * converted". Even older worlds (Infdev/Alpha chunk files, Indev fixed
         * maps) have no usable version tag at all and land in the same refusal.
         *
         * <p>This callback is deliberately CHEAP: the world list must load
         * without paying for any conversion. Legacy worlds only get their
         * in-memory tag patched, so the list shows them like every other world;
         * the actual conversion runs when the player launches the world (see
         * {@link WorldOpenFlowsMixin}). The only work still done here is the
         * idempotent folder migration for worlds an older mod/game pair already
         * converted into a previous storage layout - pure file moves, no chunk
         * processing.
         */
        @Inject(method = "makeLevelSummary", at = @At("HEAD"))
        private void legacyworldfix$prepareLegacyWorldSummary(
                Dynamic<?> dataTag,
                LevelStorageSource.LevelDirectory levelDirectory,
                boolean locked,
                int dataVersion,
                CallbackInfoReturnable<LevelSummary> cir
        ) {
                try {
                        Path worldDir = levelDirectory.path();
                        if (LegacyMcRegionUpgrader.detectFormat(worldDir).isLegacy()) {
                                // Still legacy: the conversion happens at world launch. Only
                                // patch the in-memory copy so this very call proceeds as if
                                // the world had always been Anvil.
                                if (dataTag.getValue() instanceof CompoundTag compoundTag) {
                                        LegacyMcRegionUpgrader.patchInMemoryTag(compoundTag, worldDir);
                                }
                        } else if (LegacyMcRegionUpgrader.needsLayoutMigration(worldDir)) {
                                // Already-converted world sitting in a previous storage
                                // layout: cheap, deterministic folder moves, no chunk work.
                                LegacyMcRegionUpgrader.prepareWorld(worldDir);
                        }
                } catch (Exception e) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Unexpected error while checking world {}",
                                levelDirectory.directoryName(),
                                e
                        );
                }
        }
}
