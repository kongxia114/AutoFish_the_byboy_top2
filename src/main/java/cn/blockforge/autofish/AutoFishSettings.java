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
 * <p>界面分工沿用原来的约定：
 * <ul>
 *   <li>基本选项：只放开关；</li>
 *   <li>高级选项：只放精度数值（带滑条）。</li>
 * </ul>
 */
public final class AutoFishSettings {
    private AutoFishSettings() {
    }

    /** 打开设置页。Cloth Config 没装时给一条聊天提示，而不是把游戏搞崩。 */
    public static void open(MinecraftClient client) {
        try {
            client.setScreen(build(client));
        } catch (Throwable missingClothConfig) {
            // 典型情况：玩家没装 Cloth Config，加载本类时抛 NoClassDefFoundError
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[自动钓鱼] 打开设置页需要安装 Cloth Config API"), false);
            }
        }
    }

    private static Screen build(MinecraftClient client) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(client.currentScreen)
            .setTitle(Text.literal("自动钓鱼设置"))
            .setSavingRunnable(AutoFishController::saveConfig);

        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory basic = builder.getOrCreateCategory(Text.literal("基本选项"));
        basic.addEntry(entries.startBooleanToggle(
                Text.literal("启用自动钓鱼"), AutoFishController.isEnabled())
            .setDefaultValue(false)
            .setSaveConsumer(AutoFishController::setEnabled)
            .build());
        basic.addEntry(entries.startBooleanToggle(
                Text.literal("溅水花触发咬钩"), AutoFishController.splashTriggerEnabled())
            .setDefaultValue(true)
            .setSaveConsumer(AutoFishController::setSplashTriggerEnabled)
            .build());
        basic.addEntry(entries.startBooleanToggle(
                Text.literal("多人游戏检测"), AutoFishController.multiplayerMode())
            .setDefaultValue(false)
            .setSaveConsumer(AutoFishController::setMultiplayerMode)
            .build());

        ConfigCategory advanced = builder.getOrCreateCategory(Text.literal("高级选项"));
        advanced.addEntry(entries.startIntSlider(
                Text.literal("收线预判（毫秒）"), AutoFishController.leadMs(), 0, 200)
            .setDefaultValue(40)
            .setSaveConsumer(AutoFishController::setLeadMs)
            .build());
        advanced.addEntry(entries.startIntSlider(
                Text.literal("重新投掷延迟（毫秒）"), AutoFishController.recastDelayMs(), 500, 5000)
            .setDefaultValue(1500)
            .setSaveConsumer(AutoFishController::setRecastDelayMs)
            .build());
        advanced.addEntry(entries.startIntSlider(
                Text.literal("随机重投延迟（百分之）"), AutoFishController.recastJitterPercent(), 0, 100)
            .setDefaultValue(0)
            .setSaveConsumer(AutoFishController::setRecastJitterPercent)
            .build());

        return builder.build();
    }
}
