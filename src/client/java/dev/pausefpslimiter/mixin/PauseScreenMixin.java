package dev.pausefpslimiter.mixin;

import dev.pausefpslimiter.PauseFpsLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void pauseFpsLimiter$addButton(CallbackInfo ci) {
        PauseScreen screen = (PauseScreen) (Object) this;

        int size = Math.max(26, Math.min(32, screen.height / 20));
        int x = screen.width / 2 + Math.min(150, screen.width / 4);
        x = Math.min(screen.width - size - 8, x);
        int y = Math.max(8, screen.height - size - 12);

        Button button = Button.builder(
                Component.literal("FPS"),
                ignored -> Minecraft.getInstance().gui.setScreen(
                        new PauseFpsLimiter.ConfigScreen(screen)
                )
        ).bounds(x, y, size, size).build();

        Screens.getWidgets(screen).add(button);
    }
}
