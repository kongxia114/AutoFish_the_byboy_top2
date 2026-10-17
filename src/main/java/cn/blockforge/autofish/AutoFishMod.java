package cn.blockforge.autofish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 独立版「自动钓鱼」模组入口：只注册钓鱼相关的东西，不再带旋转 / 隐身 / 坐姿 / 转R6 那些功能。
 *
 * <p>两个驱动点：
 * <ul>
 *   <li>{@link AutoFishController#tick} —— 每游戏刻（20Hz）：只负责自动重抛这种秒级时序；</li>
 *   <li>GameRenderer 的 mixin —— 每渲染帧：负责读屏识别与收线，因为指针扫得快时 20Hz 会整段穿过绿区。</li>
 * </ul>
 */
public final class AutoFishMod implements ClientModInitializer {
    public static final String MOD_ID = "tidin_autofish";

    private static KeyBinding openConfigKey;

    @Override
    public void onInitializeClient() {
        // 先读回上次保存的参数（收线预判 / 重投节奏），文件缺失或损坏时保持内置默认值
        AutoFishController.loadConfig();

        // 右上角角标：状态 + 实时诊断计数（调预判档位时的唯一反馈）
        AutoFishHud.register();

        // F8 打开设置页；Cloth Config 负责界面，本模组不碰任何版本相关的 GUI 钩子
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key." + MOD_ID + ".open_config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KeyBinding.UI_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                AutoFishSettings.open(client);
            }
            AutoFishController.tick(client);
        });
    }
}
