package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 全模组设置页：左侧参数名、中间控件、右侧「重置」，底部「取消 / 完成并退出」。
 *
 * <p>按「基本只放开关、高级只放精度」分工：
 * <ul>
 *   <li><b>基本选项</b>：每个功能一个开 / 关，一眼看清现在开着什么；</li>
 *   <li><b>高级选项</b>：数值（旋转速度、收线预判、重投节奏），影响判定精度与手感。</li>
 * </ul>
 *
 * <p><b>为什么不用滑条控件</b>：{@code SliderWidget} 的构造函数在 1.20.1 与 1.20.4
 * 之间不一致（1.20.4 多一个 {@code double value} 参数），{@code renderButton}/{@code renderWidget}
 * 的钩子名也不同。本模组要用同一份源码同时编 1.20.1 和 1.20.4，所以这里全部用最稳定的
 * {@link ButtonWidget} 拼出来：数值行用「− / +」按钮，精调反而更准。
 *
 * <p>只有「高级选项」的数值会写进 config/ 下的 properties，重启仍然生效；
 * 基本区的开关是会话状态，关掉游戏即恢复默认。
 */
public final class ModSettingsScreen extends Screen {
    private static final int ROW_MIN = 13;
    private static final int ROW_MAX = 20;
    private static final int LABEL_W = 150;
    private static final int RESET_W = 40;
    private static final int STEP_W = 26;
    private static final int STEP_GAP = 4;
    private static final int SECTION_H = 12;
    private static final int SECTION_GAP = 6;

    private static final int SCREEN_DIM = 0x4D000000;
    private static final int PANEL_BG = 0xCC000000;
    private static final int PANEL_BORDER = 0xFF8C8C8C;
    private static final int SEPARATOR = 0x599E9E9E;
    private static final int TEXT_TITLE = 0xFFFFFFFF;
    private static final int TEXT_SECTION = 0xFFE6C35C;
    private static final int TEXT_LABEL = 0xFFE0E0E0;
    private static final int TEXT_HINT = 0xFF8F8F8F;

    private final Screen parent;

    private final List<Text> labelTexts = new ArrayList<>();
    private final List<Integer> labelYs = new ArrayList<>();
    private final List<Text> sectionTexts = new ArrayList<>();
    private final List<Integer> sectionYs = new ArrayList<>();
    /** 自己维护的控件列表：手动按「先面板、后控件」渲染，不调 super.render()。 */
    private final List<ClickableWidget> controls = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int innerW;
    private int controlX;
    private int controlW;
    private int resetX;

    /** 「取消」要还原的数值快照：收线预判 / 重投延迟 / 随机延迟。 */
    private int snapshotLead;
    private int snapshotRecast;
    private int snapshotJitter;

    public ModSettingsScreen(Screen parent) {
        super(Text.literal("功能设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        snapshotLead = AutoFishController.leadMs();
        snapshotRecast = AutoFishController.recastDelayMs();
        snapshotJitter = AutoFishController.recastJitterPercent();

        labelTexts.clear();
        labelYs.clear();
        sectionTexts.clear();
        sectionYs.clear();
        controls.clear();
        layoutPanel();

        int rows = 14;
        int titleH = 34;
        int bottomH = 50;
        int usable = panelH - titleH - bottomH - SECTION_H * 2 - SECTION_GAP;
        int unit = Math.max(ROW_MIN, Math.min(ROW_MAX, usable / rows));
        int y = panelY + titleH;

        // ---------------- 基本选项：只放开关 ----------------
        y = section("基本选项", y);
        y = toggleRow("旋转（视角 + 模型）", y, unit,
            SpinController.getMode() == SpinController.Mode.VIEW,
            () -> SpinController.toggle(SpinController.Mode.VIEW), false);
        y = toggleRow("角色旋转（视角不转）", y, unit,
            SpinController.getMode() == SpinController.Mode.MODEL,
            () -> SpinController.toggle(SpinController.Mode.MODEL), false);
        y = toggleRow("抬臂旋转（飞机姿势）", y, unit,
            SpinController.getMode() == SpinController.Mode.AIRPLANE,
            () -> SpinController.toggle(SpinController.Mode.AIRPLANE), false);
        y = toggleRow("人物模型隐身", y, unit,
            ModelHideController.isHidden(),
            ModelHideController::toggle, false);
        y = toggleRow("盘腿坐", y, unit,
            SitPoseController.isSitting(),
            SitPoseController::toggle, false);
        y = toggleRow("转R6 比例", y, unit,
            R6ModelController.isEnabled(),
            R6ModelController::toggle, false);
        y = toggleRow("自动钓鱼", y, unit,
            AutoFishController.isEnabled(),
            AutoFishController::toggle, false);
        y = toggleRow("呼出音效", y, unit,
            FunctionMenuMod.soundEnabled,
            () -> FunctionMenuMod.soundEnabled = !FunctionMenuMod.soundEnabled, true);
        y = toggleRow("信息浮层", y, unit,
            FunctionMenuMod.infoOverlayEnabled,
            () -> FunctionMenuMod.infoOverlayEnabled = !FunctionMenuMod.infoOverlayEnabled, false);

        // ---------------- 高级选项：只放精度数值 ----------------
        y = section("高级选项", y + SECTION_GAP);
        y = stepRow("旋转速度", spinSpeedText(SpinController.speedIndex()), y, unit,
            () -> SpinController.setSpeedIndex(SpinController.speedIndex() - 1),
            () -> SpinController.setSpeedIndex(SpinController.speedIndex() + 1),
            () -> SpinController.setSpeedIndex(1));
        y = switchRow("旋转方向", SpinController.isClockwise() ? "顺时针" : "逆时针", y, unit,
            SpinController::toggleDirection,
            () -> SpinController.setClockwise(true));
        y = stepRow("收线预判", AutoFishController.leadMs() + " 毫秒", y, unit,
            () -> AutoFishController.setLeadMs(AutoFishController.leadMs() - 20),
            () -> AutoFishController.setLeadMs(AutoFishController.leadMs() + 20),
            () -> AutoFishController.setLeadMs(40));
        y = stepRow("重新投掷延迟", AutoFishController.recastDelayMs() + " 毫秒", y, unit,
            () -> AutoFishController.setRecastDelayMs(AutoFishController.recastDelayMs() - 100),
            () -> AutoFishController.setRecastDelayMs(AutoFishController.recastDelayMs() + 100),
            () -> AutoFishController.setRecastDelayMs(1500));
        y = stepRow("随机重投延迟", "百分之 " + AutoFishController.recastJitterPercent(), y, unit,
            () -> AutoFishController.setRecastJitterPercent(AutoFishController.recastJitterPercent() - 5),
            () -> AutoFishController.setRecastJitterPercent(AutoFishController.recastJitterPercent() + 5),
            () -> AutoFishController.setRecastJitterPercent(0));

        // ---------------- 底部 ----------------
        int bw = 122;
        int gap = 10;
        int by = panelY + panelH - 32;
        int totalW = bw * 2 + gap;
        int bx = panelX + (panelW - totalW) / 2;
        addControl(ButtonWidget.builder(Text.literal("取消"), b -> {
            AutoFishController.setLeadMs(snapshotLead);
            AutoFishController.setRecastDelayMs(snapshotRecast);
            AutoFishController.setRecastJitterPercent(snapshotJitter);
            closeToParent();
        }).dimensions(bx, by, bw, 20).build());
        addControl(ButtonWidget.builder(Text.literal("完成并退出"), b -> {
            AutoFishController.saveConfig();
            closeToParent();
        }).dimensions(bx + bw + gap, by, bw, 20).build());
    }

    // -----------------------------------------------------------------------
    // 行构造：只用 ButtonWidget，避开 1.20.1 / 1.20.4 的控件 API 差异
    // -----------------------------------------------------------------------

    private void addControl(ClickableWidget widget) {
        controls.add(widget);
        addDrawableChild(widget);
    }

    private void layoutPanel() {
        panelW = Math.min(520, Math.max(340, (int) Math.round(this.width * 0.58)));
        panelH = Math.min(this.height - 16, Math.max(240, (int) Math.round(this.height * 0.86)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        innerX = panelX + 20;
        innerW = panelW - 40;
        resetX = innerX + innerW - RESET_W;
        controlX = innerX + LABEL_W;
        controlW = Math.max(80, resetX - controlX - 10);
    }

    private int section(String title, int y) {
        sectionTexts.add(Text.literal("− " + title));
        sectionYs.add(y);
        return y + SECTION_H;
    }

    /** 开关行。{@code defaultOn} 是「重置」要恢复到的默认状态。 */
    private int toggleRow(String label, int y, int unit, boolean on, Runnable flip, boolean defaultOn) {
        labelTexts.add(Text.literal(label));
        labelYs.add(y);
        int h = Math.min(20, unit);
        addControl(ButtonWidget.builder(Text.literal(on ? "开" : "关"), b -> {
            flip.run();
            clearAndInit();
        }).dimensions(controlX, y, controlW, h).build());
        Runnable reset = (on == defaultOn) ? () -> { } : flip;
        addControl(ButtonWidget.builder(Text.literal("重置"), b -> {
            reset.run();
            clearAndInit();
        }).dimensions(resetX, y, RESET_W, h).build());
        return y + unit;
    }

    /** 数值行：左边 −、右边 +（都比原版滑条更精确），最右重置。 */
    private int stepRow(String label, String valueText, int y, int unit,
                        Runnable minus, Runnable plus, Runnable reset) {
        labelTexts.add(Text.literal(label + "：" + valueText));
        labelYs.add(y);
        int h = Math.min(20, unit);
        addControl(ButtonWidget.builder(Text.literal("−"), b -> {
            minus.run();
            clearAndInit();
        }).dimensions(controlX, y, STEP_W, h).build());
        addControl(ButtonWidget.builder(Text.literal("+"), b -> {
            plus.run();
            clearAndInit();
        }).dimensions(controlX + STEP_W + STEP_GAP, y, STEP_W, h).build());
        addControl(ButtonWidget.builder(Text.literal("重置"), b -> {
            reset.run();
            clearAndInit();
        }).dimensions(resetX, y, RESET_W, h).build());
        return y + unit;
    }

    /** 二选一行：一个「切换」按钮。 */
    private int switchRow(String label, String valueText, int y, int unit,
                          Runnable toggle, Runnable reset) {
        labelTexts.add(Text.literal(label + "：" + valueText));
        labelYs.add(y);
        int h = Math.min(20, unit);
        addControl(ButtonWidget.builder(Text.literal("切换"), b -> {
            toggle.run();
            clearAndInit();
        }).dimensions(controlX, y, 60, h).build());
        addControl(ButtonWidget.builder(Text.literal("重置"), b -> {
            reset.run();
            clearAndInit();
        }).dimensions(resetX, y, RESET_W, h).build());
        return y + unit;
    }

    /** 旋转档位下标 → 「正常 · 180°/秒」。 */
    private static String spinSpeedText(int index) {
        String[] keys = {"slow", "normal", "fast", "ultra"};
        String[] degrees = {"60", "180", "360", "720"};
        int i = Math.max(0, Math.min(keys.length - 1, index));
        return Text.translatable("tidinmenu.spin.speed." + keys[i]).getString() + " · " + degrees[i] + "°/秒";
    }

    // -----------------------------------------------------------------------
    // 绘制 / 生命周期
    // -----------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, SCREEN_DIM);
        context.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_BORDER);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelY + 12, TEXT_TITLE);
        context.fill(panelX + 12, panelY + 26, panelX + panelW - 12, panelY + 27, SEPARATOR);

        for (int i = 0; i < sectionTexts.size(); i++) {
            context.drawTextWithShadow(this.textRenderer, sectionTexts.get(i), innerX, sectionYs.get(i), TEXT_SECTION);
        }
        for (int i = 0; i < labelTexts.size(); i++) {
            context.drawTextWithShadow(this.textRenderer, labelTexts.get(i), innerX, labelYs.get(i) + 6, TEXT_LABEL);
        }

        for (ClickableWidget control : controls) {
            control.render(context, mouseX, mouseY, delta);
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("只有「高级选项」的数值会保存；开关是会话状态，重启即恢复默认"),
            this.width / 2, panelY + panelH - 46, TEXT_HINT);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Esc 与「完成并退出」等价：保存数值后回到上一页。 */
    @Override
    public void close() {
        AutoFishController.saveConfig();
        closeToParent();
    }

    private void closeToParent() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
