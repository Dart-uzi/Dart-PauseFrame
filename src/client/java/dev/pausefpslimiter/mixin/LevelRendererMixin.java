package dev.pausefpslimiter.mixin;

import dev.pausefpslimiter.PauseFpsLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void pauseFpsLimiter$skipWorldBehindMenu(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();

        if (PauseFpsLimiter.shouldSkipWorld()
                && client.gui != null
                && client.gui.screen() != null) {
            ci.cancel();
        }
    }
}
