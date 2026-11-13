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
         * Both the client world list and the dedicated server entry point funnel
         * through this method when they build a LevelSummary. Since 26.x, Mojang
         * removed the McRegion -> Anvil upgrader, so worlds whose level.dat still
         * says "version: 19132" are refused with "This world must be opened in an
         * older version (like 1.6.4) to be safely converted". Even older worlds
         * (Infdev/Alpha chunk files, Indev fixed maps) have no usable version tag
         * at all and land in the same refusal.
         *
         * <p>We run the restored conversion right before the summary is computed,
         * then patch the in-memory copy of the tag so this very call proceeds as if
         * the world had always been Anvil. Vanilla then offers its regular
         * "Upgrade and play" flow (file fixing + data fixers), which carries the
         * world all the way to the current version.
         */
        @Inject(method = "makeLevelSummary", at = @At("HEAD"))
        private void legacyworldfix$convertLegacyWorld(
                Dynamic<?> dataTag,
                LevelStorageSource.LevelDirectory levelDirectory,
                boolean locked,
                int dataVersion,
                CallbackInfoReturnable<LevelSummary> cir
        ) {
                try {
                        Path worldDir = levelDirectory.path();
                        if (LegacyMcRegionUpgrader.isLegacyWorld(worldDir)) {
                                if (LegacyMcRegionUpgrader.convertWorld(worldDir) && dataTag.getValue() instanceof CompoundTag compoundTag) {
                                        LegacyMcRegionUpgrader.patchInMemoryTag(compoundTag, worldDir);
                                }
                        }
                } catch (Exception e) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Unexpected error while checking/converting world {}",
                                levelDirectory.directoryName(),
                                e
                        );
                }
        }
}
