package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.AutoFishController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「自动钓鱼」的逐帧驱动钩子：{@code GameRenderer#render} 每渲染帧调用一次，
 * 在主帧缓冲即将开始重绘新帧时（HEAD），它还完整保留着上一帧的画面——
 * 正是识别钓鱼条想要的最新内容。
 *
 * <p>收线判定必须跟帧率走而不是跟游戏刻走：指针扫得快时 20Hz 采样间隔内能移动
 * 几十像素，绿色段可能整体从两次采样中间穿过，等下一次 tick 再判定时已经错过。
 * 逐帧采样后相邻采样只差 1 帧（约 8~17 像素），配合条体缓存快通道的小区域读回，
 * 判定开销也比全屏读回低一个量级。
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public class GameRendererAutoFishMixin {
    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void autoFishOnFrame(float tickDelta, long limitTime, boolean renderBlockOutline, CallbackInfo ci) {
        AutoFishController.frame(MinecraftClient.getInstance());
    }
}
