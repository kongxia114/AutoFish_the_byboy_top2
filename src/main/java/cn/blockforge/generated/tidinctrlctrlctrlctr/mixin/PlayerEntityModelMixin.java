package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.R6ModelController;
import cn.blockforge.generated.tidinctrlctrlctrlctr.SitPoseController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 PlayerEntityModel#setAngles 的最后阶段应用 R6，并把玩家的外层服装同步到最终变换。
 *
 * <p>PlayerEntityModel 会在 super.setAngles 返回后复制袖子、裤腿和外套；如果只在
 * BipedEntityModel 的尾部改动，下一层复制和共用模型实例容易让外层保留上一帧状态。
 * 这里统一在原版复制完成后再写基础部件，并再复制一次外层，关闭时也明确写回原版值。
 */
@Environment(EnvType.CLIENT)
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> {
    @Mutable
    @Shadow
    public ModelPart leftSleeve;

    @Mutable
    @Shadow
    public ModelPart rightSleeve;

    @Mutable
    @Shadow
    public ModelPart leftPants;

    @Mutable
    @Shadow
    public ModelPart rightPants;

    @Mutable
    @Shadow
    public ModelPart jacket;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void r6Model$applyFinal(LivingEntity entity, float limbAngle, float limbDistance,
                                    float animationProgress, float headYaw, float headPitch,
                                    CallbackInfo info) {
        if (!(entity instanceof AbstractClientPlayerEntity)) {
            return;
        }

        BipedEntityModel<?> model = (BipedEntityModel<?>)(Object)this;
        boolean apply = R6ModelController.isApplied(entity);
        applyScale(model.head,
            apply ? R6ModelController.HEAD_X : 1.0F,
            apply ? R6ModelController.HEAD_Y : 1.0F,
            apply ? R6ModelController.HEAD_Z : 1.0F);
        applyScale(model.body,
            apply ? R6ModelController.BODY_X : 1.0F,
            apply ? R6ModelController.BODY_Y : 1.0F,
            apply ? R6ModelController.BODY_Z : 1.0F);
        applyScale(model.rightArm,
            apply ? R6ModelController.ARM_X : 1.0F,
            apply ? R6ModelController.ARM_Y : 1.0F,
            apply ? R6ModelController.ARM_Z : 1.0F);
        applyScale(model.leftArm,
            apply ? R6ModelController.ARM_X : 1.0F,
            apply ? R6ModelController.ARM_Y : 1.0F,
            apply ? R6ModelController.ARM_Z : 1.0F);
        applyScale(model.rightLeg,
            apply ? R6ModelController.LEG_X : 1.0F,
            apply ? R6ModelController.LEG_Y : 1.0F,
            apply ? R6ModelController.LEG_Z : 1.0F);
        applyScale(model.leftLeg,
            apply ? R6ModelController.LEG_X : 1.0F,
            apply ? R6ModelController.LEG_Y : 1.0F,
            apply ? R6ModelController.LEG_Z : 1.0F);

        if (apply && !SitPoseController.isSitting()) {
            // 以身体侧面为基准向内贴合；原版臂盒是不对称的，不能按中心点计算。
            model.rightArm.pivotX = -R6ModelController.ARM_PIVOT_X;
            model.leftArm.pivotX = R6ModelController.ARM_PIVOT_X;
            model.rightLeg.pivotX = -R6ModelController.LEG_PIVOT_X;
            model.leftLeg.pivotX = R6ModelController.LEG_PIVOT_X;
        }

        // 这些部件已经在 PlayerEntityModel#setAngles 中复制过一次；这里再复制，
        // 确保开启和关闭都与最终的基础部件完全一致，不残留旧缩放或旧枢轴。
        model.hat.copyTransform(model.head);
        this.rightSleeve.copyTransform(model.rightArm);
        this.leftSleeve.copyTransform(model.leftArm);
        this.rightPants.copyTransform(model.rightLeg);
        this.leftPants.copyTransform(model.leftLeg);
        this.jacket.copyTransform(model.body);
    }

    private static void applyScale(ModelPart part, float x, float y, float z) {
        part.xScale = x;
        part.yScale = y;
        part.zScale = z;
    }
}
