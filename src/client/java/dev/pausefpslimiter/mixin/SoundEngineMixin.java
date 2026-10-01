package dev.pausefpslimiter.mixin;

import dev.pausefpslimiter.PauseFpsLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class SoundEngineMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void pauseFpsLimiter$muteAudio(boolean paused, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();

        if (client.gui != null
                && PauseFpsLimiter.shouldMuteAudio()) {
            ((SoundManager) (Object) this).pauseAllExcept();
        }
    }
}
