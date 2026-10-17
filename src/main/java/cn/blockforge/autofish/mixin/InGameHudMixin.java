package cn.blockforge.autofish.mixin;

import cn.blockforge.autofish.TitleWatcher;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接住服务器发来的三种提示文字，交给 {@link TitleWatcher} 识别目标颜色。
 *
 * <p>为什么挂在这里而不是读屏：{@code Title} / {@code Subtitle} / {@code ActionBar}
 * 三类包在客户端最终都汇聚到这三个方法，参数就是现成的 {@link Text} 对象——
 * 带样式、带颜色、带原文，不受分辨率、字体、资源包、光影影响。
 *
 * <p>只读不写：注入 HEAD、不取消、不改参数，对原版行为零影响。
 */
@Environment(EnvType.CLIENT)
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void autoFish$onTitle(Text title, CallbackInfo ci) {
        TitleWatcher.onText(title);
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void autoFish$onSubtitle(Text subtitle, CallbackInfo ci) {
        TitleWatcher.onText(subtitle);
    }

    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void autoFish$onOverlay(Text message, boolean tinted, CallbackInfo ci) {
        TitleWatcher.onText(message);
    }
}
