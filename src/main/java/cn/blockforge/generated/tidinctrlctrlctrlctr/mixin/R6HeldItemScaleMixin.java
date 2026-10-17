package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.R6ModelController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「转R6」的手持物品补偿：第三人称下拿在手里的物品，其矩阵是
 * {@code HeldItemFeatureRenderer#renderItem} 里先乘上手臂部件的
 * {@code setArmAngle}（1.20.1 中 {@code ModelPart.rotate} 含「平移→旋转→缩放」
 * 三步）再摆角度平移的——手臂被 R6 加粗 ×1.84 后，物品会跟着变宽变厚、
 * 握持偏移也被放大。
 *
 * <p>这里在 {@code setArmAngle} 调用之后（后续旋转/平移之前）立刻乘上
 * 手臂缩放之逆，之后的矩阵就与原版完全一致：物品保持原版大小与握持姿势，
 * 只是随外移后的肩枢轴一起移到加宽身体的手边。守卫与「转R6」本体同一入口
 * （{@link R6ModelController#isApplied}），开关关闭或其它玩家渲染时不注入任何矩阵。
 *
 * <p>该类也被女巫、村民等生物的持物渲染器共用，守卫保证只有本地玩家命中；
 * 望远镜贴脸特效走另一条独立路径（按头部矩阵），属罕见边角，未做补偿。
 */
@Environment(EnvType.CLIENT)
@Mixin(HeldItemFeatureRenderer.class)
public abstract class R6HeldItemScaleMixin {
    @Inject(
        method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;"
            + "Lnet/minecraft/client/render/model/json/ModelTransformationMode;Lnet/minecraft/util/Arm;"
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/model/ModelWithArms;"
                + "setArmAngle(Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;)V",
            shift = At.Shift.AFTER))
    private void r6Item$neutralizeArmScale(LivingEntity entity, ItemStack stack,
                                           ModelTransformationMode mode, Arm arm,
                                           MatrixStack matrices,
                                           VertexConsumerProvider vertexConsumers, int light,
                                           CallbackInfo info) {
        if (R6ModelController.isApplied(entity)) {
            matrices.scale(1.0F / R6ModelController.ARM_X,
                           1.0F / R6ModelController.ARM_Y,
                           1.0F / R6ModelController.ARM_Z);
        }
    }
}
