package cn.blockforge.autofish;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * 设置页：交给 Cloth Config 渲染。
 *
 * <p>为什么用 Cloth Config 而不是自己画：{@code Screen#renderBackground}、
 * {@code ClickableWidget#renderButton}（1.20.4 改名 {@code renderWidget}）、
 * {@code SliderWidget} 的构造函数在 1.20.1 与 1.20.4 之间都不一致，
 * 手写 GUI 就得为每个版本各写一份。Cloth Config 自己按 MC 版本发布，
 * 这些差异由它吸收，本模组只出「配置项定义」。
 *
 * <p><b>单页两个小节</b>：不再用两个 ConfigCategory（那会变成顶部的标签页），
 * 而是同一个分类里用文字分隔，滚一页就看完——基本区只有开关，高级区只有精度数值。
 */
public final class AutoFishSettings {
    private AutoFishSettings() {
    }

    /** 打开设置页。Cloth Config 没装时给一条聊天提示，而不是把游戏搞崩。 */
    public static void open(MinecraftClient client) {
        try {
            client.setScreen(buildScreen(client.currentScreen));
        } catch (Throwable missingClothConfig) {
            // 典型情况：玩家没装 Cloth Config，加载本类时抛 NoClassDefFoundError
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[自动钓鱼] 打开设置页需要安装 Cloth Config API"), false);
            }
        }
    }

    /** 给 Mod Menu 的「配置」按钮用：只需要父屏幕，不依赖 client 当前状态。 */
    public static Screen buildScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("自动钓鱼设置"))
            .setSavingRunnable(AutoFishController::saveConfig);

        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory page = builder.getOrCreateCategory(Text.literal("设置"));

        // ---------------- 基本选项：只放开关 ----------------
        page.addEntry(entries.startTextDescription(Text.literal("—— 基本选项 ——")).build());
        page.addEntry(entries.startBooleanToggle(
                Text.literal("启用自动钓鱼"), AutoFishController.isEnabled())
            .setDefaultValue(false)
            .setSaveConsumer(AutoFishController::setEnabled)
            .build());
        page.addEntry(entries.startBooleanToggle(
                Text.literal("溅水花触发咬钩"), AutoFishController.splashTriggerEnabled())
            .setDefaultValue(true)
            .setSaveConsumer(AutoFishController::setSplashTriggerEnabled)
            .build());
        page.addEntry(entries.startBooleanToggle(
                Text.literal("多人游戏检测"), AutoFishController.multiplayerMode())
            .setDefaultValue(false)
            .setSaveConsumer(AutoFishController::setMultiplayerMode)
            .build());

        // ---------------- 高级选项：只放精度数值 ----------------
        page.addEntry(entries.startTextDescription(Text.literal("—— 高级选项 ——")).build());
        page.addEntry(entries.startBooleanToggle(
                Text.literal("自动识别目标色"), AutoFishController.autoTarget())
            .setDefaultValue(true)
            .setSaveConsumer(AutoFishController::setAutoTarget)
            .build());
        page.addEntry(entries.startSelector(
                Text.literal("手动目标色"), AutoFishController.paletteNames(),
                AutoFishController.paletteNames()[Math.max(0, AutoFishController.targetPaletteIndex())])
            .setDefaultValue("绿")
            .setSaveConsumer(AutoFishController::setTargetPaletteName)
            .build());
        page.addEntry(entries.startStrField(
                Text.literal("目标颜色（#RRGGBB）"), AutoFishController.targetRgbHex())
            .setDefaultValue("#34ED53")
            .setSaveConsumer(AutoFishController::setTargetRgbHex)
            .build());
        page.addEntry(entries.startIntSlider(
                Text.literal("色相容差（度）"), AutoFishController.targetTol(), 10, 90)
            .setDefaultValue(40)
            .setSaveConsumer(AutoFishController::setTargetTol)
            .build());
        page.addEntry(entries.startIntSlider(
                Text.literal("收线预判（毫秒）"), AutoFishController.leadMs(), 0, 200)
            .setDefaultValue(40)
            .setSaveConsumer(AutoFishController::setLeadMs)
            .build());
        page.addEntry(entries.startIntSlider(
                Text.literal("重新投掷延迟（毫秒）"), AutoFishController.recastDelayMs(), 500, 5000)
            .setDefaultValue(1500)
            .setSaveConsumer(AutoFishController::setRecastDelayMs)
            .build());
        page.addEntry(entries.startIntSlider(
                Text.literal("随机重投延迟（百分之）"), AutoFishController.recastJitterPercent(), 0, 100)
            .setDefaultValue(0)
            .setSaveConsumer(AutoFishController::setRecastJitterPercent)
            .build());

        return builder.build();
    }
}
