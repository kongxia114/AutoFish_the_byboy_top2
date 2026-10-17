package cn.blockforge.autofish;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 右上角常驻角标：状态 + 怎么关 + 实时诊断计数。
 *
 * <p>这些计数是调「收线预判」时唯一的反馈来源，不要删：
 * <ul>
 *   <li><b>扫描</b>：读屏次数；<b>见条</b>：识别到鱼条的次数；</li>
 *   <li><b>亮度</b>：读回画面里非黑采样点的百分比。持续 0 说明这台设备的读屏通路拿不到内容
 *       （模组会自动在主帧缓冲 / 窗口后缓冲之间切换）；</li>
 *   <li><b>绿命中</b>：指针压在绿色段上的帧数；<b>右键</b>：实际按下右键的次数；</li>
 *   <li><b>判定 / 预测</b>：判定收线的总次数 / 其中靠速度外推提前点下的次数——
 *       后者占比越高说明指针越快、预判越吃重；</li>
 *   <li><b>溅水</b>：听到「浮漂：溅起水花」的次数 / 由它触发咬钩的次数；</li>
 *   <li><b>预判档</b>：当前提前量，调参时用来确认自己正在测哪一档。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class AutoFishHud {
    private static final int COLOR_STATE = 0xFF6FE3D5;
    private static final int COLOR_HINT = 0xA6FFFFFF;
    private static final int COLOR_STATS = 0xFFB9C4FF;

    private AutoFishHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(AutoFishHud::render);
    }

    private static void render(DrawContext context, float tickDelta) {
        if (!AutoFishController.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }

        Text state = Text.literal("自动钓鱼中");
        Text hint = Text.literal("溅水花自动咬钩 · 指针压绿自动收线 · 按 F8 可设置");
        Text line1 = Text.literal(String.format("扫描 %d · 见条 %d · 亮度 %d%% · 绿命中 %d",
            AutoFishController.scanCount(),
            AutoFishController.barSeenCount(),
            AutoFishController.frameBrightness(),
            AutoFishController.greenHitCount()));
        Text line2 = Text.literal(String.format("右键 %d · 判定 %d · 预测 %d · 溅水 %d/%d · 预判 %dms",
            AutoFishController.reelCount(),
            AutoFishController.hitDecisionCount(),
            AutoFishController.predictedHitCount(),
            AutoFishController.splashSeenCount(),
            AutoFishController.splashReelCount(),
            AutoFishController.leadMs()));
        // 调「目标颜色 / 颜色容差」时唯一的参照：这一行告诉你指针底下实际读到的是什么色
        Text line3 = Text.literal(String.format("中心 %s · 目标 %s · 容差 ±%d",
            hex(AutoFishController.pointerCenterRgb()),
            AutoFishController.targetRgbHex(),
            AutoFishController.targetTol()));

        int widest = client.textRenderer.getWidth(state);
        widest = Math.max(widest, client.textRenderer.getWidth(hint));
        widest = Math.max(widest, client.textRenderer.getWidth(line1));
        widest = Math.max(widest, client.textRenderer.getWidth(line2));
        widest = Math.max(widest, client.textRenderer.getWidth(line3));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        int y = 6;

        context.drawTextWithShadow(client.textRenderer, state, x, y, COLOR_STATE);
        context.drawTextWithShadow(client.textRenderer, hint, x, y + 11, COLOR_HINT);
        context.drawTextWithShadow(client.textRenderer, line1, x, y + 22, COLOR_STATS);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + 33, COLOR_STATS);
        context.drawTextWithShadow(client.textRenderer, line3, x, y + 44, COLOR_STATS);
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
