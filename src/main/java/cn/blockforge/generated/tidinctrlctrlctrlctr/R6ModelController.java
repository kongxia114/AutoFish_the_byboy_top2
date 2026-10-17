package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.LivingEntity;

/**
 * 「转R6」：按参考图（Screenshot_20261014_001504.jpg，经典 Roblox 方块人）实测的
 * 比例缩放本地玩家角色模型的各部件——躯干加宽加厚成「宽大于高」的方块，
 * 手臂、腿同步加粗并调整到贴合躯干；头部加宽并增高，躯干与四肢长度保持不变。
 *
 * <p>比例来源：对参考图做颜色分割量出各部件像素包围盒（908px 宽的图），
 * 左右两侧部件互为透视补偿（相机偏右，近侧偏大），取左右平均：
 * <pre>
 *   部件   实测(宽×高 px)   换算成模型单位(躯干高 12 单位 = 345px)   原版(宽×高×厚)
 *   头     229 × 172        8.0 × 6.0                                8 × 8 × 8
 *   躯干   406 × 345        14.1 × 12                                8 × 12 × 4
 *   手臂   212 × 342        7.4 × 12                                 4 × 12 × 4
 *   腿     201 × 346        7.0 × 12                                 4 × 12 × 4
 * </pre>
 * 深度按 Roblox 经典截面比例补齐（躯干厚=宽的一半、四肢截面正方、头厚=宽），
 * 恰好与原版「厚宽比」一致，所以 x 与 z 用同一个系数即可。
 * 高度分配 头:躯干:腿 = 6:12:12 = 20%:40%:40%，与参考图实测 20%:40%:40% 吻合。
 *
 * <p>实现依托 1.20.1 {@code ModelPart} 的 {@code xScale/yScale/zScale} 与
 * {@code pivotX/pivotY}：渲染矩阵按「平移到枢轴 → 旋转 → 缩放」合成。
 * 躯干加宽后手臂枢轴向身体内侧调整（内缘压住躯干边缘，避免透视缝隙）；
 * 手臂枢轴 y 不覆写——原版局部盒顶就比枢轴高 2 单位（box y -2..10），
 * 默认 pivotY=2 已让臂顶与躯干顶（肩线）齐平；
 * 腿枢轴从 ±1.9 外移到 ±3.5（两腿在正中对齐、外缘与躯干齐平），接缝无缝。
 * 袖子、裤腿、领口在 {@code PlayerEntityModel#setAngles} 里由 {@code copyTransform}
 * 从手臂/腿/躯干/头复制变换（枢轴、角度、缩放全带上），自动跟随；
 * 盔甲经 {@code BipedEntityModel#copyBipedStateTo} 同样整份复制，同步缩放外移。
 * 手持物品走 {@code HeldItemFeatureRenderer#renderItem}，其矩阵含手臂缩放，
 * 由 {@code R6HeldItemScaleMixin} 在同一帧里写回反向缩放抵消，物品保持原版大小。
 *
 * <p>生效范围与开关状态由 {@link #isApplied(LivingEntity)} 判定，
 * {@code PlayerEntityModelMixin} 在原版模型的 {@code setAngles} 完成后逐帧设置比例；
 * 仅本地玩家第三人称写入 R6 值，其它情况明确写回 1。袖子、裤腿、外套和帽子
 * 在此时重新复制基础部件变换，避免共享模型实例留下旧比例；枢轴由原版每帧重置。
 * 纯客户端表现：别人视角里的你始终是正常比例；第一人称的手、移动、碰撞箱、身高都不受影响。
 */
@Environment(EnvType.CLIENT)
public final class R6ModelController {
    // ---- 参考图实测比例（模型轴：左右 = x，上下 = y，前后 = z）----
    /** 头：宽、厚保持大方块，高度再增加一点；缩放绕头部底边枢轴，底边不下移。 */
    public static final float HEAD_X = 1.35F;
    public static final float HEAD_Y = 1.20F;
    public static final float HEAD_Z = 1.35F;

    /** 躯干：宽 8→14.2、厚 4→7.1，高 12 不变——宽大于高的 Roblox 大方块。 */
    public static final float BODY_X = 1.77F;
    public static final float BODY_Y = 1.0F;
    public static final float BODY_Z = 1.77F;

    /** 手臂：宽、厚 4→7.4，长 12 不变——和躯干等高的粗方柱。 */
    public static final float ARM_X = 1.84F;
    public static final float ARM_Y = 1.0F;
    public static final float ARM_Z = 1.84F;

    /** 腿：宽、厚 4→7.0，长 12 不变——两条粗方柱并排。 */
    public static final float LEG_X = 1.75F;
    public static final float LEG_Y = 1.0F;
    public static final float LEG_Z = 1.75F;

    // ---- 枢轴外移量（模型单位，原版每帧重置后由本功能覆写）----
    /**
     * 手臂枢轴向身体内侧偏移：参考截图中袖子与躯干仍有明显空隙，
     * 再向内约 0.9 格，使肩袖边缘与躯干轻微重叠，避免视角下露缝。
     */
    public static final float ARM_PIVOT_X = 8.0F;
    /** 腿枢轴 |x|：原版 1.9 → 半腿宽 3.5，两腿内缘在身体中线对齐互触。 */
    public static final float LEG_PIVOT_X = 3.5F;

    private static boolean enabled = false;

    private R6ModelController() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 开关式切换，返回切换后的状态。 */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    /** 本帧是否该给这个实体写 R6 比例：开关开 + 本地玩家 + 非第一人称。 */
    public static boolean isApplied(LivingEntity entity) {
        if (!enabled) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null
            && entity == client.player
            && client.options != null
            && client.options.getPerspective() != Perspective.FIRST_PERSON;
    }
}
