package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.AutoFishController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「浮漂：溅起水花」触发钩子：屏幕上的这条字幕由音效 entity.fishing_bobber.splash 产生，
 * 与其读屏认字，不如在音效系统本身取事件——声源坐标、时刻都是现成的，且不受分辨率、
 * 字体与截图压缩影响。
 *
 * <p>注入点选 {@code SoundSystem#play(SoundInstance)}：客户端所有音效（含延迟排程的）
 * 最终都汇聚到这一个方法。比挂在 SubtitlesHud 更可靠——字幕开关关闭时原版根本不注册
 * 字幕监听，本机制照常工作；控制器只记字段、由逐帧的 frame() 消费，从哪个线程发声都安全。
 */
@Environment(EnvType.CLIENT)
@Mixin(SoundSystem.class)
public class SoundSystemAutoFishMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"))
    private void autoFishOnSoundPlayed(SoundInstance sound, CallbackInfo ci) {
        AutoFishController.onSplashSound(sound);
    }
}
