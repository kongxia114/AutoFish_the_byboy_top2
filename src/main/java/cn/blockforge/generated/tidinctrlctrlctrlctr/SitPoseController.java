package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * 「盘腿坐」：在第三人称（F5）下把本地玩家的角色模型摆成盘腿坐的姿势——
 * 双腿向前折拢、在身前交叉叠放，躯干端坐在腿上并微微前倾，双手自然搭向膝前，
 * 整个模型下沉到坐姿高度（头顶约在站立高度的 71% 处）。
 *
 * <p>姿势由 {@code BipedEntityModelMixin} 在 {@code BipedEntityModel#setAngles} 末尾
 * 逐帧覆盖部件角度与枢轴实现：原版 setAngles 每帧都会把部件枢轴/角度重置回
 * 站立或潜行的默认值，所以开关一关，下一帧模型立刻站好，无需任何还原代码。
 * 姿势参数（角度、下沉量）用反编译出的 1.20.1 玩家模型真实枢轴几何
 * 经离线渲染预览验证：双腿恰好贴地交叉、不穿地、不悬空。
 *
 * <p>纯客户端表现：实体真实位置、碰撞箱、视角都不变，别人看到的仍是站着的你。
 * 与旋转类功能互不冲突、可以叠加（盘腿坐 + 角色旋转 = 原地盘坐转圈，
 * 与抬臂旋转同时开启时手臂以坐姿搭膝为准）。
 * 死亡、睡觉、游泳、滑翔、骑乘时姿势自动暂停（不与这些原版姿态打架），
 * 离开世界时开关自动关闭。
 */
@Environment(EnvType.CLIENT)
public final class SitPoseController {
    private static boolean sitting = false;

    private SitPoseController() {
    }

    public static boolean isSitting() {
        return sitting;
    }

    /** 开关式切换，返回切换后的状态。 */
    public static boolean toggle() {
        sitting = !sitting;
        return sitting;
    }

    /**
     * 由 {@code ClientTickEvents.END_CLIENT_TICK} 驱动：
     * 退出世界 / 回到主界面时自动关闭开关，不把坐姿带到下一局。
     * （姿势本身是逐帧渲染覆盖，关闭开关后无需清理任何模型状态。）
     */
    public static void tick(MinecraftClient client) {
        if (sitting && (client.player == null || client.world == null)) {
            sitting = false;
        }
    }
}
