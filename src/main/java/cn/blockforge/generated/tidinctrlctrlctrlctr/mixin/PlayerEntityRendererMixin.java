package cn.blockforge.generated.tidinctrlctrlctrlctr.mixin;

import cn.blockforge.generated.tidinctrlctrlctrlctr.ModelHideController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「人物模型隐身」的渲染侧：本地玩家在第三人称下的整次模型渲染在入口处被取消。
 *
 * <p>注入 {@code PlayerEntityRenderer} 的玩家签名重载是安全的：该类同时桥接
 * {@code LivingEntity}/{@code Entity} 两个更宽签名的 {@code render}，桥接方法都会
 * 汇聚到这个实现体，所以在 HEAD 取消一次即可覆盖所有入口；而第一人称的手部
 * 走 {@code renderRightArm / renderLeftArm} 独立通道，不经过这里，不受影响。
 *
 * <p>该渲染器对所有在线玩家都会调用，守卫放在
 * {@link ModelHideController#shouldHide(AbstractClientPlayerEntity)}：
 * 只有「被渲染实体 == client.player 且开关打开」才取消，其它玩家一律照常渲染。
 */
@Environment(EnvType.CLIENT)
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(
        method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;"
            + "FFLnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        cancellable = true)
    private void hideModel$skipSelf(AbstractClientPlayerEntity player, float yaw, float tickDelta,
                                    MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                    int light, CallbackInfo ci) {
        if (ModelHideController.shouldHide(player)) {
            ci.cancel();
        }
    }
}
