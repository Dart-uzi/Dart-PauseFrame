package dev.pausefpslimiter.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.pausefpslimiter.PauseFpsLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderFrameMixin {
    @Redirect(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I"))
    private int pauseFpsLimiter$overrideLimit(FramerateLimitTracker tracker) {
        Minecraft client = Minecraft.getInstance();
        Integer limit = PauseFpsLimiter.getEffectiveFpsLimit(client.gui == null ? null : client.gui.screen());
        return limit != null ? limit : tracker.getFramerateLimit();
    }
}
