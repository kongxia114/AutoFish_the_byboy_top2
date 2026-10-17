package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Ctrl 功能菜单：按一次右 Ctrl 呼出半透明黑色功能面板，再按一次（或 Esc）关闭。
 * 纯客户端模组，不写任何配置文件；所有选项固定使用推荐值，仅保留两个会话内开关。
 */
public final class FunctionMenuMod implements ClientModInitializer {
    public static final String MOD_ID = "tidin_ctrl_ctrl_ctrl_ctr";

    /** 呼出/关闭音效（推荐值：开。不持久化，重启恢复默认）。 */
    public static boolean soundEnabled = true;

    /** 屏幕左上角信息浮层（推荐值：关。不持久化，重启恢复默认）。 */
    public static boolean infoOverlayEnabled = false;

    private static KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        // 先读回上次保存的高级参数（收线预判 / 重投节奏），缺失或损坏时保持内置默认值
        AutoFishController.loadConfig();

        // 默认绑右 Ctrl：左 Ctrl 是原版疾跑键，避免玩法冲突；可在按键绑定里改。
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key." + MOD_ID + ".open_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_CONTROL,
            KeyBinding.UI_CATEGORY));

        // 开关式按键：按下切换面板，界面立即出现，不进入按键等待状态。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                toggle(client);
            }
        });

        // 旋转工具：每刻推进一点朝向，面板开着（游戏不暂停）也能实时看到转起来。
        ClientTickEvents.END_CLIENT_TICK.register(SpinController::tick);

        // 盘腿坐：离开世界时自动关开关（姿势本身是逐帧渲染覆盖，无需清理）。
        ClientTickEvents.END_CLIENT_TICK.register(SitPoseController::tick);
        // 自动钓鱼：tick 只做自动重抛；识别与收线由 GameRenderer 逐帧钩子驱动（见 AutoFishController.frame）；
        // 「浮漂：溅起水花」咬钩触发由 SoundSystem.play 混入记声源、frame() 逐帧消费（见 SoundSystemAutoFishMixin）。
        ClientTickEvents.END_CLIENT_TICK.register(AutoFishController::tick);

        HudRenderCallback.EVENT.register(FunctionMenuMod::renderInfoOverlay);
        HudRenderCallback.EVENT.register(FunctionMenuMod::renderSpinBadge);
        HudRenderCallback.EVENT.register(FunctionMenuMod::renderHideBadge);
        HudRenderCallback.EVENT.register(FunctionMenuMod::renderSitBadge);
        HudRenderCallback.EVENT.register(FunctionMenuMod::renderAutoFishBadge);
        HudRenderCallback.EVENT.register(FunctionMenuMod::renderR6Badge);
    }

    /** 角标堆叠：右上角自上而下依次为 旋转（两行）→ 隐身（两行）→ 盘腿坐（两行）→ 自动钓鱼（三行）→ 转R6（两行），
     * 旋转/隐身/盘腿坐/转R6 每个角标占两行 22 像素，自动钓鱼占三行 33 像素，只有开启的才占位。 */
    private static int badgeRowY(boolean afterSpin, boolean afterHide, boolean afterSit, boolean afterAutoFish) {
        int y = 6;
        if (afterSpin && SpinController.isSpinning()) {
            y += 22;
        }
        if (afterHide && ModelHideController.isHidden()) {
            y += 22;
        }
        if (afterSit && SitPoseController.isSitting()) {
            y += 22;
        }
        if (afterAutoFish && AutoFishController.isEnabled()) {
            y += 33;
        }
        return y;
    }

    /** 自动钓鱼开启时的常驻角标：提醒识别在后台运行、怎么关。 */
    private static void renderAutoFishBadge(DrawContext context, float tickDelta) {
        if (!AutoFishController.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }
        // 面板打开时不画：工具页的按钮本身就在显示自动钓鱼状态。
        if (client.currentScreen instanceof FunctionMenuScreen) {
            return;
        }
        Text state = Text.translatable("tidinmenu.autofish.badge");
        Text hint = Text.translatable("tidinmenu.autofish.badge_hint");
        Text stats = Text.translatable("tidinmenu.autofish.badge_stats",
            AutoFishController.scanCount(), AutoFishController.barSeenCount(),
            AutoFishController.frameBrightness(), AutoFishController.greenHitCount(),
            AutoFishController.reelCount(), AutoFishController.splashSeenCount(),
            AutoFishController.splashReelCount());
        int widest = Math.max(client.textRenderer.getWidth(state),
            Math.max(client.textRenderer.getWidth(hint), client.textRenderer.getWidth(stats)));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        int y = badgeRowY(true, true, true, false);
        context.drawTextWithShadow(client.textRenderer, state, x, y, 0xFF6FE3D5);
        context.drawTextWithShadow(client.textRenderer, hint, x, y + 11, 0xA6FFFFFF);
        context.drawTextWithShadow(client.textRenderer, stats, x, y + 22, 0xFFB9C4FF);
    }

    /** 转R6开启时的常驻角标：第一人称看不到比例变化，留一行字提醒当前是 R6 比例、怎么关。 */
    private static void renderR6Badge(DrawContext context, float tickDelta) {
        if (!R6ModelController.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }
        // 面板打开时不画：工具页的按钮本身就在显示转R6状态。
        if (client.currentScreen instanceof FunctionMenuScreen) {
            return;
        }
        Text state = Text.translatable("tidinmenu.r6.badge");
        Text hint = Text.translatable("tidinmenu.r6.badge_hint");
        int widest = Math.max(client.textRenderer.getWidth(state), client.textRenderer.getWidth(hint));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        int y = badgeRowY(true, true, true, true);
        context.drawTextWithShadow(client.textRenderer, state, x, y, 0xFFE28CFF);
        context.drawTextWithShadow(client.textRenderer, hint, x, y + 11, 0xA6FFFFFF);
    }

    /** 盘腿坐开启时的常驻角标：提醒当前是坐姿、怎么站回来。 */
    private static void renderSitBadge(DrawContext context, float tickDelta) {
        if (!SitPoseController.isSitting()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }
        // 面板打开时不画：工具页的按钮本身就在显示坐姿状态。
        if (client.currentScreen instanceof FunctionMenuScreen) {
            return;
        }
        Text state = Text.translatable("tidinmenu.sit.badge");
        Text hint = Text.translatable("tidinmenu.sit.badge_hint");
        int widest = Math.max(client.textRenderer.getWidth(state), client.textRenderer.getWidth(hint));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        int y = badgeRowY(true, true, false, false);
        context.drawTextWithShadow(client.textRenderer, state, x, y, 0xFF9BB8FF);
        context.drawTextWithShadow(client.textRenderer, hint, x, y + 11, 0xA6FFFFFF);
    }

    /** 人物模型隐身开启时的常驻角标：身体不见了，留一行字提醒这是开关生效、怎么关。 */
    private static void renderHideBadge(DrawContext context, float tickDelta) {
        if (!ModelHideController.isHidden()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }
        // 面板打开时不画：工具页的按钮本身就在显示隐身状态。
        if (client.currentScreen instanceof FunctionMenuScreen) {
            return;
        }
        Text state = Text.translatable("tidinmenu.hide.badge");
        Text hint = Text.translatable("tidinmenu.hide.badge_hint");
        int widest = Math.max(client.textRenderer.getWidth(state), client.textRenderer.getWidth(hint));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        // 旋转角标占顶部两行（y=6/17），两个功能同时开启时隐身角标往下错开。
        int y = badgeRowY(true, false, false, false);
        context.drawTextWithShadow(client.textRenderer, state, x, y, 0xFF8FE38F);
        context.drawTextWithShadow(client.textRenderer, hint, x, y + 11, 0xA6FFFFFF);
    }

    /** 旋转进行中的常驻角标：菜单关掉后也能一眼看到「正在转」以及怎么停。 */
    private static void renderSpinBadge(DrawContext context, float tickDelta) {
        if (!SpinController.isSpinning()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options == null || client.options.hudHidden) {
            return;
        }
        // 面板打开时不画：工具页的按钮本身就在显示旋转状态。
        if (client.currentScreen instanceof FunctionMenuScreen) {
            return;
        }
        SpinController.Mode mode = SpinController.getMode();
        String badgeKey = switch (mode) {
            case MODEL -> "tidinmenu.spin.badge_model";
            case AIRPLANE -> "tidinmenu.spin.badge_airplane";
            default -> "tidinmenu.spin.badge";
        };
        String hintKey = switch (mode) {
            case MODEL -> "tidinmenu.spin.badge_model_hint";
            case AIRPLANE -> "tidinmenu.spin.badge_airplane_hint";
            default -> "tidinmenu.spin.badge_hint";
        };
        Text state = Text.translatable(badgeKey,
            Text.translatable("tidinmenu.spin.speed." + SpinController.speedKey()),
            Text.translatable(SpinController.isClockwise() ? "tidinmenu.spin.dir.cw" : "tidinmenu.spin.dir.ccw"),
            SpinController.degreesPerSecond());
        Text hint = Text.translatable(hintKey);
        int widest = Math.max(client.textRenderer.getWidth(state), client.textRenderer.getWidth(hint));
        int x = client.getWindow().getScaledWidth() - widest - 6;
        context.drawTextWithShadow(client.textRenderer, state, x, 6, 0xFFFFD166);
        context.drawTextWithShadow(client.textRenderer, hint, x, 17, 0xA6FFFFFF);
    }

    /** 再按一次 Ctrl 关闭；若当前是其它界面则叠开面板并在关闭时返回它。 */
    public static void toggle(MinecraftClient client) {
        if (client.currentScreen instanceof FunctionMenuScreen screen) {
            client.setScreen(screen.getParent());
        } else {
            client.setScreen(new FunctionMenuScreen(client.currentScreen));
            playOpenClick();
        }
    }

    private static void playOpenClick() {
        if (!soundEnabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        SoundEvent click = (SoundEvent) SoundEvents.UI_BUTTON_CLICK.value();
        // 0.65 的音高让开屏提示音比常规按钮更低沉。
        client.getSoundManager().play(PositionedSoundInstance.master(click, 0.65F));
    }

    private static void renderInfoOverlay(DrawContext context, float tickDelta) {
        if (!infoOverlayEnabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        if (client.currentScreen != null || client.options.hudHidden) {
            return;
        }
        List<String> lines = WorldInfo.summaryLines(client);
        int y = 6;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xE6FFFFFF : 0x99FFFFFF;
            context.drawTextWithShadow(client.textRenderer, lines.get(i), 6, y, color);
            y += 11;
        }
    }
}
