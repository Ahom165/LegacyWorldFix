package fr.legacyworldfix.client;

import java.nio.file.Path;
import fr.legacyworldfix.LegacyMcRegionUpgrader;
import fr.legacyworldfix.LegacyWorldFix;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;

/**
 * Shown while the legacy world the player just launched is converted (legacy
 * storage -&gt; Anvil -&gt; current data version, or a folder migration into the
 * layout this build reads). The conversion runs on a worker thread; the
 * vanilla open flow ({@code WorldOpenFlows#openWorld}) restarts on the client
 * thread once the world reads as Anvil, so the world opens instead of being
 * refused.
 *
 * <p>No cancel button on purpose: the conversion is crash-safe (level.dat is
 * only rewritten on success, and a retry converts whatever is left), but
 * interrupting the launch flow mid-way would leave the game without a world to
 * open. The progress comes from the counters the converters already maintain
 * for their console logs - the render thread only reads them.
 */
public class LegacyConversionScreen extends Screen {
        private final WorldOpenFlows worldOpenFlows;
        private final String levelName;
        private final Runnable onDispose;
        private final Path worldDir;

        private volatile boolean finished;
        private volatile boolean failed;
        private Thread worker;

        public LegacyConversionScreen(WorldOpenFlows worldOpenFlows, String levelName, Runnable onDispose, Path worldDir) {
                super(Component.translatable("legacyworldfix.converting.title"));
                this.worldOpenFlows = worldOpenFlows;
                this.levelName = levelName;
                this.onDispose = onDispose;
                this.worldDir = worldDir;
        }

        @Override
        protected void init() {
                // init() runs again on window resize: the worker thread must not
                // restart, and the back button must come back after a failure.
                if (this.worker == null) {
                        this.worker = new Thread(this::runConversion, "LegacyWorldFix-LaunchConversion");
                        this.worker.setDaemon(true);
                        this.worker.start();
                }
                if (this.finished && this.failed) {
                        this.addRenderableWidget(Button.builder(
                                                Component.translatable("legacyworldfix.converting.back"),
                                                button -> this.minecraft.setScreenAndShow(new SelectWorldScreen(null)))
                                        .bounds(this.width / 2 - 100, this.height / 2 + 45, 200, 20)
                                        .build());
                }
        }

        private void runConversion() {
                try {
                        // Full launch-time preparation: storage layout migration (older
                        // mod/game pairs) and/or the legacy -&gt; Anvil + datafixer
                        // conversion. Idempotent, and per-world locked.
                        LegacyMcRegionUpgrader.prepareWorld(this.worldDir);
                } catch (Throwable t) {
                        LegacyWorldFix.LOGGER.error(
                                "[LegacyWorldFix] Launch-time conversion of {} failed",
                                this.levelName,
                                t
                        );
                }

                boolean legacyLeft = LegacyMcRegionUpgrader.detectFormat(this.worldDir).isLegacy();
                this.finished = true;
                this.failed = legacyLeft;
                if (!legacyLeft) {
                        // Re-enter the vanilla flow: the openWorld mixin now sees an
                        // Anvil world and lets it through untouched.
                        this.minecraft.execute(() -> this.worldOpenFlows.openWorld(this.levelName, this.onDispose));
                }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
                super.extractRenderState(graphics, mouseX, mouseY, partialTick);
                int centerX = this.width / 2;
                graphics.centeredText(this.font, this.title, centerX, this.height / 2 - 35, 0xFFFFFFFF);

                if (this.finished && this.failed) {
                        graphics.centeredText(
                                this.font,
                                Component.translatable("legacyworldfix.converting.failed"),
                                centerX,
                                this.height / 2 - 10,
                                0xFFFF5555
                        );
                        return;
                }

                LegacyMcRegionUpgrader.ConversionPhase phase = LegacyMcRegionUpgrader.currentConversionPhase();
                if (phase == LegacyMcRegionUpgrader.ConversionPhase.CHUNKS) {
                        int done = LegacyMcRegionUpgrader.currentConversionDone();
                        int total = Math.max(LegacyMcRegionUpgrader.currentConversionTotal(), 0);
                        graphics.centeredText(
                                this.font,
                                Component.translatable("legacyworldfix.converting.chunks", done, total),
                                centerX,
                                this.height / 2 - 10,
                                0xFFA0A0A0
                        );
                        int barWidth = 200;
                        int x = centerX - barWidth / 2;
                        int y = this.height / 2 + 10;
                        float ratio = total == 0 ? 1.0F : Math.min(1.0F, done / (float) total);
                        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + 11, 0xFF000000);
                        graphics.fill(x, y, x + (int) (barWidth * ratio), y + 9, 0xFF38D038);
                } else if (phase == LegacyMcRegionUpgrader.ConversionPhase.WRITING) {
                        graphics.centeredText(
                                this.font,
                                Component.translatable("legacyworldfix.converting.writing"),
                                centerX,
                                this.height / 2 - 10,
                                0xFFA0A0A0
                        );
                } else {
                        graphics.centeredText(
                                this.font,
                                Component.translatable("legacyworldfix.converting.preparing"),
                                centerX,
                                this.height / 2 - 10,
                                0xFFA0A0A0
                        );
                }
        }

        @Override
        public boolean shouldCloseOnEsc() {
                return false;
        }

        @Override
        public boolean isPauseScreen() {
                return false;
        }
}
