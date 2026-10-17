package cn.blockforge.autofish;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 自动钓鱼：先用色带宽度与上下描边确认条体，再匹配用户的 5×15 双箭头指针贴图。
 * 背景白块也能满足旧的"上下亮色连续段"判据，因此必须校验白芯、冰蓝侧翼、深色描边的
 * 完整排列，不能仅凭指针色竖线确认指针。
 *
 * <p>命中只认当前读回帧：指针第 7 行是透明缝，位于条体第 4 行，缝中内部三列必须有
 * 连续真绿才允许收线。不外扩命中范围，不沿用旧绿色布局，也不以两帧之间的扫掠推定命中。
 * 格心采样存在半格定位偏差，故指针两侧各保留一格安全边距，优先避免尚未压绿就收线。
 *
 * <p>仅在持竿、HUD 可见、功能面板关闭时扫描。自动重抛要求本会话见过条体且条体消失
 * 至少一秒。读屏连续三帧近黑时切换主帧缓冲/窗口后缓冲；角标亮像仅作读回内容诊断。
 *
 * <p>反应速度：收线判定由 GameRenderer.render 逐帧驱动（每秒 60~120 次，而非 20 刻），
 * 判定准则不变。全屏扫描定位到条体后缓存其几何（左右端、条顶、缩放），之后每帧只读回
 * 条体小区域并复核指针压绿——读回面积缩小一个量级以上，指针扫得再快每个采样间隔也只
 * 移动几个像素，不会从两次采样中间穿过绿段。条体复核连续两帧失败或窗口尺寸变化即作废
 * 缓存，退回全屏扫描重新定位。
 *
 * <p>溅水花咬钩触发：屏幕字幕「浮漂：溅起水花」的源头是音效 entity.fishing_bobber.splash
 * （原版的浮漂落水声是另一个音效 entity.generic.splash，不会混淆）。SoundSystem.play
 * 每放行一个音效实例都会经 {@link #onSplashSound}，命中该音效就记下声源与时刻，由
 * frame() 逐帧消费：手持钓竿、浮漂在水中、未在持物使用状态、钓鱼条不在屏上、声源落在
 * 浮漂或玩家附近且过了收线冷却，才按下右键（覆盖原版在浮漂处播声与服务端插件在玩家处
 * 播声两种）。一次咬钩只按一次：按下即解除武装，浮漂消失再抛竿才重新武装。字幕开关
 * 关不掉本机制（注入在音效系统本身，早于字幕判断）。
 */
@Environment(EnvType.CLIENT)
public final class AutoFishController {
    /** 贴图色带内容区宽 176（x=4..179），bar1..bar9 通用。 */
    private static final int BAND_WIDTH = 176;
    private static final int KIND_RED = 0;
    private static final int KIND_ORANGE = 1;
    private static final int KIND_YELLOW = 2;
    private static final int KIND_GREEN = 3;
    private static final int KIND_NONE = 4;
    /** 彩虹鱼条里的冷色段：只用于"这到底是不是一条鱼条"的覆盖判定，不算命中色。 */
    private static final int KIND_CYAN = 5;
    private static final int KIND_BLUE = 6;
    private static final int KIND_PURPLE = 7;

    /** 指针贴图 5×15：上箭头行 0..6、第 7 行整行透明缝、下箭头行 8..14。 */
    private static final int POINTER_W = 5;
    private static final int POINTER_H = 15;
    /** 指针垂直居中压在条体上：贴图顶 = 条顶 - 3·scale（透明缝正好落在颜色行中间）。 */
    private static final float POINTER_TOP_OFFSET_ROWS = -3.0F;
    /** 模板匹配逐通道容差，允许缩放边缘与截图压缩产生的偏色。 */
    private static final int POINTER_COLOR_TOL = 64;
    /** 不透明 texel 的最低匹配比例。 */
    private static final float POINTER_MATCH_RATIO = 0.9F;

    private static final long SCAN_INTERVAL_NANOS = 20_000_000L;
    /** 快通道读回间隔：逐帧驱动下最快约 160Hz，高于常见帧率即等于每帧一采。 */
    private static final long ROI_SCAN_INTERVAL_NANOS = 6_000_000L;
    /** 条体几何缓存寿命：每帧复核成功都会续期，超时后退回全屏扫描定位。 */
    private static final long BAR_CACHE_TTL_NANOS = 2_000_000_000L;
    /** 连续复核失败多少帧后判定条体已不在原位（作废缓存）。 */
    private static final int ROI_MISS_LIMIT = 2;
    /** 缓存有效期间仍按此周期做一次全屏精扫：刷新候选条顶，兜底快通道几何偏差。 */
    private static final long FULL_REFRESH_NANOS = 250_000_000L;
    private static final long REEL_COOLDOWN_NANOS = 700_000_000L;
    /**
     * 重新投掷延迟（毫秒）：收线 / 咬钩之后隔多久再抛竿。
     * 可在「自动钓鱼设置」页里用滑条改，并写进 config/ 目录，重启后仍然生效。
     */
    private static int recastDelayMs = 1500;
    /** 重新投掷延迟的随机抖动百分比（0~100）：打散固定节奏，避免被插件当成脚本。 */
    private static int recastJitterPercent = 0;
    /** 「浮漂：溅起水花」音效触发咬钩的开关。 */
    private static boolean splashTriggerEnabled = true;
    /**
     * 多人游戏检测：不依赖客户端浮漂实体是否存在，只凭「溅水花」音效 + 手持钓竿就按右键。
     *
     * <p>单人原版里浮漂实体一定在，等它是安全的；多人服务器上插件常常在浮漂实体同步、
     * 被插件清理（clearlag）或干脆没发给客户端的时候就已经播了咬钩音效，
     * 严格等实体就会漏按。开启后声源半径也从 6 格放宽到 16 格。
     */
    private static boolean multiplayerMode = false;
    private static final java.util.Random RANDOM = new java.util.Random();
    /** 条体最近仍在屏幕上时不自动重抛，避免在收线/结算动画期间乱按右键。 */
    private static final long RECAST_BAR_HOLD_NANOS = 1_000_000_000L;
    /** 鱼条在这么久之内出现过才算"还在屏上"；超时即认为小游戏结束。 */
    private static final long BAR_ACTIVE_NANOS = 1_500_000_000L;
    /**
     * 浮漂出现后的"落水宽限期"：抛竿入水播的是同一个溅水音效，这段时间内的溅水不算咬钩。
     * 原版咬钩一般要 5 秒以上，2.5 秒的宽限很安全。
     */
    private static final long CAST_GRACE_NANOS = 2_500_000_000L;
    private static final float MIN_SCALE = 0.8F;
    /** 自定义钓鱼 HUD 可将 256px 贴图放大到全屏宽度附近；截图对应约 8 倍。 */
    private static final float MAX_SCALE = 16.0F;
    /** 色带里允许的最大断口（指针遮挡造成的缺口，按最大缩放下的指针宽 + 余量）。 */
    private static final int GAP_MERGE = 30;

    private static boolean enabled;
    private static long nextScanAt;
    private static long nextCastAt;
    private static long lastReelAt;
    private static long lastBarSeenAt;
    /** 当前这个浮漂是何时第一次被看到的；0 = 现在没有浮漂。用于落水宽限期。 */
    private static long hookAppearAt;

    private static ByteBuffer pixels;
    private static int bufferCapacity;
    /** 指针模板：POINTER_W*POINTER_H 的期望 RGB 与不透明掩码，首次使用时从贴图加载。 */
    private static int[] templateRgb;
    private static boolean[] templateOpaque;
    private static int templateOpaqueCount;
    /** 读屏来源：0=客户端主帧缓冲，1=窗口默认帧缓冲（后缓冲）。全黑时自动切换。 */
    private static int readMode;
    private static int darkStreak;
    /** 最近一次读回画面里非黑采样点的百分比（诊断：0 表示读屏拿到黑帧）。 */
    private static int frameBrightnessPct;
    /** 行扫描轮转相位：3 行步进、每帧错开一行，颜色行最薄 2~3 像素也不会漏。 */
    private static int scanRowPhase;

    /** 条体几何缓存：全屏扫描定位成功后，逐帧只读回条体附近小区域复核。 */
    private static boolean barCached;
    private static int cachedRunStart;
    private static int cachedRunEnd;
    private static int cachedTop;
    private static float cachedScale;
    private static long cachedAtNs;
    private static int cachedFrameWidth;
    private static int cachedFrameHeight;
    private static int cacheMissStreak;
    private static long nextRoiScanAt;
    /** 缓存期间最近一次全屏精扫时间：兜底刷新候选条顶。 */
    private static long lastFullRefreshAt;
    /** 当前 pixels 缓冲对应的读回矩形（图像坐标，y 向下）；全屏读回时即整屏。 */
    private static int regionX;
    private static int regionY;
    private static int regionWidth;
    private static int regionHeight;

    /** 溅水花触发：音效系统记下最近一次「浮漂：溅起水花」声源（世界坐标）与时刻。 */
    private static volatile boolean splashPending;
    private static long splashAtNs;
    private static double splashX;
    private static double splashY;
    private static double splashZ;
    /** 一次咬钩只按一次：按下即解除武装，浮漂消失（下一次抛竿前）才重新武装。 */
    private static boolean splashArmed = true;
    /** 声源须落在浮漂或玩家此半径（平方，6 格）内：远处的浮漂溅水（别人的）不算。 */
    private static final double SPLASH_MAX_DISTANCE_SQ = 36.0;
    /** 多人模式下的声源半径（平方，16 格）：插件常在玩家处播声，浮漂实体也可能还没同步。 */
    private static final double MULTIPLAYER_SPLASH_DISTANCE_SQ = 256.0;
    /** 溅水事件超过此时长未被 frame() 消费即作废：宁可不按，不补一次迟到的右键。 */
    private static final long SPLASH_STALE_NANOS = 250_000_000L;
    /** 钓鱼条近在此窗口内出现过（指针判定接管）时，溅水事件让路作废。 */
    private static final long SPLASH_BAR_HOLD_NANOS = 500_000_000L;

    /** 诊断计数：扫描帧数 / 看到条体次数 / 绿区命中次数 / 实际按下右键次数。 */
    private static int scanCount;
    private static int barSeenCount;
    private static int greenHitCount;
    private static int reelCount;
    /** 诊断计数：听到「浮漂：溅起水花」次数 / 由它触发按下右键次数。 */
    private static int splashSeenCount;
    private static int splashReelCount;
    private static boolean barSeenThisScan;

    // -----------------------------------------------------------------------
    // 指针追踪与提前量：随机速度下命中率的关键
    //   判定不再"先求绿段边界、再比区间"——指针压在绿段正中间时，指针自己的像素
    //   会把绿段切成左右两段；取最长的一段之后，指针中心恰好落在两段之间的缝里，
    //   于是出现"明明在绿里却判定失败"（绿在两侧就必失败）。
    //   改成沿速度方向直接探测：当前帧读指针中心那一列的颜色，提前量则从指针中心
    //   沿速度方向逐像素前探。任意段数、任意布局都成立，也不再需要求边界。
    // -----------------------------------------------------------------------
    /** 目标色（默认取实测的条体绿 #34ED53）：判定只认它，容差 ±targetTol。 */
    private static int targetRgb = 0x34ED53;
    /** 色相容差（度）：像素色相与调色板里最近色的偏差超过它就不算鱼条颜色。 */
    private static int targetTol = 40;
    /** 自动识别目标色：收到标题/副标题里的颜色提示时自动切换 targetRgb。 */
    private static boolean autoTarget = true;
    /**
     * 自动重抛开关。关掉后只收线、不抛竿——想单独验证"是不是该收才收"时用，
     * 也避免任何"抛→收→抛"的循环干扰观察。
     */
    private static boolean autoRecast = true;
    /** 最近一次由提示自动设定目标色的时刻；0 = 从没自动设过。 */
    private static long autoTargetAtNs;
    /** 提前量最多前探多少像素：速度估计抖动、或指针在端点减速时防止冲过头。 */
    private static final int MAX_LEAD_PX = 16;
    /** 最近一次读到的指针中心列颜色：调目标色与容差时唯一的参照，HUD 里显示。 */
    private static int pointerCenterRgb;
    /** 最近一次定位到的指针中心列。 */
    private static int pointerX = -1;
    private static long pointerSeenNs;
    /** 指针横向速度，单位 像素/毫秒；0 表示还没测出来。 */
    private static double pointerVx;
    /** 最近一帧的瞬时速度（未平滑）。平滑值在减速时会虚高，用它取较小值兜底。 */
    private static double pointerVxInstant;
    /** 最近一次判定时目标色距指针中心多少像素；-1 = 本帧没探到。HUD 调试用。 */
    private static int lastTargetDistancePx = -1;
    /** 提前量（毫秒）：补偿"读回画面 → 判定 → 点击 → 服务端处理"这条延迟链。 */
    private static int leadMs = 40;
    /** 诊断计数：判定收线的次数 / 其中靠速度外推提前点下的次数。 */
    private static int hitDecisionCount;
    private static int predictedHitCount;

    private AutoFishController() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int scanCount() {
        return scanCount;
    }

    public static int barSeenCount() {
        return barSeenCount;
    }

    public static int greenHitCount() {
        return greenHitCount;
    }

    public static int reelCount() {
        return reelCount;
    }

    public static int splashSeenCount() {
        return splashSeenCount;
    }

    public static int splashReelCount() {
        return splashReelCount;
    }

    /** 最近一次读回画面的非黑采样百分比；持续为 0 说明该设备的读屏通路拿不到内容。 */
    public static int frameBrightness() {
        return frameBrightnessPct;
    }

    /** 当前读屏来源：0=主帧缓冲，1=窗口后缓冲。 */
    public static int readSource() {
        return readMode;
    }

    /** 当前收线提前量（毫秒）。 */
    public static int leadMs() {
        return leadMs;
    }

    /** 判定收线的总次数（诊断用）。 */
    public static int hitDecisionCount() {
        return hitDecisionCount;
    }

    /** 其中靠速度外推提前点下的次数（诊断用：越接近总次数说明指针越快）。 */
    public static int predictedHitCount() {
        return predictedHitCount;
    }

    /** 循环切换提前量：0 → 20 → 40 → 60 → 80 → 120 → 0 毫秒。 */
    public static int cycleLead() {
        int[] steps = {0, 20, 40, 60, 80, 120};
        int next = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == leadMs) {
                next = (i + 1) % steps.length;
                break;
            }
        }
        leadMs = steps[next];
        // 换档后旧的速度估计不再可信，清掉重新测。
        pointerVx = 0.0;
        pointerSeenNs = 0L;
        return leadMs;
    }

    // -----------------------------------------------------------------------
    // 设置项的读写（「自动钓鱼设置」页用的是这一组）
    // -----------------------------------------------------------------------

    public static void setLeadMs(int ms) {
        leadMs = Math.max(0, Math.min(500, ms));
        pointerVx = 0.0;
        pointerSeenNs = 0L;
    }

    public static int recastDelayMs() {
        return recastDelayMs;
    }

    public static void setRecastDelayMs(int ms) {
        recastDelayMs = Math.max(500, Math.min(5000, ms));
    }

    public static int recastJitterPercent() {
        return recastJitterPercent;
    }

    public static void setRecastJitterPercent(int percent) {
        recastJitterPercent = Math.max(0, Math.min(100, percent));
    }

    public static boolean splashTriggerEnabled() {
        return splashTriggerEnabled;
    }

    public static void setSplashTriggerEnabled(boolean on) {
        splashTriggerEnabled = on;
    }

    public static int targetTol() {
        return targetTol;
    }

    /** 容差范围 10~90 度：太小会漏判（抗抖动差），太大会把不相干的颜色也收进来。 */
    public static void setTargetTol(int tol) {
        targetTol = Math.max(10, Math.min(90, tol));
    }

    /** 最近一次收线的触发来源：0=还没收过, 1=溅水花, 2=色块命中。HUD 诊断用。 */
    private static int lastReelReason;
    private static long lastReelReasonAtNs;

    /** HUD 用：上次收线的来源与时间，例如「溅水 320ms前」。 */
    public static String lastReelReasonText() {
        if (lastReelReason == 0) {
            return "无";
        }
        long ms = (System.nanoTime() - lastReelReasonAtNs) / 1_000_000L;
        return (lastReelReason == 1 ? "溅水" : "色块") + " " + ms + "ms前";
    }

    /** 当前是不是正处于浮漂刚出现的宽限期（HUD 用）。 */
    public static boolean inCastGrace() {
        return hookAppearAt != 0L && System.nanoTime() - hookAppearAt < CAST_GRACE_NANOS;
    }

    /** 浮漂出现多久了（毫秒）；0 = 现在没有浮漂。 */
    public static long hookAgeMs() {
        return hookAppearAt == 0L ? 0L : (System.nanoTime() - hookAppearAt) / 1_000_000L;
    }

    public static boolean autoTarget() {
        return autoTarget;
    }

    public static boolean autoRecast() {
        return autoRecast;
    }

    public static void setAutoRecast(boolean on) {
        autoRecast = on;
    }

    public static void setAutoTarget(boolean on) {
        autoTarget = on;
    }

    /**
     * 由 {@link TitleWatcher} 调用：服务器提示里识别到目标色时，自动把判定色切过去。
     * 自动识别关掉时只记录、不生效——手动填的颜色不会被提示覆盖。
     */
    public static void onTargetColorHint(int rgb) {
        if (rgb <= 0) {
            return;
        }
        autoTargetAtNs = System.nanoTime();
        if (autoTarget) {
            targetRgb = rgb & 0xFFFFFF;
        }
    }

    /** 当前目标色是否来自最近的提示（10 秒内）。HUD 用来标注来源。 */
    public static boolean targetFromHint() {
        return autoTargetAtNs != 0L && System.nanoTime() - autoTargetAtNs < 10_000_000_000L;
    }

    public static int targetRgb() {
        return targetRgb;
    }

    /** 调色板名字表（设置页下拉用；返回副本，避免被外部改）。 */
    public static String[] paletteNames() {
        return PALETTE_NAME.clone();
    }

    /** 当前目标色归到调色板第几个；-1 = 不属于调色板任何一格。 */
    public static int targetPaletteIndex() {
        return targetClass();
    }

    /** 设置页下拉：按名字把目标色设成调色板里的某一格。 */
    public static void setTargetPaletteName(String name) {
        if (name == null) {
            return;
        }
        for (int i = 0; i < PALETTE_NAME.length; i++) {
            if (PALETTE_NAME[i].equals(name)) {
                targetRgb = PALETTE_RGB[i];
                return;
            }
        }
    }

    public static String targetRgbHex() {
        return String.format("#%06X", targetRgb & 0xFFFFFF);
    }

    /** 解析 #RRGGBB 形式；格式不对就保持原值并返回 false。 */
    public static boolean setTargetRgbHex(String hex) {
        if (hex == null) {
            return false;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return false;
        }
        try {
            targetRgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            return true;
        } catch (NumberFormatException bad) {
            return false;
        }
    }

    /** 最近一次读到的指针中心列颜色（0 = 还没读到 / 区域外）。 */
    public static int pointerCenterRgb() {
        return pointerCenterRgb;
    }

    /** 最近一次判定时目标色距指针中心的像素数；-1 = 本帧没探到。 */
    public static int lastTargetDistancePx() {
        return lastTargetDistancePx;
    }

    public static boolean multiplayerMode() {
        return multiplayerMode;
    }

    public static void setMultiplayerMode(boolean on) {
        multiplayerMode = on;
    }

    /** 配置文件：放在 Fabric 的 config 目录下，纯文本 properties，坏了也不影响启动。 */
    private static final String CONFIG_FILE = "tidin_ctrl_ctrl_ctrl_ctr.properties";

    /** 读回上次保存的自动钓鱼参数；文件缺失或损坏时静默保持内置默认值。 */
    public static void loadConfig() {
        Path path = configPath();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (Exception ignored) {
            return;
        }
        leadMs = readInt(props, "lead_ms", leadMs, 0, 500);
        recastDelayMs = readInt(props, "recast_delay_ms", recastDelayMs, 500, 5000);
        recastJitterPercent = readInt(props, "recast_jitter_percent", recastJitterPercent, 0, 100);
        String splash = props.getProperty("splash_trigger");
        if (splash != null) {
            splashTriggerEnabled = Boolean.parseBoolean(splash.trim());
        }
        String multiplayer = props.getProperty("multiplayer_mode");
        if (multiplayer != null) {
            multiplayerMode = Boolean.parseBoolean(multiplayer.trim());
        }
        String targetHex = props.getProperty("target_rgb");
        if (targetHex != null) {
            setTargetRgbHex(targetHex);
        }
        targetTol = readInt(props, "target_tol", targetTol, 10, 90);
        String auto = props.getProperty("auto_target");
        if (auto != null) {
            autoTarget = Boolean.parseBoolean(auto.trim());
        }
        String recast = props.getProperty("auto_recast");
        if (recast != null) {
            autoRecast = Boolean.parseBoolean(recast.trim());
        }
    }

    /** 把当前参数写回配置文件；写失败就算了，功能照常，只是不持久化。 */
    public static void saveConfig() {
        Path path = configPath();
        if (path == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty("lead_ms", String.valueOf(leadMs));
        props.setProperty("recast_delay_ms", String.valueOf(recastDelayMs));
        props.setProperty("recast_jitter_percent", String.valueOf(recastJitterPercent));
        props.setProperty("splash_trigger", String.valueOf(splashTriggerEnabled));
        props.setProperty("multiplayer_mode", String.valueOf(multiplayerMode));
        props.setProperty("target_rgb", targetRgbHex());
        props.setProperty("target_tol", String.valueOf(targetTol));
        props.setProperty("auto_target", String.valueOf(autoTarget));
        props.setProperty("auto_recast", String.valueOf(autoRecast));
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Ctrl function menu - auto fish");
            }
        } catch (Exception ignored) {
            // 只读目录 / 权限不足等情况：静默失败
        }
    }

    private static Path configPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readInt(Properties props, String key, int fallback, int min, int max) {
        try {
            String raw = props.getProperty(key);
            if (raw == null) {
                return fallback;
            }
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** 直接设定开关状态（设置页的开关用；状态没变就不重置计数器）。 */
    public static void setEnabled(boolean on) {
        if (on != enabled) {
            toggle();
        }
    }

    public static boolean toggle() {
        enabled = !enabled;
        long now = System.nanoTime();
        nextScanAt = 0L;
        lastReelAt = 0L;
        lastBarSeenAt = 0L;
        darkStreak = 0;
        barCached = false;
        cacheMissStreak = 0;
        nextRoiScanAt = 0L;
        scanCount = 0;
        barSeenCount = 0;
        greenHitCount = 0;
        reelCount = 0;
        splashPending = false;
        splashArmed = true;
        splashSeenCount = 0;
        splashReelCount = 0;
        // 新一轮钓鱼：指针追踪重新建立，免得带到上一竿的残留状态
        pointerX = -1;
        pointerSeenNs = 0L;
        pointerVx = 0.0;
        hitDecisionCount = 0;
        predictedHitCount = 0;
        nextCastAt = now + recastDelayNanos();
        return enabled;
    }

    /** 由 ClientTickEvents.END_CLIENT_TICK 驱动：只负责自动重抛（时序判定，秒级精度够用）。 */
    /**
     * 当前屏幕上有没有"活着的"钓鱼条。
     *
     * <p>{@link #BAR_ACTIVE_NANOS} 内识别到过条体就算有；超时即认为小游戏已结束。
     * 原版普通钓鱼根本没有条，本方法恒为 false —— 重抛逻辑正是靠它同时兼容两种场景。
     */
    public static boolean barActive() {
        return lastBarSeenAt != 0L && System.nanoTime() - lastBarSeenAt < BAR_ACTIVE_NANOS;
    }

    public static void tick(MinecraftClient client) {
        if (!enabled) {
            return;
        }
        if (client.player == null || client.world == null || client.interactionManager == null) {
            enabled = false;
            return;
        }
        // 暂停条件：HUD 隐藏时不做任何操作（连重抛也不做）。
        if (client.options.hudHidden) {
            return;
        }
        PlayerEntity player = client.player;
        Hand rodHand = rodHand(player);
        if (rodHand == null) {
            return;
        }
        long now = System.nanoTime();

        // 自动重抛：仅在本会话识别到过条体、且条体已消失 ≥1 秒后才按右键。
        // 从未识别到条体时一次都不按——避免任何"盲按"打断小游戏判定。
        // （曾经放开过"没有鱼条也重抛"以支持原版普通钓鱼，但那会让"落水声被当成咬钩"
        //   变成无限循环：抛→收→抛…，所以撤回，只保留有鱼条的服务器。）
        if (autoRecast && barSeenCount > 0 && client.currentScreen == null && !player.isUsingItem()
            && player.fishHook == null && now >= nextCastAt
            && now - lastBarSeenAt >= RECAST_BAR_HOLD_NANOS) {
            client.interactionManager.interactItem(player, rodHand);
            nextCastAt = now + recastDelayNanos();
        }
    }

    /**
     * 由 GameRenderer.render 的 HEAD 注入逐帧驱动：读上一帧画面做识别与自动收线。
     * 收线必须比游戏刻更频繁——指针扫得快时，20Hz 采样会整段穿过绿色区。
     */
    public static void frame(MinecraftClient client) {
        if (!enabled) {
            return;
        }
        // 画面已开始渲染但世界未就绪等边角情况：只认 tick 的判定，这里静默跳过。
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }
        // HUD 隐藏、或打开了任何界面时都不读屏：
        //  · 界面开着不可能钓鱼，读屏纯属浪费；
        //  · 更关键的是 glReadPixels 是同步阻塞调用，会和 GUI 渲染抢 GPU，
        //    表现为"打开设置页/背包时卡一下"。这一条把它彻底断掉。
        if (client.options.hudHidden || client.currentScreen != null) {
            return;
        }
        PlayerEntity player = client.player;
        Hand rodHand = rodHand(player);
        if (rodHand == null) {
            return;
        }

        long now = System.nanoTime();

        // 溅水花触发：字幕「浮漂：溅起水花」出现（音效已记下）→ 立即按右键咬钩，
        // 之后钓鱼条出现，收线交还给下面的指针压绿判定。
        FishingBobberEntity hook = player.fishHook;
        // 记录浮漂"刚出现"的时刻：抛竿入水播的也是 entity.fishing_bobber.splash，
        // 那一瞬间的声音不是鱼咬钩，必须靠这个时间戳挡掉。
        if (hook == null) {
            hookAppearAt = 0L;
        } else if (hookAppearAt == 0L) {
            hookAppearAt = now;
        }
        if (splashClickDue(hook != null, true, player.isUsingItem(),
            barCached || now - lastBarSeenAt < SPLASH_BAR_HOLD_NANOS,
            player.getX(), player.getY(), player.getZ(),
            hook == null ? 0.0 : hook.getX(), hook == null ? 0.0 : hook.getY(), hook == null ? 0.0 : hook.getZ(),
            now)) {
            client.interactionManager.interactItem(player, rodHand);
            nextCastAt = now + recastDelayNanos();
            lastReelReason = 1;
            lastReelReasonAtNs = now;
        }

        // 命中绿色段：收线。按住钓竿走原版松开路径，否则走原版按下右键路径。
        if (now - lastReelAt >= REEL_COOLDOWN_NANOS && scanFrame(client, now)) {
            boolean clicked = false;
            if (player.isUsingItem()) {
                if (player.getActiveItem().isOf(Items.FISHING_ROD)) {
                    client.interactionManager.stopUsingItem(player);
                    clicked = true;
                }
            } else {
                client.interactionManager.interactItem(player, rodHand);
                clicked = true;
            }
            if (clicked) {
                lastReelAt = now;
                nextCastAt = now + recastDelayNanos();
                reelCount++;
                lastReelReason = 2;
                lastReelReasonAtNs = now;
            }
        }
    }

    /** 本次重新投掷要等的纳秒数：基准值 ± 抖动百分比，最低 200ms 兜底。 */
    private static long recastDelayNanos() {
        long base = recastDelayMs * 1_000_000L;
        if (recastJitterPercent > 0) {
            double factor = 1.0 + (RANDOM.nextDouble() - 0.5) * 2.0 * (recastJitterPercent / 100.0);
            base = (long) (base * factor);
        }
        return Math.max(200_000_000L, base);
    }

    private static Hand rodHand(PlayerEntity player) {
        if (player.getMainHandStack().isOf(Items.FISHING_ROD)) {
            return Hand.MAIN_HAND;
        }
        if (player.getOffHandStack().isOf(Items.FISHING_ROD)) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    /**
     * 由 SoundSystem.play 的 HEAD 注入调用（客户端每个音效实例都经过这里）：命中
     * entity.fishing_bobber.splash——屏幕字幕「浮漂：溅起水花」的声源——就记下声源与
     * 时刻，交由 frame() 逐帧校验消费。不按音量/音高过滤：服务端插件可能以不同音量
     * 播放同一音效，只要字幕会出现就该触发；浮漂落水声是另一个音效（generic.splash），
     * 不会混淆。本方法只写字段（volatile 写收尾保证次序），从网络线程发声也安全。
     */
    public static void onSplashSound(SoundInstance sound) {
        if (!enabled || !splashTriggerEnabled
            || !SoundEvents.ENTITY_FISHING_BOBBER_SPLASH.getId().equals(sound.getId())) {
            return;
        }
        splashSeenCount++;
        splashX = sound.getX();
        splashY = sound.getY();
        splashZ = sound.getZ();
        splashAtNs = System.nanoTime();
        splashPending = true;
    }

    /**
     * 节流读取画面并识别"指针压在绿色段上"。缓存有效时走快通道：只读回条体小区域、
     * 逐帧复核（约 6ms 一次）；缓存作废或过期时走全屏扫描（20ms 一次）重新定位。
     */
    private static boolean scanFrame(MinecraftClient client, long now) {
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer == null) {
            return false;
        }
        int width = framebuffer.viewportWidth;
        int height = framebuffer.viewportHeight;
        if (width <= 0 || height <= 0) {
            return false;
        }

        if (barCached && now - cachedAtNs <= BAR_CACHE_TTL_NANOS) {
            if (now < nextRoiScanAt) {
                return false;
            }
            nextRoiScanAt = now + ROI_SCAN_INTERVAL_NANOS;
            if (scanRegion(framebuffer, width, height)) {
                return true;
            }
            if (barCached && now - lastFullRefreshAt < FULL_REFRESH_NANOS) {
                // 条体仍在原位，只是指针还没压绿：等下一帧，不必回落全屏扫描。
                return false;
            }
            // 缓存刚作废：或到了周期性全屏精扫的兜底时点，重新定位并刷新候选条顶。
        }

        if (now < nextScanAt) {
            return false;
        }
        nextScanAt = now + SCAN_INTERVAL_NANOS;
        lastFullRefreshAt = now;

        if (!readPixels(framebuffer, width, height, 0, 0, width, height)) {
            // 主帧缓冲读失败时立刻试一次窗口后缓冲。
            readMode = readMode == 0 ? 1 : 0;
            if (!readPixels(framebuffer, width, height, 0, 0, width, height)) {
                return false;
            }
        }
        measureBrightness(width, height);
        if (frameBrightnessPct < 2) {
            // 连续多帧全黑：这台设备的这条读屏通路拿不到内容，换另一条。
            if (++darkStreak >= 3) {
                readMode = readMode == 0 ? 1 : 0;
                darkStreak = 0;
            }
        } else {
            darkStreak = 0;
        }
        scanCount++;
        barSeenThisScan = false;
        scanRowPhase = (scanRowPhase + 1) % 3;
        boolean greenHit = detect(width, height);
        if (greenHit) {
            greenHitCount++;
        }
        return greenHit;
    }

    /**
     * 快通道：按缓存几何圈出条体小区域（含指针全身与安全边距）读回复核。
     * @return 指针是否压在绿色段上；false 且 barCached 变 false 表示缓存已作废。
     */
    private static boolean scanRegion(Framebuffer framebuffer, int width, int height) {
        if (width != cachedFrameWidth || height != cachedFrameHeight) {
            barCached = false;
            return false;
        }
        int x0 = Math.max(0, cachedRunStart - roiPadX(cachedScale));
        int x1 = Math.min(width, cachedRunEnd + 1 + roiPadX(cachedScale));
        int y0 = Math.max(0, cachedTop - roiPadTop(cachedScale));
        int y1 = Math.min(height, cachedTop + Math.round(8.0F * cachedScale) + roiPadBottom(cachedScale));
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            barCached = false;
            return false;
        }
        if (!readPixels(framebuffer, width, height, x0, y0, x1 - x0, y1 - y0)) {
            // 小区域读失败：作废缓存，回全屏路径处理（含读屏通路切换）。
            barCached = false;
            return false;
        }
        scanCount++;
        barSeenThisScan = false;
        boolean greenHit = verifyCachedBarOnGreen(width, height);
        if (greenHit) {
            greenHitCount++;
        }
        return greenHit;
    }

    /**
     * 读回矩形区域（图像坐标 y 向下，全屏读回即 0,0,width,height），并把 region 记账
     * 给 {@link #pixel}。glReadPixels 原点在左下，需换算 y。
     */
    private static boolean readPixels(Framebuffer framebuffer, int fullWidth, int fullHeight,
                                      int x, int y, int w, int h) {
        long requiredLong = (long) w * h * 4L;
        if (requiredLong <= 0L || requiredLong > Integer.MAX_VALUE - 8) {
            return false;
        }
        int required = (int) requiredLong;
        if (pixels == null || bufferCapacity < required) {
            bufferCapacity = required;
            pixels = BufferUtils.createByteBuffer(bufferCapacity);
        }
        int previousReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readMode == 0 ? framebuffer.fbo : 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            pixels.clear();
            GL11.glReadPixels(x, fullHeight - (y + h), w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            regionX = x;
            regionY = y;
            regionWidth = w;
            regionHeight = h;
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
        }
    }

    /** 网格采样 84 点统计非黑像素比例，用于诊断读屏通路。 */
    private static void measureBrightness(int width, int height) {
        int hit = 0;
        int total = 0;
        for (int sy = 1; sy < 8; sy++) {
            for (int sx = 1; sx < 13; sx++) {
                int color = pixel(width * sx / 13, height * sy / 8, width, height);
                total++;
                if (((color >>> 16) & 0xFF) + ((color >>> 8) & 0xFF) + (color & 0xFF) > 36) {
                    hit++;
                }
            }
        }
        frameBrightnessPct = total == 0 ? 0 : hit * 100 / total;
    }

    /**
     * 以亮绿像素为种子向左右扩展出完整色带，按宽 176 反推缩放；缩放合法且通用结构
     * 校验通过后，在色带里做指针模板匹配。本帧一旦识别到条体（无论指针是否命中）
     * 就结束扫描，开销封顶在一条色带上。
     */
    private static boolean detect(int width, int height) {
        for (int y = scanRowPhase; y < height; y += 3) {
            int x = 0;
            while (x < width) {
                if (!isGreenish(x, y, width, height)) {
                    x += 2;
                    continue;
                }
                int runStart = x;
                int runEnd = x;
                int probe = x - 1;
                while (probe >= 0 && x - probe <= GAP_MERGE + Math.round(BAND_WIDTH * MAX_SCALE)) {
                    if (bandKind(probe, y, width, height) != KIND_NONE) {
                        runStart = probe;
                        probe--;
                    } else if (probe < runStart - GAP_MERGE) {
                        break;
                    } else {
                        probe--;
                    }
                }
                probe = x + 1;
                while (probe < width && probe - runEnd <= GAP_MERGE + Math.round(BAND_WIDTH * MAX_SCALE)) {
                    if (bandKind(probe, y, width, height) != KIND_NONE) {
                        runEnd = probe;
                        probe++;
                    } else if (probe > runEnd + GAP_MERGE) {
                        break;
                    } else {
                        probe++;
                    }
                }
                x = runEnd + 1;

                int length = runEnd - runStart + 1;
                float scale = length / (float) BAND_WIDTH;
                if (scale < MIN_SCALE || scale > MAX_SCALE) {
                    continue;
                }
                int result = verifyBarAndPointerOnGreen(runStart, runEnd, y, scale, width, height);
                if (result == 2) {
                    return true;
                }
                if (result == 1) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 快通道读回区域边距：横向覆盖指针中心扫到条体端头外的全部采样列。 */
    private static int roiPadX(float scale) {
        return Math.round(5.5F * scale) + 4;
    }

    private static int roiPadTop(float scale) {
        return Math.round(4.0F * scale) + 4;
    }

    private static int roiPadBottom(float scale) {
        return Math.round(4.5F * scale) + 4;
    }

    /** 全屏扫描复核通过后记住条体几何，供逐帧快通道小区域读回复核。 */
    private static void rememberBarGeometry(int runStart, int runEnd, int top, float scale, int width, int height) {
        barCached = true;
        cachedRunStart = runStart;
        cachedRunEnd = runEnd;
        cachedTop = top;
        cachedScale = scale;
        cachedFrameWidth = width;
        cachedFrameHeight = height;
        cachedAtNs = System.nanoTime();
        cacheMissStreak = 0;
    }

    /**
     * 用缓存几何在当前读回区域内复核：条体仍在→指针压绿判定（判据与全屏完全一致）；
     * 条体连续 {@link #ROI_MISS_LIMIT} 帧不在原位→作废缓存退回全屏扫描。
     */
    private static boolean verifyCachedBarOnGreen(int width, int height) {
        if (matchesBar(cachedRunStart, cachedRunEnd, cachedTop, cachedScale, width, height)) {
            cacheMissStreak = 0;
            cachedAtNs = System.nanoTime();
            lastBarSeenAt = cachedAtNs;
            if (!barSeenThisScan) {
                barSeenThisScan = true;
                barSeenCount++;
            }
            return matchesPointerOnGreen(cachedRunStart, cachedRunEnd, cachedTop, cachedScale, width, height);
        }
        if (++cacheMissStreak >= ROI_MISS_LIMIT) {
            barCached = false;
        }
        return false;
    }

    /**
     * 溅水花触发的纯状态机（不含游戏类型，供离线回归）：消费 {@link #onSplashSound}
     * 记下的最近一次「浮漂：溅起水花」声事件。
     *
     * <p>规则：手持钓竿且浮漂在水中才按；处于持物使用状态、或钓鱼条近在场（指针判定
     * 接管）时让路作废；声源须落在浮漂或玩家 {@link #SPLASH_MAX_DISTANCE_SQ}（平方）
     * 半径内——原版在浮漂处播声、服务端插件常在玩家处播声，两种都认，远处的浮漂溅水
     * （别人的竿）丢弃。按下后解除武装，直到浮漂消失（下一次抛竿前）才重新武装：
     * 一次咬钩只按一次，不会在条体阶段补刀。事件超时（面板打开、暂停）自动作废。
     */
    static boolean splashClickDue(boolean hasHook, boolean rodHeld, boolean usingItem, boolean barActive,
                                  double playerX, double playerY, double playerZ,
                                  double hookX, double hookY, double hookZ, long now) {
        // 落水宽限：浮漂刚出现的那 2.5 秒内，溅水声基本是抛竿入水，不是鱼咬钩。
        // 原版这两个动作播的是同一个音效（entity.fishing_bobber.splash），
        // 不挡掉就会"一抛竿立刻收线"。
        if (hookAppearAt != 0L && now - hookAppearAt < CAST_GRACE_NANOS) {
            splashPending = false;
            return false;
        }
        if (!multiplayerMode && !splashArmed && !hasHook) {            // 浮漂已不在（收线/结算完成）：为下一次抛竿重新武装。
            // 多人模式下不依赖浮漂实体，所以不靠它的消失来重新武装，而是靠下面的冷却与
            // 「一次溅水一条事件」来防重复按。
            splashArmed = true;
        }
        if (!splashPending) {
            return false;
        }
        if (now - splashAtNs > SPLASH_STALE_NANOS) {
            splashPending = false;
            return false;
        }
        if (!splashArmed && !multiplayerMode) {
            splashPending = false;
            return false;
        }
        if (usingItem || barActive) {
            splashPending = false;
            return false;
        }
        if (!rodHeld || (!hasHook && !multiplayerMode)) {
            // 竿/浮漂未就位：保留事件等下一帧，超时由上面的时效检查作废。
            return false;
        }
        double dxHook = splashX - hookX;
        double dyHook = splashY - hookY;
        double dzHook = splashZ - hookZ;
        double dxPlayer = splashX - playerX;
        double dyPlayer = splashY - playerY;
        double dzPlayer = splashZ - playerZ;
        double nearestSq = Math.min(dxHook * dxHook + dyHook * dyHook + dzHook * dzHook,
            dxPlayer * dxPlayer + dyPlayer * dyPlayer + dzPlayer * dzPlayer);
        // 没有自己的浮漂实体时（多人模式），声源必须离玩家很近：
        // 插件常在玩家处播声，而别人的浮漂不会这么近——这条挡住"别人中鱼我们也按右键"。
        if (!hasHook && multiplayerMode) {
            double nearPlayerSq = dxPlayer * dxPlayer + dyPlayer * dyPlayer + dzPlayer * dzPlayer;
            if (nearPlayerSq > 16.0) {
                splashPending = false;
                return false;
            }
        }
        double maxDistanceSq = multiplayerMode ? MULTIPLAYER_SPLASH_DISTANCE_SQ : SPLASH_MAX_DISTANCE_SQ;
        if (nearestSq > maxDistanceSq) {
            splashPending = false;
            return false;
        }
        if (now - lastReelAt < REEL_COOLDOWN_NANOS) {
            splashPending = false;
            return false;
        }
        splashPending = false;
        splashArmed = false;
        lastReelAt = now;
        splashReelCount++;
        return true;
    }

    /** @return 0=不是条体；1=是条体但指针未压绿；2=指针压绿。 */
    private static int verifyBarAndPointerOnGreen(int runStart, int runEnd, int scanY, float scale, int width, int height) {
        // 扫描行落在颜色行（贴图第 3~5 行）内：条顶 ∈ [scanY-6·scale, scanY-3·scale]，
        // 以中心估计 scanY-4.5·scale 为起点向两侧扩散；错的候选先被便宜的描边检查拒掉。
        int centerTop = scanY - Math.round(4.5F * scale);
        int radius = Math.round(1.6F * scale) + 2;
        int result = 0;
        for (int d = 0; d <= radius; d++) {
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 1 && d == 0) {
                    break;
                }
                int top = pass == 0 ? centerTop - d : centerTop + d;
                if (top < 0 || top + Math.round(8 * scale) >= height) {
                    continue;
                }
                if (matchesBar(runStart, runEnd, top, scale, width, height)) {
                    if (result == 0) {
                        result = 1;
                        lastBarSeenAt = System.nanoTime();
                        if (!barSeenThisScan) {
                            barSeenThisScan = true;
                            barSeenCount++;
                        }
                        // 第一个复核通过的候选几何进缓存，之后走逐帧快通道。
                        rememberBarGeometry(runStart, runEnd, top, scale, width, height);
                    }
                    if (matchesPointerOnGreen(runStart, runEnd, top, scale, width, height)) {
                        // 命中指针的候选最贴近真实条顶，缓存以它为准。
                        rememberBarGeometry(runStart, runEnd, top, scale, width, height);
                        return 2;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 通用结构校验（不固定某一种绿色段布局）：
     * ① 色带上方是深灰描边行 (77,79,90)、下方是浅灰白描边行 (227,238,247)——
     * 上下这对边框是 bar1..bar9 共有的结构特征，能滤掉树叶/花草等巧合色带，
     * 也是最便宜的判据，放在最前让错误的条顶候选快速出局；
     * ② 色带内 ≥85% 的列在颜色行里是红/橙/黄/绿；③ 有足量真绿像素。
     */
    private static boolean matchesBar(int runStart, int runEnd, int top, float scale, int width, int height) {
        int length = runEnd - runStart + 1;
        // 精确采样贴图第 2 行深灰边框与第 6 行浅色边框；取各自行中心，避免错位时把条边框当指针。
        int darkRow = top + (int) (2.5F * scale);
        int lightRow = top + (int) (6.5F * scale);
        int darkHits = 0;
        int lightHits = 0;
        for (int i = 1; i <= 5; i++) {
            int x = runStart + Math.round(length * i / 6.0F);
            if (isNeutralDark(pixel(x, darkRow, width, height))) {
                darkHits++;
            }
            if (isNeutralLight(pixel(x, lightRow, width, height))) {
                lightHits++;
            }
        }
        if (darkHits < 3 || lightHits < 3) {
            return false;
        }
        // 颜色行为贴图 y=3..5（含端点）；用 ceil 换算，避免把上下描边行卷进来。
        int rowLo = top + (int) Math.ceil(3.0 * scale);
        int rowHi = top + (int) Math.ceil(6.0 * scale) - 1;
        int needed = (int) Math.ceil(length * 0.85);
        int covered = 0;
        int greenCount = 0;
        for (int x = runStart; x <= runEnd; x++) {
            boolean any = false;
            for (int y = rowLo; y <= rowHi; y++) {
                int kind = bandKind(x, y, width, height);
                if (kind != KIND_NONE) {
                    any = true;
                    if (kind == KIND_GREEN) {
                        greenCount++;
                    }
                }
            }
            if (any) {
                covered++;
            } else if (covered + (runEnd - x) < needed) {
                return false; // 剩余列全中也不够 85%，提前退出
            }
        }
        if (covered < needed) {
            return false;
        }
        return greenCount >= Math.max(3, Math.round(2.0F * scale));
    }

    /**
     * 本帧是否该收线。指针定位与命中判定已经拆开：
     * 这里只拿 {@link #findPointerCenter} 的结果做速度外推，不再边扫边判。
     */
    private static boolean matchesPointerOnGreen(int runStart, int runEnd, int top, float scale, int width, int height) {
        int center = findPointerCenter(runStart, runEnd, top, scale, width, height);
        if (center < 0) {
            return false;
        }
        trackPointer(center);
        return shouldReelNow(runStart, runEnd, top, scale, width, height);
    }

    /**
     * 在条体范围内找指针中心列：两道廉价的快速预检先挡掉绝大多数列，
     * 只有过了预检的列才做 5×15 模板逐格匹配。这是每帧都要跑的热路径，
     * 预检顺序不能反——模板匹配一格要读 5×15 个像素。
     *
     * @return 指针中心 x；-1 表示这一帧没找到（条体没画完 / 指针不在屏上）。
     */
    private static int findPointerCenter(int runStart, int runEnd, int top, float scale, int width, int height) {
        if (!ensureTemplate()) {
            return -1;
        }
        int halfWidth = Math.round(2.5F * scale);
        // 指针中心列的扫描范围：允许指针压住色带左右端头。
        for (int x = runStart - halfWidth; x <= runEnd + halfWidth; x++) {
            // 快速预检 1：左上描边列应为深蓝灰 (70,71,98)——背景白边直接出局。
            int edgeX = x - Math.round(2.0F * scale);
            int edgeY = top + (int) Math.floor((POINTER_TOP_OFFSET_ROWS + 1.5F) * scale);
            if (!nearColor(pixel(edgeX, edgeY, width, height), 70, 71, 98, 48)) {
                continue;
            }
            // 快速预检 2：中心列中上应为冰蓝 (168,189,208)。
            int coreY = top + (int) Math.floor((POINTER_TOP_OFFSET_ROWS + 4.5F) * scale);
            if (!nearColor(pixel(x, coreY, width, height), 168, 189, 208, POINTER_COLOR_TOL)) {
                continue;
            }
            if (!pointerTemplateMatches(x, top, scale, width, height)) {
                continue;
            }
            return x;
        }
        return -1;
    }

    /** 记录一次指针位置并更新横向速度（指数平滑，抑制单帧抖动）。 */
    private static void trackPointer(int x) {
        long now = System.nanoTime();
        if (pointerSeenNs != 0L && pointerX >= 0 && now > pointerSeenNs) {
            double dtMs = (now - pointerSeenNs) / 1_000_000.0;
            // 超过 200ms 说明中间断过帧（切屏 / 卡顿 / 面板打开），此时速度不可信，重测。
            if (dtMs >= 0.5 && dtMs <= 200.0) {
                double instant = (x - pointerX) / dtMs;
                pointerVxInstant = instant;
                pointerVx = pointerVx * 0.35 + instant * 0.65;
            } else {
                pointerVxInstant = pointerVx;
            }
        }
        pointerX = x;
        pointerSeenNs = now;
    }

    /**
     * 收线判定核心：沿速度方向探测目标色，不再依赖"绿段边界"。
     *
     * <ol>
     *   <li><b>当前帧</b>：指针中心那一列透出的就是条体底色，直接拿它跟目标色比。
     *       指针把绿段劈成两半也不影响——我们看的不是"段"，是"指针底下这一列"。</li>
     *   <li><b>提前量</b>：从指针中心沿当前速度方向逐像素前探一个 lead 窗口的距离，
     *       路上撞到目标色就提前点下去。方向天然正确，反向瞬间不会再误判；
     *       段数、段宽、布局也全都不用关心。</li>
     * </ol>
     */
    private static boolean shouldReelNow(int runStart, int runEnd, int top, float scale, int width, int height) {
        if (pointerX < 0 || regionWidth <= 0) {
            return false;
        }
        int gapY = top + (int) Math.floor(4.5F * scale);

        // ① 当前帧：指针中心列
        pointerCenterRgb = pixel(pointerX, gapY, width, height);
        if (isTargetColorAt(pointerX, gapY, width, height)) {
            lastTargetDistancePx = 0;
            hitDecisionCount++;
            return true;
        }

        // ② 提前量：沿速度方向前探
        lastTargetDistancePx = -1;
        if (leadMs <= 0) {
            return false;
        }
        // 平滑速度会滞后：加速时偏低、减速时偏高。取「平滑」与「瞬时」的较小值走保守一侧
        // —— 宁可少探一点（偏晚按），也不要因为速度虚高而提前点空。
        double speed = Math.min(Math.abs(pointerVx), Math.abs(pointerVxInstant));
        if (speed <= 0.0) {
            return false;
        }
        double aheadPx = speed * (leadMs + (System.nanoTime() - pointerSeenNs) / 1_000_000.0);
        int steps = (int) Math.round(Math.min(aheadPx, MAX_LEAD_PX));
        if (steps <= 0) {
            return false;
        }
        int dir = pointerVx > 0.0 ? 1 : -1;
        for (int i = 1; i <= steps; i++) {
            int x = pointerX + dir * i;
            if (x < runStart || x > runEnd) {
                break; // 走出条体范围就别再探了
            }
            // 连续两格都是目标色才算：色带交界的混色像素只有一个，靠这条挡掉
            if (isTargetColorAt(x, gapY, width, height)
                && isTargetColorAt(x + dir, gapY, width, height)) {
                lastTargetDistancePx = i;
                hitDecisionCount++;
                predictedHitCount++;
                return true;
            }
        }
        return false;
    }

    /**
     * 目标色判定：精确色 ± 容差。
     *
     * <p>用精确色而不是色相分桶，是因为相邻色相会互相串桶（橙 #FB9E49 与黄 #FBC94E
     * 只差一点）；但同一个颜色的段内有 2~3 档深浅（绿有 #34ED53 / #2EEE53 等），
     * 所以必须留容差。实测：同段深浅差 ≤10，与相邻色相的边界混色差 30~50，
     * 相邻色相差 ≥80 —— 取 24 正好吃下前者、挡住后两者。
     */
    /**
     * 调色板：取自服务器鱼条的实际取色（用户用取色工具提取）。
     * 顺序与 {@link #PALETTE_NAME} 对应：橙 / 黄 / 绿 / 青 / 蓝 / 紫。
     */
    private static final int[] PALETTE_RGB = {
        0xFCA248, 0xFCC84B, 0x33EF4F, 0x33EFD2, 0x5182D7, 0xC76BFC,
    };
    private static final String[] PALETTE_NAME = {"橙", "黄", "绿", "青", "蓝", "紫"};
    private static final float[] PALETTE_HUE = new float[PALETTE_RGB.length];

    static {
        for (int i = 0; i < PALETTE_RGB.length; i++) {
            PALETTE_HUE[i] = hueOf(PALETTE_RGB[i]);
        }
    }

    /** 目标色缓存：色相归类结果随 targetRgb 变化重算，避免每像素都算一遍。 */
    private static int targetClassCache = -1;
    private static int targetClassCacheRgb = -1;

    private static int targetClass() {
        if (targetClassCacheRgb != targetRgb) {
            targetClassCache = classifyColor(targetRgb);
            targetClassCacheRgb = targetRgb;
        }
        return targetClassCache;
    }

    /**
     * 色相角（0~360）。低饱和（灰/白/黑）返回 -1 —— 它们不属于任何色相，
     * 也就不会被当成鱼条颜色。
     */
    private static float hueOf(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        // 太暗、或饱和度太低（最大值不到最小值的 4 倍跨度）→ 视为无彩色
        if (max < 40 || delta * 4 < max) {
            return -1F;
        }
        float h;
        if (max == r) {
            h = 60F * (((g - b) / (float) delta) % 6F);
        } else if (max == g) {
            h = 60F * ((b - r) / (float) delta) + 120F;
        } else {
            h = 60F * ((r - g) / (float) delta) + 240F;
        }
        return (h + 360F) % 360F;
    }

    /**
     * 把一个像素归到调色板里"最近"的那个颜色。
     *
     * <p>为什么用就近归类而不是阈值：橙(30°) 与黄(42°) 只差 12.8°，任何"容差"设到
     * 20° 都会把两者混起来；而就近归类永远只选最近的，不会互相抢。
     *
     * <p>同一颜色的亮档 / 暗档色相几乎一致（例如 #FCA248 与 #D78535 都是 30°），
     * 所以贴图里那两档深浅天然被同一类收下，不需要额外规则。
     *
     * @return 调色板下标；不属于任何一种（灰/白/黑/偏差过大）返回 -1
     */
    private static int classifyColor(int rgb) {
        float h = hueOf(rgb);
        if (h < 0F) {
            return -1;
        }
        int best = -1;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < PALETTE_HUE.length; i++) {
            float d = Math.abs(h - PALETTE_HUE[i]);
            if (d > 180F) {
                d = 360F - d; // 色相是环形的
            }
            if (d < bestDelta) {
                bestDelta = d;
                best = i;
            }
        }
        return bestDelta <= targetTol ? best : -1;
    }

    /** 调色板名字（HUD 显示用）；未知返回 "?"。 */
    public static String paletteName(int index) {
        return index >= 0 && index < PALETTE_NAME.length ? PALETTE_NAME[index] : "?";
    }

    /** 当前目标色归到的调色板名字（HUD 显示用）。 */
    public static String targetColorName() {
        return paletteName(targetClass());
    }

    private static boolean isTargetColorAt(int x, int gapY, int width, int height) {
        int rgb = pixel(x, gapY, width, height);
        if (rgb == 0) {
            return false; // 读回区域之外
        }
        int cls = classifyColor(rgb);
        return cls >= 0 && cls == targetClass();
    }

    /** 5×15 指针贴图逐格匹配：不透明 texel 取格心采样，≥90% 颜色对上才算指针。 */
    private static boolean pointerTemplateMatches(int centerX, int top, float scale, int width, int height) {
        int matched = 0;
        for (int ty = 0; ty < POINTER_H; ty++) {
            int y = top + (int) Math.floor((POINTER_TOP_OFFSET_ROWS + ty + 0.5F) * scale);
            for (int tx = 0; tx < POINTER_W; tx++) {
                int idx = ty * POINTER_W + tx;
                if (!templateOpaque[idx]) {
                    continue;
                }
                int x = centerX + Math.round((tx - 2) * scale);
                int expected = templateRgb[idx];
                int color = pixel(x, y, width, height);
                if (nearColor(color, (expected >>> 16) & 0xFF, (expected >>> 8) & 0xFF, expected & 0xFF,
                    POINTER_COLOR_TOL)) {
                    matched++;
                }
            }
        }
        return matched >= Math.ceil(templateOpaqueCount * POINTER_MATCH_RATIO);
    }

    private static boolean nearColor(int color, int r, int g, int b, int tol) {
        return Math.abs(((color >>> 16) & 0xFF) - r) <= tol
            && Math.abs(((color >>> 8) & 0xFF) - g) <= tol
            && Math.abs((color & 0xFF) - b) <= tol;
    }

    /** 懒加载指针贴图模板（5×15，含透明掩码）。加载失败返回 false，本模组不会按右键。 */
    private static boolean ensureTemplate() {
        if (templateRgb != null) {
            return templateOpaqueCount > 0;
        }
        templateRgb = new int[POINTER_W * POINTER_H];
        templateOpaque = new boolean[POINTER_W * POINTER_H];
        String path = "/assets/" + AutoFishMod.MOD_ID + "/textures/gui/auto_fish_pointer.png";
        try (InputStream stream = AutoFishController.class.getResourceAsStream(path)) {
            if (stream != null) {
                BufferedImage image = ImageIO.read(stream);
                if (image == null || image.getWidth() != POINTER_W || image.getHeight() != POINTER_H) {
                    return false;
                }
                int w = POINTER_W;
                int h = POINTER_H;
                for (int ty = 0; ty < h; ty++) {
                    for (int tx = 0; tx < w; tx++) {
                        int argb = image.getRGB(tx, ty);
                        int idx = ty * POINTER_W + tx;
                        if (((argb >>> 24) & 0xFF) >= 128) {
                            templateOpaque[idx] = true;
                            templateRgb[idx] = argb & 0xFFFFFF;
                            templateOpaqueCount++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            templateOpaqueCount = 0;
        }
        return templateOpaqueCount > 0;
    }

    /** 亮绿快速判定（种子）：贴图绿 (52,242,80)/(43,200,66)。bar10 的青色不算绿。 */
    private static boolean isGreenish(int x, int y, int width, int height) {
        int color = pixel(x, y, width, height);
        return bandKind(color) == KIND_GREEN;
    }

    private static int bandKind(int x, int y, int width, int height) {
        return bandKind(pixel(x, y, width, height));
    }

    /** 颜色行的色种；阈值同时覆盖明暗两档（y=3 与 y=4/5）。绿色只认真绿，青色（bar10）不再算。 */
    private static int bandKind(int color) {
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        if (g >= 140 && r >= 20 && r < 120 && g - r >= 60 && b < 130) {
            return KIND_GREEN;
        }
        if (r >= 150 && g < 110 && b < 110 && r - g >= 90) {
            return KIND_RED;
        }
        if (r >= 180 && g >= 110 && g < 185 && b < 140) {
            return KIND_ORANGE;
        }
        if (r >= 180 && g >= 185 && b < 140) {
            return KIND_YELLOW;
        }
        // 冷色段：彩虹鱼条（红橙黄绿青蓝紫七段等宽）里的青 / 蓝 / 紫。
        // 它们不是命中色，但必须被认出来——否则 matchesBar 的 85% 覆盖率判据
        // 会把整条鱼条否掉（实测彩虹条 7 段里 3 段是冷色 = 覆盖率只有 57%）。
        if (g >= 170 && b >= 170 && r < 120) {
            return KIND_CYAN;
        }
        if (b >= 170 && g >= 100 && r < 140 && b - r >= 70) {
            return KIND_BLUE;
        }
        if (r >= 150 && b >= 180 && g < 160 && b - g >= 60) {
            return KIND_PURPLE;
        }
        return KIND_NONE;
    }

    /** 深灰描边行 (77,79,90) 一类：低饱和、中低亮度。 */
    private static boolean isNeutralDark(int color) {
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 30 && (r + g + b) / 3 >= 50 && (r + g + b) / 3 <= 115;
    }

    /** 浅灰白描边行 (227,238,247) 一类：低饱和、高亮度。 */
    private static boolean isNeutralLight(int color) {
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 35 && (r + g + b) / 3 >= 170;
    }

    /**
     * 返回图像坐标（y 向下）的 RGB；当前读回区域外的坐标按黑处理（快通道只读小区域，
     * 保守判定不误触）。glReadPixels 原点在左下，区域内按 region 高度翻转 y。
     */
    private static int pixel(int x, int y, int width, int height) {
        if (pixels == null || regionWidth <= 0 || regionHeight <= 0) {
            return 0;
        }
        int lx = x - regionX;
        int ly = y - regionY;
        if (lx < 0 || ly < 0 || lx >= regionWidth || ly >= regionHeight) {
            return 0;
        }
        int index = ((regionHeight - 1 - ly) * regionWidth + lx) * 4;
        int r = pixels.get(index) & 0xFF;
        int g = pixels.get(index + 1) & 0xFF;
        int b = pixels.get(index + 2) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }
}
