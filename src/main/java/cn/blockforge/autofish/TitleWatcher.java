package cn.blockforge.autofish;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

/**
 * 从服务器发来的提示文字里识别"这一竿要打哪个颜色"。
 *
 * <p>信息来源是 {@code InGameHud} 的 setTitle / setSubtitle / setOverlayMessage
 * ——服务器插件发的 Title、Subtitle、ActionBar 包最终都落到这三个方法上，
 * 客户端直接持有 {@link Text} 对象，不需要读屏、不需要 OCR。
 *
 * <p>识别优先级：
 * <ol>
 *   <li><b>样式颜色</b>：插件常把提示文字染成目标色（比如「紫色！」就是紫字），
 *       那就直接取 {@code Style#getColor()} 的 RGB，最准；</li>
 *   <li><b>颜色词表</b>：样式没带颜色时，按文字里的「紫/青/蓝/绿/黄/橙/红」映射到
 *       从实测鱼条上量的代表色；</li>
 *   <li>两者都没有 → 不动作，保持设置页里的手动值。</li>
 * </ol>
 *
 * <p>本类只写字段（volatile 收尾），可能从网络线程被调用，不碰任何游戏状态。
 */
public final class TitleWatcher {
    /** 颜色词（长词在前，避免「紫」抢在别的词前面匹配到半个）。 */
    private static final String[] WORDS = {"紫色", "青色", "蓝色", "绿色", "黄色", "橙色", "红色",
        "紫", "青", "蓝", "绿", "黄", "橙", "红"};

    /**
     * 词表兜底用的代表色：直接用服务器鱼条的实际取色。
     *
     * <p>注意「红色」和「橙色」都指向 {@code #FCA248}——那条鱼条最左边那块就是这个橙红色，
     * 插件可能管它叫红、也可能叫橙，而它跟「黄」(#FCC84B) 只差 12.8° 色相，
     * 必须当成同一个色类才不会互相抢。
     */
    private static final int[] WORD_RGB = {
        0xC76BFC, // 紫色
        0x33EFD2, // 青色
        0x5182D7, // 蓝色
        0x33EF4F, // 绿色
        0xFCC84B, // 黄色
        0xFCA248, // 橙色
        0xFCA248, // 红色 —— 与橙同一类
        0xC76BFC, 0x33EFD2, 0x5182D7, 0x33EF4F, 0xFCC84B, 0xFCA248, 0xFCA248};

    private TitleWatcher() {
    }

    /** 由 InGameHud 的注入点调用。 */
    public static void onText(Text text) {
        if (text == null) {
            return;
        }
        String raw;
        try {
            raw = text.getString();
        } catch (Throwable ignored) {
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }

        int wordIndex = findColorWord(raw);
        if (wordIndex < 0) {
            return;
        }

        int rgb = colorFromStyle(text);
        if (rgb <= 0) {
            rgb = WORD_RGB[wordIndex];
        }
        AutoFishController.onTargetColorHint(rgb);
    }

    /** 样式里带颜色就返回 0xRRGGBB；没带返回 -1。 */
    private static int colorFromStyle(Text text) {
        try {
            Style style = text.getStyle();
            if (style == null) {
                return -1;
            }
            TextColor color = style.getColor();
            if (color == null) {
                return -1;
            }
            return color.getRgb() & 0xFFFFFF;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int findColorWord(String raw) {
        for (int i = 0; i < WORDS.length; i++) {
            if (raw.contains(WORDS[i])) {
                return i;
            }
        }
        return -1;
    }
}
