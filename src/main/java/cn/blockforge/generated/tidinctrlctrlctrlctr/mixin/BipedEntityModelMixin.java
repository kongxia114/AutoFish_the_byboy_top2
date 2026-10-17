package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.R6ModelController;
import cn.blockforge.generated.tidinctrlctrlctrlctr.SitPoseController;
import cn.blockforge.generated.tidinctrlctrlctrlctr.SpinController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「抬臂旋转」的渲染侧：在 {@link BipedEntityModel#setAngles} 算完本帧姿势之后，
 * 把本地玩家双臂的 roll（绕 Z 轴的抬起角）覆盖成 ±90°，形成水平平举的飞机姿势。
 *
 * <p>注入在 BipedEntityModel（而非 PlayerEntityModel）的 TAIL 是有原因的：
 * {@code PlayerEntityModel#setAngles} 先调 super、再把手臂姿势 copyTransform 给袖子，
 * 所以在 super 末尾改手臂，袖子会自动跟着抬起来，不用手动同步。
 *
 * <p>守卫条件层层收窄，保证其它生物 / 其它玩家 / 第一人称手部完全不受影响：
 * <ul>
 *   <li>仅在「抬臂旋转」开启时进入（静态标志，正常游戏开销约等于一次布尔读）；</li>
 *   <li>entity 必须是本地玩家（{@code client.player}）；</li>
 *   <li>视角必须是第三人称——第一人称的手臂与身体共用同一个模型实例
 *       （{@code PlayerEntityRenderer#renderArm} 也走 {@code setAngles}），
 *       不加这条守卫屏幕角落的手会横过来。</li>
 * </ul>
 * 姿势是逐帧计算的：模式一关，下一帧就不再覆盖，双臂自然垂落，无需还原代码。
 *
 * <p>同一个 TAIL 位置还挂了「盘腿坐」的姿势覆盖（{@link #sitPose$fold}）：
 * 原版 {@code setAngles} 每帧都会把部件角度与 pivotY/pivotZ 重置回站立/潜行默认值，
 * 所以坐姿同样只在开启的那些帧里生效，关闭后下一帧模型自动站好。
 * 两者同时开启时后注入的坐姿覆盖手臂（盘坐时手搭膝上，不再平举）。
 */
@Environment(EnvType.CLIENT)
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin<T extends LivingEntity> {
    /** 90°（弧度）：抬起角的覆盖幅度，双臂各自向身体外侧水平伸开（左臂向左、右臂向右）。 */
    private static final float ARMS_OUT = (float) (Math.PI / 2.0);

    // ---- 盘腿坐姿势参数（弧度 / 模型像素）。基于 1.20.1 玩家模型真实枢轴几何
    // （head(0,0,0) body(0,0,0) arm(±5,2,0) leg(±1.9,12,0)，脚底 y=24），
    // 经离线渲染预览验证：双腿贴地交叉、躯干坐在腿上、头顶离地约 22.6px。 ----
    /** 整模型下沉量：髋部降到 y=22，折叠后的腿（厚 4px）底面恰好落在地面 y=24。 */
    private static final float SIT_SINK = 10.0F;
    /** 躯干前倾角（约 10°）。头部是叠加到原版视角角上，保留“头随鼠标转”。 */
    private static final float SIT_LEAN = 0.17F;
    /** 手臂前摆角（约 -34°，负 pitch = 向面朝方向摆），双手搭向膝前。 */
    private static final float SIT_ARMS_LAP = -0.60F;
    /** 坐姿下双臂的轻微外张角，防止手穿进身体。 */
    private static final float SIT_ARM_OUT = 0.14F;
    /** 右腿：向前折约 95°、向内倾约 25°，压在身前最外侧。 */
    private static final float SIT_RIGHT_LEG_PITCH = -1.66F;
    private static final float SIT_RIGHT_LEG_ROLL = -0.44F;
    /** 左腿：向前折约 93°、向内倾约 17°，叠在右腿内侧略高一点。 */
    private static final float SIT_LEFT_LEG_PITCH = -1.62F;
    private static final float SIT_LEFT_LEG_ROLL = 0.30F;
    /** 双腿整体略前移，交叉点落在身体正前方。 */
    private static final float SIT_LEG_PIVOT_Z = -0.5F;
    /** 原版 setAngles 每帧重置的默认枢轴：手臂 pivotY=2.0，髋部 pivotY=12.0。 */
    private static final float ARM_PIVOT_Y_DEFAULT = 2.0F;
    private static final float HIP_PIVOT_Y_DEFAULT = 12.0F;

    // 目标字段是 public final，覆盖其旋转角需要 @Mutable 声明。
    @Mutable
    @Shadow
    public ModelPart rightArm;

    @Mutable
    @Shadow
    public ModelPart leftArm;

    @Mutable
    @Shadow
    public ModelPart head;

    @Mutable
    @Shadow
    public ModelPart body;

    @Mutable
    @Shadow
    public ModelPart rightLeg;

    @Mutable
    @Shadow
    public ModelPart leftLeg;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void airplane$holdArmsOut(LivingEntity entity, float limbAngle, float limbDistance,
                                      float animationProgress, float headYaw, float headPitch,
                                      CallbackInfo info) {
        if (!SpinController.isArmsRaised()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || entity != client.player) {
            return;
        }
        if (client.options == null || client.options.getPerspective() == Perspective.FIRST_PERSON) {
            return;
        }
        // 只覆盖 roll（侧向抬起角）：xRot/yRot 留给原版的走路摆臂与攻击挥臂，
        // 平举的手臂还会跟着挥动，像机翼扑动，观感更活泼。
        // roll 的正负方向以实测为准：这样两臂各自向身体外侧水平抬起（左臂向左、右臂向右）。
        this.rightArm.roll = ARMS_OUT;
        this.leftArm.roll = -ARMS_OUT;
    }

    /**
     * 「盘腿坐」的渲染侧：在本帧姿势算完后覆盖本地玩家模型的部件角度与枢轴，
     * 摆出双腿前折交叉、躯干坐在腿上的盘坐姿势。
     *
     * <p>之所以放在 TAIL 覆盖而不是持久修改：原版 setAngles 每帧重置这些字段
     * （手臂 pivotX/pivotZ 无条件重置；pivotY 与腿的 pivotZ 在潜行 if/else 两个分支里
     * 都会赋回默认值），所以开关关闭后下一帧模型自动恢复，零还原代码。
     * 袖子与裤腿是手臂/腿的 copyTransform 子部件，会自动跟随坐姿。
     *
     * <p>守卫与抬臂旋转同样层层收窄：仅本地玩家、仅第三人称；死亡、骑乘、
     * 睡觉、滑翔时不覆盖，不与这些原版姿态打架。
     */
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void sitPose$fold(LivingEntity entity, float limbAngle, float limbDistance,
                              float animationProgress, float headYaw, float headPitch,
                              CallbackInfo info) {
        if (!SitPoseController.isSitting()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || entity != client.player) {
            return;
        }
        if (client.options == null || client.options.getPerspective() == Perspective.FIRST_PERSON) {
            return;
        }
        if (!entity.isAlive() || entity.hasVehicle() || entity.isSleeping() || entity.isFallFlying()) {
            return;
        }
        // 整体下沉：头/躯干/手臂/双腿的 pivotY 统一加下沉量（基准取原版每帧重置的默认值）。
        this.head.pivotY = SIT_SINK;
        this.body.pivotY = SIT_SINK;
        this.rightArm.pivotY = ARM_PIVOT_Y_DEFAULT + SIT_SINK;
        this.leftArm.pivotY = ARM_PIVOT_Y_DEFAULT + SIT_SINK;
        this.rightLeg.pivotY = HIP_PIVOT_Y_DEFAULT + SIT_SINK;
        this.leftLeg.pivotY = HIP_PIVOT_Y_DEFAULT + SIT_SINK;
        this.rightLeg.pivotZ = SIT_LEG_PIVOT_Z;
        this.leftLeg.pivotZ = SIT_LEG_PIVOT_Z;
        // 躯干微前倾；头部叠加同样前倾，保留原版的视角跟随（头仍跟鼠标看上看下）。
        this.body.pitch = SIT_LEAN;
        this.head.pitch += SIT_LEAN;
        // 双手搭向膝前、略外张；绝对赋值覆盖走路摆臂（坐着不该摆臂）。
        this.rightArm.pitch = SIT_ARMS_LAP;
        this.rightArm.roll = SIT_ARM_OUT;
        this.leftArm.pitch = SIT_ARMS_LAP;
        this.leftArm.roll = -SIT_ARM_OUT;
        // 双腿向前折拢、向内交叉：右腿在外、左腿在内（非对称角度营造叠压感）。
        this.rightLeg.pitch = SIT_RIGHT_LEG_PITCH;
        this.rightLeg.roll = SIT_RIGHT_LEG_ROLL;
        this.leftLeg.pitch = SIT_LEFT_LEG_PITCH;
        this.leftLeg.roll = SIT_LEFT_LEG_ROLL;
    }

    /**
     * 先在基础双足模型上处理缩放与枢轴（包括盔甲模型）；玩家外层部件在
     * PlayerEntityModelMixin 中、原版 copyTransform 完成后再同步一次。
     */
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void r6Model$stretch(LivingEntity entity, float limbAngle, float limbDistance,
                                 float animationProgress, float headYaw, float headPitch,
                                 CallbackInfo info) {
        if (!(entity instanceof AbstractClientPlayerEntity)) {
            return;
        }
        boolean apply = R6ModelController.isApplied(entity);
        applyScale(this.head,
            apply ? R6ModelController.HEAD_X : 1.0F,
            apply ? R6ModelController.HEAD_Y : 1.0F,
            apply ? R6ModelController.HEAD_Z : 1.0F);
        applyScale(this.body,
            apply ? R6ModelController.BODY_X : 1.0F,
            apply ? R6ModelController.BODY_Y : 1.0F,
            apply ? R6ModelController.BODY_Z : 1.0F);
        applyScale(this.rightArm,
            apply ? R6ModelController.ARM_X : 1.0F,
            apply ? R6ModelController.ARM_Y : 1.0F,
            apply ? R6ModelController.ARM_Z : 1.0F);
        applyScale(this.leftArm,
            apply ? R6ModelController.ARM_X : 1.0F,
            apply ? R6ModelController.ARM_Y : 1.0F,
            apply ? R6ModelController.ARM_Z : 1.0F);
        applyScale(this.rightLeg,
            apply ? R6ModelController.LEG_X : 1.0F,
            apply ? R6ModelController.LEG_Y : 1.0F,
            apply ? R6ModelController.LEG_Z : 1.0F);
        applyScale(this.leftLeg,
            apply ? R6ModelController.LEG_X : 1.0F,
            apply ? R6ModelController.LEG_Y : 1.0F,
            apply ? R6ModelController.LEG_Z : 1.0F);
        if (apply && !SitPoseController.isSitting()) {
            this.rightArm.pivotX = -R6ModelController.ARM_PIVOT_X;
            this.leftArm.pivotX = R6ModelController.ARM_PIVOT_X;
            this.rightLeg.pivotX = -R6ModelController.LEG_PIVOT_X;
            this.leftLeg.pivotX = R6ModelController.LEG_PIVOT_X;
        }
    }

    private static void applyScale(ModelPart part, float x, float y, float z) {
        part.xScale = x;
        part.yScale = y;
        part.zScale = z;
    }
}
