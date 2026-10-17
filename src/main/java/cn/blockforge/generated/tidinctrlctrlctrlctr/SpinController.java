package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * 旋转类工具：让玩家绕自身 Y 轴（竖直轴）持续自转，分三种模式。
 *
 * <ul>
 *   <li>{@link Mode#VIEW}「旋转」：每个客户端 tick 递增地改变 yaw，并让 bodyYaw / headYaw
 *       一起跟上。第一人称视角、第三人称（F5）模型、以及发给服务器的朝向同步旋转；</li>
 *   <li>{@link Mode#MODEL}「角色旋转」：每个 tick 只推进 bodyYaw / headYaw，<b>不动 yaw</b>。
 *       第三人称（F5）下能看到角色模型转圈，但第一人称视角保持不转——已用反编译确认
 *       LivingEntityRenderer 渲染躯干/头部时读的是 prevBodyYaw→bodyYaw、prevHeadYaw→headYaw
 *       的插值，而第一人称相机只读 Entity#getYaw(tickDelta)，两者互不干涉。
 *       WASD 移动、攻击、放置等仍按视角朝向走（原版逻辑），行为不受模型旋转影响。</li>
 *   <li>{@link Mode#AIRPLANE}「抬臂旋转」：旋转行为与「角色旋转」相同（模型转、视角不转），
 *       另外把角色模型的双臂水平抬起（飞机姿势，左臂向左、右臂向右向外平举）。抬臂由
 *       {@code BipedEntityModelMixin} 在 {@code BipedEntityModel#setAngles} 末尾把两臂 roll
 *       覆盖成 ±90° 实现，袖子是手臂的子部件会跟着抬；只在第三人称（F5）下生效，
 *       第一人称画面不受影响。</li>
 * </ul>
 *
 * <p>三种模式共用一组速度 / 方向档位，且互斥：开一个会自动关掉另一个。
 * 只改朝向不改坐标，因此不会把人传送、也不会被挤进墙里。
 * 「角色旋转」不向服务器发送任何新朝向（本地玩家发包只带 yaw / pitch，服务器不会回发旋转覆盖），
 * 所以它是纯客户端表现：其他人看不到你在转。
 * 状态只在当前会话内有效，不写任何配置文件；玩家死亡、被移除或离开世界时自动停止。
 * 停止「角色旋转」/「抬臂旋转」时模型会归位回视角朝向，不留歪头的残影。
 * 「抬臂旋转」的双臂是逐帧渲染姿势：模式一关，下一帧手臂就自然放下，无需还原代码。
 */
@Environment(EnvType.CLIENT)
public final class SpinController {
    /** 旋转模式。{@code OFF} 为未旋转；三种旋转模式互斥。 */
    public enum Mode {
        OFF,
        /** 「旋转」：视角 + 模型一起转。 */
        VIEW,
        /** 「角色旋转」：只有角色模型转，视角朝向保持不变。 */
        MODEL,
        /** 「抬臂旋转」：同「角色旋转」的转法（视角不转），外加双臂水平平举（飞机姿势）。 */
        AIRPLANE
    }

    /** 每刻（1/20 秒）转过的角度：3° ≈ 6.7 秒一圈，9° ≈ 2.2 秒一圈，18° = 1 秒一圈，36° = 720°/秒。 */
    private static final int[] DEGREES_PER_TICK = {3, 9, 18, 36};
    private static final String[] SPEED_KEY = {"slow", "normal", "fast", "ultra"};

    private static Mode mode = Mode.OFF;
    private static int speedIndex = 1;
    private static boolean clockwise = true;

    /**
     * 「角色旋转」的模型朝向进度。不能读 player.getBodyYaw()——ClientPlayerEntity 把它
     * 覆盖成了直接返回 yaw，字段里的实际进度读不到，所以由我们自己维护。
     */
    private static float modelYaw;

    private SpinController() {
    }

    public static Mode getMode() {
        return mode;
    }

    /** 任意一种旋转正在进行（含「角色旋转」「抬臂旋转」）。 */
    public static boolean isSpinning() {
        return mode != Mode.OFF;
    }

    /** 「抬臂旋转」进行中：渲染侧据此把双臂覆盖成水平平举姿势。 */
    public static boolean isArmsRaised() {
        return mode == Mode.AIRPLANE;
    }

    /** 当前速度档位的语言键后缀：slow / normal / fast。 */
    public static String speedKey() {
        return SPEED_KEY[speedIndex];
    }

    public static boolean isClockwise() {
        return clockwise;
    }

    /** 当前速度换算成「度 / 秒」，用于按钮与提示文字。 */
    public static int degreesPerSecond() {
        return DEGREES_PER_TICK[speedIndex] * 20;
    }

    /** 当前速度档位下标（0=慢 … 3=极快），设置页的滑条要用。 */
    public static int speedIndex() {
        return speedIndex;
    }

    /** 直接设定档位下标（超出范围自动夹紧）。 */
    public static void setSpeedIndex(int index) {
        speedIndex = Math.max(0, Math.min(DEGREES_PER_TICK.length - 1, index));
    }

    /** 直接设定旋转方向（true = 顺时针）。 */
    public static void setClockwise(boolean isClockwise) {
        clockwise = isClockwise;
    }

    /**
     * 开关式切换：点同一个模式 → 停止；点另一个模式 → 直接切换过去。
     * 返回切换后的模式。
     */
    public static Mode toggle(Mode target) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (mode == target) {
            stop();
        } else {
            if ((target == Mode.MODEL || target == Mode.AIRPLANE) && player != null) {
                // 从当前视角朝向起步，避免开始瞬间模型跳角度。
                modelYaw = player.getYaw();
            }
            mode = target;
        }
        return mode;
    }

    /** 停止旋转；把模型归位回视角朝向（「角色旋转」「抬臂旋转」停在任意角度都能立刻回正）。 */
    public static void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        mode = Mode.OFF;
        if (player != null) {
            resetModelToYaw(player);
        }
    }

    /** 把躯干 / 头部的朝向与插值前值一起归位到视角 yaw，停止后模型立刻恢复自然姿态。 */
    private static void resetModelToYaw(ClientPlayerEntity player) {
        float yaw = player.getYaw();
        player.setBodyYaw(yaw);
        player.prevBodyYaw = yaw;
        player.setHeadYaw(yaw);
        player.prevHeadYaw = yaw;
    }

    /** 慢 → 正常 → 快 循环切换，返回新的档位语言键。 */
    public static String cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_KEY.length;
        return SPEED_KEY[speedIndex];
    }

    /** 切换顺 / 逆时针，返回切换后的方向。 */
    public static boolean toggleDirection() {
        clockwise = !clockwise;
        return clockwise;
    }

    /**
     * 由 {@code ClientTickEvents.END_CLIENT_TICK} 驱动。放在刻末执行有两个好处：
     * prev 朝向已在本次 tick 里刷新，渲染会在旧值与新值之间插值，转动看起来是平滑的；
     * 而「旋转」模式的发包逻辑比较的是「当前朝向 vs 上次发送的朝向」，所以服务器照样收得到旋转。
     * 「角色旋转」「抬臂旋转」模式则完全不触碰 yaw：相机、移动方向、发包朝向都保持原样。
     */
    public static void tick(MinecraftClient client) {
        if (mode == Mode.OFF) {
            return;
        }
        ClientPlayerEntity player = client.player;
        // 死亡、被移除、退出世界、回到主界面：自动停下，不把旋转状态带到下一局。
        if (player == null || client.world == null || player.isRemoved() || !player.isAlive()) {
            mode = Mode.OFF;
            return;
        }
        if (player.isSleeping()) {
            return;
        }
        float delta = DEGREES_PER_TICK[speedIndex] * (clockwise ? 1.0F : -1.0F);
        if (mode == Mode.VIEW) {
            float yaw = MathHelper.wrapDegrees(player.getYaw() + delta);
            player.setYaw(yaw);
            player.setBodyYaw(yaw);
            player.setHeadYaw(yaw);
        } else {
            // 「角色旋转」与「抬臂旋转」：视角 yaw 原封不动，只有躯干与头部绕 Y 轴转。
            // 「抬臂旋转」的双臂由渲染侧 Mixin 逐帧覆盖，与这里的转法互不干涉。
            modelYaw = MathHelper.wrapDegrees(modelYaw + delta);
            player.setBodyYaw(modelYaw);
            player.setHeadYaw(modelYaw);
        }
    }
}
