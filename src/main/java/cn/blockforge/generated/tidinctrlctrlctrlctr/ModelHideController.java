package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;

/**
 * 「人物模型隐身」：第三人称（F5）下把本地玩家自己的角色模型整体隐藏——身体、装备、披风、
 * 手持物都看不见（原版隐身药水效果连拿着的物品也会一起藏掉，这里保持一致）。
 *
 * <p>实现方式是在渲染入口处直接跳过本地玩家的整次渲染
 * （{@code PlayerEntityRendererMixin} 在 {@code PlayerEntityRenderer#render} 的 HEAD 取消调用），
 * 因此：
 * <ul>
 *   <li>只影响 {@code client.player} 自己——该渲染器同样负责渲染其它玩家，
 *       靠 {@link #shouldHide(AbstractClientPlayerEntity)} 里的同一性判断把他们排除在外；</li>
 *   <li>纯客户端表现，服务器与其它玩家视角里你完全正常，不会被真正"隐身"；</li>
 *   <li>第一人称本来就不渲染身体（只画手部，走 {@code renderRightArm / renderLeftArm} 独立通道），
 *       不受任何影响；</li>
 *   <li>名字标签、准星、受伤/火焰遮罩都不在这条渲染路径里，照常显示。</li>
 * </ul>
 *
 * <p>与旋转类功能互相独立、可以叠加：隐身 + 抬臂旋转 = 看不见的人在转圈。
 * 状态只在当前会话内有效，不写任何配置文件；关掉开关后下一帧模型立刻恢复显示。
 */
@Environment(EnvType.CLIENT)
public final class ModelHideController {
    private static boolean hidden = false;

    private ModelHideController() {
    }

    public static boolean isHidden() {
        return hidden;
    }

    /** 开关式切换，返回切换后的状态。 */
    public static boolean toggle() {
        hidden = !hidden;
        return hidden;
    }

    /**
     * 渲染侧守卫：这个被渲染的玩家是否「本地玩家本人且隐身开关已打开」。
     * 用实例同一性（==）比较而不是名字：本地玩家实体在整个客户端里只有一个实例，
     * 而 {@code PlayerEntityRenderer} 渲染任何在线玩家都会走到同一个方法。
     */
    public static boolean shouldHide(AbstractClientPlayerEntity player) {
        if (!hidden || player == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && player == client.player;
    }
}
