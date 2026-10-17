package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 半透明黑色功能面板：占屏幕 72% 居中，纯黑 80% 半透明，12 像素圆角 + 1 像素细灰边框；
 * 顶部居中 2 倍字号带阴影的白色「功能」标题；下方「工具 / 世界 / 设置」三个标签页切换内容。
 */
public final class FunctionMenuScreen extends Screen {
    private static final double PANEL_RATIO = 0.72;
    private static final int CORNER_RADIUS = 12;

    private static final int SCREEN_DIM = 0x4D000000;   // 面板外轻微压暗（30%）
    private static final int PANEL_BG = 0xCC000000;     // 纯黑 80% 不透明
    private static final int PANEL_BORDER = 0xFF8C8C8C; // 细灰边框
    private static final int SEPARATOR = 0x599E9E9E;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_INFO = 0xE0FFFFFF;
    private static final int TEXT_STATUS = 0xFFE6C35C;
    private static final int TEXT_HINT = 0xFF8F8F8F;
    private static final int TAB_SELECTED_BG = 0xD9F2F2F2;
    private static final int TAB_SELECTED_TEXT = 0xFF1A1A1A;
    private static final int TAB_IDLE_BG = 0x1AFFFFFF;
    private static final int TAB_HOVER_BG = 0x4DFFFFFF;

    private final Screen parent;

    /**
     * 自己维护的控件列表，用于手动按「先面板、后控件」的顺序渲染。
     * 不能调 super.render()：它内部会先画原版背景（1.20.4 的 renderBackground 签名
     * 与 1.20.1 不同，我没法覆写成空方法），那层背景会盖在面板上面。
     */
    private final List<ClickableWidget> widgetList = new ArrayList<>();

    private int tab = 0;
    private String status = null;
    private int statusTicks = 0;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /** 工具页里旋转状态说明行的纵坐标，由 buildTools() 的网格推算，避免压到面板边缘。 */
    private int spinInfoY;

    public FunctionMenuScreen(Screen parent) {
        super(Text.translatable("tidinmenu.title"));
        this.parent = parent;
    }

    public Screen getParent() {
        return parent;
    }

    @Override
    protected void init() {
        super.init();
        widgetList.clear();
        layoutPanel();

        int tabH = 20;
        int tabGap = 8;
        int tabW = Math.min(92, (panelW - 48 - tabGap * 2) / 3);
        if (tabW < 48) {
            tabW = 48;
        }
        int totalTabW = tabW * 3 + tabGap * 2;
        int tabX = panelX + (panelW - totalTabW) / 2;
        int tabY = panelY + 48;

        addWidget(tabButton(tabX, tabY, tabW, tabH, 0, "tidinmenu.tab.tools"));
        addWidget(tabButton(tabX + tabW + tabGap, tabY, tabW, tabH, 1, "tidinmenu.tab.world"));
        addWidget(tabButton(tabX + (tabW + tabGap) * 2, tabY, tabW, tabH, 2, "tidinmenu.tab.settings"));

        switch (tab) {
            case 0 -> buildTools();
            case 1 -> buildWorld();
            default -> buildSettings();
        }
    }

    private void layoutPanel() {
        panelW = Math.max(280, (int) Math.round(this.width * PANEL_RATIO));
        panelH = Math.max(180, (int) Math.round(this.height * PANEL_RATIO));
        if (panelW > this.width - 10) {
            panelW = Math.max(120, this.width - 10);
        }
        if (panelH > this.height - 10) {
            panelH = Math.max(120, this.height - 10);
        }
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    // ---------------------------------------------------------------
    // 各标签页内容
    // ---------------------------------------------------------------

    private void buildTools() {
        MinecraftClient client = MinecraftClient.getInstance();
        ButtonGrid grid = new ButtonGrid(14, panelX, panelY, panelW, panelH, 86, 56);

        addWidget(ButtonWidget.builder(Text.translatable("tidinmenu.tool.copy_coords"),
            b -> copyCoords()).dimensions(grid.x(0), grid.y(0), grid.colW, 20).build());

        boolean hudHidden = client != null && client.options != null && client.options.hudHidden;
        addWidget(ButtonWidget.builder(
            Text.translatable(hudHidden ? "tidinmenu.tool.hud_show" : "tidinmenu.tool.hud_hide"),
            b -> {
                client.options.hudHidden = !client.options.hudHidden;
                setStatus(client.options.hudHidden ? "已隐藏 HUD（可随时按 F1 切换）" : "已恢复 HUD 显示");
                clearAndInit();
            }).dimensions(grid.x(1), grid.y(1), grid.colW, 20).build());

        boolean debugOn = isDebugInfoOn(client);
        addWidget(ButtonWidget.builder(
            Text.translatable(debugOn ? "tidinmenu.tool.debug_on" : "tidinmenu.tool.debug_off"),
            b -> {
                Boolean now = toggleDebugInfo(client);
                setStatus(now == null
                    ? "当前版本没有这个开关，请直接按 F3"
                    : (now ? "已打开调试信息" : "已关闭调试信息"));
                clearAndInit();
            }).dimensions(grid.x(2), grid.y(2), grid.colW, 20).build());

        addWidget(ButtonWidget.builder(Text.translatable("tidinmenu.tool.options"),
            b -> {
                MinecraftClient c = MinecraftClient.getInstance();
                c.setScreen(new OptionsScreen(this, c.options));
            }).dimensions(grid.x(3), grid.y(3), grid.colW, 20).build());

        // ---- 旋转（沿 Y 轴自转）、角色旋转（视角不转）与抬臂旋转（视角不转 + 飞机姿势）----
        SpinController.Mode mode = SpinController.getMode();
        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.spin." + (mode == SpinController.Mode.VIEW ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能旋转");
                    return;
                }
                SpinController.Mode m = SpinController.toggle(SpinController.Mode.VIEW);
                setStatus(m == SpinController.Mode.VIEW
                    ? "旋转开始：" + speedText() + " · " + directionText() + "（" + SpinController.degreesPerSecond() + "°/秒）"
                    : "旋转停止，朝向留在当前角度（再点一次可继续转）");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.spin.tip")))
            .dimensions(grid.x(4), grid.y(4), grid.colW, 20).build());

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.spin_model." + (mode == SpinController.Mode.MODEL ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能旋转");
                    return;
                }
                SpinController.Mode m = SpinController.toggle(SpinController.Mode.MODEL);
                setStatus(m == SpinController.Mode.MODEL
                    ? "角色旋转开始：" + speedText() + " · " + directionText() + "（" + SpinController.degreesPerSecond() + "°/秒）视角不转，可按 F5 观看"
                    : "角色旋转停止，模型已归位回视角朝向");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.spin_model.tip")))
            .dimensions(grid.x(5), grid.y(5), grid.colW, 20).build());

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.spin_airplane." + (mode == SpinController.Mode.AIRPLANE ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能旋转");
                    return;
                }
                SpinController.Mode m = SpinController.toggle(SpinController.Mode.AIRPLANE);
                setStatus(m == SpinController.Mode.AIRPLANE
                    ? "抬臂旋转开始：" + speedText() + " · " + directionText() + "（" + SpinController.degreesPerSecond() + "°/秒）双臂平举，视角不转，可按 F5 观看"
                    : "抬臂旋转停止，双臂已放下，模型已归位回视角朝向");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.spin_airplane.tip")))
            .dimensions(grid.x(6), grid.y(6), grid.colW, 20).build());

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.spin_speed", speedText()),
            b -> {
                String key = SpinController.cycleSpeed();
                setStatus("旋转速度已切换：" + Text.translatable("tidinmenu.spin.speed." + key).getString()
                    + "（" + SpinController.degreesPerSecond() + "°/秒）");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.spin_speed.tip")))
            .dimensions(grid.x(7), grid.y(7), grid.colW, 20).build());

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.spin_dir", directionText()),
            b -> {
                boolean cw = SpinController.toggleDirection();
                setStatus("旋转方向已切换：" + (cw ? "顺时针（向右转）" : "逆时针（向左转）"));
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.spin_dir.tip")))
            .dimensions(grid.x(8), grid.y(8), grid.colW, 20).build());

        // ---- 人物模型隐身：第三人称下看不见自己的角色模型（只对自己生效的本地表现）----
        boolean modelHidden = ModelHideController.isHidden();
        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.hide_model." + (modelHidden ? "stop" : "start")),
            b -> {
                boolean nowHidden = ModelHideController.toggle();
                setStatus(nowHidden
                    ? "人物模型已隐身：第三人称（F5）下看不到自己的身体、装备与手持物；第一人称不受影响，别人仍能看到你"
                    : "人物模型隐身已关闭，第三人称下身体恢复显示");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.hide_model.tip")))
            .dimensions(grid.x(9), grid.y(9), grid.colW, 20).build());

        // ---- 盘腿坐：第三人称下把角色模型摆成盘坐姿势（可与旋转叠加）----
        boolean sitting = SitPoseController.isSitting();
        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.sit_pose." + (sitting ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能坐下");
                    return;
                }
                boolean nowSitting = SitPoseController.toggle();
                setStatus(nowSitting
                    ? "盘腿坐：按 F5 能看到自己盘坐在地上；视角与移动不受影响，可与旋转叠加"
                    : "已起身，模型恢复站立");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.sit_pose.tip")))
            .dimensions(grid.x(10), grid.y(10), grid.colW, 20).build());

        // ---- 自动钓鱼：溅水花字幕出现即按右键咬钩；再按参考图识别屏幕上的指针与绿色区，命中后执行右键收线 ----
        boolean autoFishing = AutoFishController.isEnabled();
        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.auto_fish." + (autoFishing ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能自动钓鱼");
                    return;
                }
                boolean nowAutoFishing = AutoFishController.toggle();
                setStatus(nowAutoFishing
                    ? "自动钓鱼已开启：出现「浮漂：溅起水花」自动咬钩；指针真正碰到绿色段才收线"
                    : "自动钓鱼已关闭");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.auto_fish.tip")))
            .dimensions(grid.x(11), grid.y(11), grid.colW, 20).build());

        // ---- 收线预判：指针速度是随机的，靠"看见它在绿里"再点永远慢一拍 ----
        addWidget(ButtonWidget.builder(
            Text.literal("收线预判：" + AutoFishController.leadMs() + " ms"),
            b -> {
                int ms = AutoFishController.cycleLead();
                setStatus(ms == 0
                    ? "收线预判已关闭：看见指针进绿段才点（慢速竿适用，快速竿容易错过）"
                    : "收线预判已切换：" + ms + " ms（按当前速度提前这么多点击）");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.literal(
                "指针速度是纯随机的：快的时候一次采样就移动十几到二十几像素，\n"
                    + "等真的看见它进绿段再按右键，点击落地时它早就跑出去了。\n"
                    + "这里按测出来的横向速度把指针外推到「这一下点击真正生效」的时刻再判定。\n"
                    + "调法：从 0 开始一档档加，加到该中的都能中为止；加过头会变成「抢跑点空」。\n"
                    + "服务器越远（延迟越高）需要越大。")))
            .dimensions(grid.x(13), grid.y(13), grid.colW, 20).build());

        // ---- 转R6：按参考图实测调整身体比例，躯干四肢加宽加厚、头部加宽并增高，Roblox 方块人 ----
        boolean r6On = R6ModelController.isEnabled();
        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.tool.r6_model." + (r6On ? "stop" : "start")),
            b -> {
                if (MinecraftClient.getInstance().player == null) {
                    setStatus("需要先进入世界才能转R6");
                    return;
                }
                boolean nowOn = R6ModelController.toggle();
                setStatus(nowOn
                    ? "转R6已开启：躯干×1.77、手臂×1.84、腿×1.75、头部加宽增高；按 F5 查看，只影响自己"
                    : "转R6已关闭，模型比例已恢复原版");
                clearAndInit();
            })
            .tooltip(Tooltip.of(Text.translatable("tidinmenu.tool.r6_model.tip")))
            .dimensions(grid.x(12), grid.y(12), grid.colW, 20).build());

        spinInfoY = Math.min(grid.bottom() + 8, panelY + panelH - 50);
    }

    private static String speedText() {
        return Text.translatable("tidinmenu.spin.speed." + SpinController.speedKey()).getString();
    }

    private static String directionText() {
        return Text.translatable(SpinController.isClockwise()
            ? "tidinmenu.spin.dir.cw" : "tidinmenu.spin.dir.ccw").getString();
    }

    private void buildWorld() {
        int gap = 12;
        int bw = Math.min(130, (panelW - 56 - gap) / 2);
        if (bw < 70) {
            bw = 70;
        }
        int totalW = bw * 2 + gap;
        int bx = panelX + (panelW - totalW) / 2;
        int top = panelY + panelH - 100;

        boolean inWorld = client != null && client.player != null && client.world != null;

        ButtonWidget day = ButtonWidget.builder(Text.translatable("tidinmenu.world.day"),
            b -> runCommand("time set day")).dimensions(bx, top, bw, 20).build();
        ButtonWidget night = ButtonWidget.builder(Text.translatable("tidinmenu.world.night"),
            b -> runCommand("time set night")).dimensions(bx + bw + gap, top, bw, 20).build();
        ButtonWidget clear = ButtonWidget.builder(Text.translatable("tidinmenu.world.clear"),
            b -> runCommand("weather clear")).dimensions(bx, top + 26, bw, 20).build();
        ButtonWidget rain = ButtonWidget.builder(Text.translatable("tidinmenu.world.rain"),
            b -> runCommand("weather rain")).dimensions(bx + bw + gap, top + 26, bw, 20).build();
        for (ButtonWidget w : new ButtonWidget[] { day, night, clear, rain }) {
            w.active = inWorld;
            addWidget(w);
        }
    }

    private void buildSettings() {
        int bw = Math.min(250, panelW - 56);
        int bx = panelX + (panelW - bw) / 2;
        int y = panelY + 86;
        int row = 26;

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.setting.sound." + (FunctionMenuMod.soundEnabled ? "on" : "off")),
            b -> {
                FunctionMenuMod.soundEnabled = !FunctionMenuMod.soundEnabled;
                setStatus(FunctionMenuMod.soundEnabled ? "呼出音效已开启" : "呼出音效已静音");
                clearAndInit();
            }).dimensions(bx, y, bw, 20).build());
        y += row;

        addWidget(ButtonWidget.builder(
            Text.translatable("tidinmenu.setting.overlay." + (FunctionMenuMod.infoOverlayEnabled ? "on" : "off")),
            b -> {
                FunctionMenuMod.infoOverlayEnabled = !FunctionMenuMod.infoOverlayEnabled;
                setStatus(FunctionMenuMod.infoOverlayEnabled ? "信息浮层已开启" : "信息浮层已关闭");
                clearAndInit();
            }).dimensions(bx, y, bw, 20).build());
        y += row;

        addWidget(ButtonWidget.builder(Text.translatable("tidinmenu.setting.restore"),
            b -> {
                FunctionMenuMod.soundEnabled = true;
                FunctionMenuMod.infoOverlayEnabled = false;
                setStatus("已恢复推荐默认：音效开 · 浮层关");
                clearAndInit();
            }).dimensions(bx, y, bw, 20).build());
        y += row;

        // ---- 完整设置页：基本区全是开关，高级区全是精度数值 ----
        addWidget(ButtonWidget.builder(Text.literal("详细设置…"),
            b -> MinecraftClient.getInstance().setScreen(new ModSettingsScreen(this)))
            .dimensions(bx, y, bw, 20).build());
    }

    // ---------------------------------------------------------------
    // 行为
    // ---------------------------------------------------------------

    private void copyCoords() {
        MinecraftClient client = MinecraftClient.getInstance();
        String text = WorldInfo.clipboardLine(client);
        if (text == null) {
            setStatus("需要先进入世界才能复制坐标");
            return;
        }
        GLFW.glfwSetClipboardString(client.getWindow().getHandle(), text);
        setStatus("已复制到剪贴板：" + text);
    }

    private void runCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            setStatus("需要先进入世界");
            return;
        }
        try {
            client.player.networkHandler.sendCommand(command);
            setStatus("已发送指令 /" + command + "（需作弊权限，服务器会提示结果）");
        } catch (Exception e) {
            setStatus("指令发送失败：" + command);
        }
    }

    private void setStatus(String text) {
        status = text;
        statusTicks = 140;
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks == 0) {
                status = null;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.shouldCloseOnEsc()) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    /** 面板打开时游戏不暂停，透出正在进行的画面。 */
    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------------------------------------------------------------
    // 绘制
    // ---------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, SCREEN_DIM);
        fillRoundedRect(context, panelX - 1, panelY - 1, panelW + 2, panelH + 2, CORNER_RADIUS + 1, PANEL_BORDER);
        fillRoundedRect(context, panelX, panelY, panelW, panelH, CORNER_RADIUS, PANEL_BG);

        drawTitle(context);
        context.fill(panelX + 12, panelY + 76, panelX + panelW - 12, panelY + 77, SEPARATOR);

        // 手动渲染控件：不调 super.render()，否则原版背景会盖在面板上面（见 widgetList 的注释）
        for (ClickableWidget widget : widgetList) {
            widget.render(context, mouseX, mouseY, delta);
        }

        if (tab == 0) {
            drawSpinReadout(context);
        } else if (tab == 1) {
            drawWorldInfo(context);
        } else if (tab == 2) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("tidinmenu.setting.desc"),
                this.width / 2, panelY + panelH - 58, 0xFFBFBFBF);
        }

        if (status != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, panelY + panelH - 36, TEXT_STATUS);
        }
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("tidinmenu.hint"), this.width / 2, panelY + panelH - 18, TEXT_HINT);
    }

    /** 顶部居中白色「功能」，原版字体放大 2 倍并带投影，距面板顶边约 24 像素。 */
    private void drawTitle(DrawContext context) {
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(panelX + panelW / 2.0, panelY + 22.0, 0.0);
        matrices.scale(2.0F, 2.0F, 1.0F);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, 0, 0, TEXT_WHITE);
        matrices.pop();
    }

    /** 工具页底部的实时状态行：按旋转模式显示档位与当前朝向，未旋转时显示待用的档位。 */
    private void drawSpinReadout(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        String line;
        int color;
        if (client == null || client.player == null) {
            line = "进入世界后即可开始旋转";
            color = TEXT_HINT;
        } else {
            SpinController.Mode mode = SpinController.getMode();
            if (mode == SpinController.Mode.VIEW) {
                float yaw = MathHelper.wrapDegrees(client.player.getYaw());
                line = String.format("旋转中 · %s · %s · %d°/秒 · 当前朝向 %s（%d°）",
                    speedText(), directionText(), SpinController.degreesPerSecond(),
                    WorldInfo.compass(yaw), Math.round(yaw));
                color = TEXT_STATUS;
            } else if (mode == SpinController.Mode.AIRPLANE) {
                float yaw = MathHelper.wrapDegrees(client.player.getYaw());
                line = String.format("抬臂旋转中 · %s · %s · %d°/秒 · 双臂平举 · 视角不转（朝向 %s）· 按 F5 观看",
                    speedText(), directionText(), SpinController.degreesPerSecond(), WorldInfo.compass(yaw));
                color = TEXT_STATUS;
            } else if (mode == SpinController.Mode.MODEL) {
                float yaw = MathHelper.wrapDegrees(client.player.getYaw());
                line = String.format("角色旋转中 · %s · %s · %d°/秒 · 视角不转（朝向 %s）· 按 F5 观看",
                    speedText(), directionText(), SpinController.degreesPerSecond(), WorldInfo.compass(yaw));
                color = TEXT_STATUS;
            } else {
                line = String.format("未旋转 · 待用档位：%s · %s · %d°/秒",
                    speedText(), directionText(), SpinController.degreesPerSecond());
                color = TEXT_HINT;
            }
        }
        context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, spinInfoY, color);
    }

    private void drawWorldInfo(DrawContext context) {
        List<String> lines = WorldInfo.summaryLines(MinecraftClient.getInstance());
        int y = panelY + 86;
        int bottom = panelY + panelH - 108;
        for (String line : lines) {
            if (y + 10 > bottom) {
                break;
            }
            context.drawTextWithShadow(this.textRenderer, line, panelX + 26, y, TEXT_INFO);
            y += 11;
        }
    }

    private void addWidget(ClickableWidget widget) {
        widgetList.add(widget);
        addDrawableChild(widget);
    }

    /**
     * 面板内的按钮网格：条目超过 4 个且面板够宽时自动排成两列（按列填充）；
     * 两列在小面板里仍会溢出时升到三列；面板偏矮时压缩行距（下限=按钮高+1），
     * 保证小窗口 / 大 GUI 缩放下条目都尽量画在面板内。
     */
    private static final class ButtonGrid {
        private static final int BTN_H = 20;
        private static final int GAP_X = 12;
        /** 一列按钮能塞下的最小行距（按钮高 20 + 缝 1）。 */
        private static final int MIN_STEP = BTN_H + 1;

        final int colW;
        final int rows;
        final int step;
        final int y0;
        private final int x0;

        ButtonGrid(int itemCount, int panelX, int panelY, int panelW, int panelH, int topOffset, int bottomReserve) {
            int usableW = panelW - 48;
            int cols = itemCount > 4 && usableW >= 176 ? 2 : 1;
            int available = (panelY + panelH - bottomReserve) - (panelY + topOffset);
            // 两列的行数在小面板里连最小行距都放不下时，改三列省一半高度。
            if (cols == 2 && usableW >= 84 * 3 + GAP_X * 2) {
                int rows2 = (itemCount + 1) / 2;
                if (rows2 * MIN_STEP - MIN_STEP + BTN_H > available) {
                    cols = 3;
                }
            }
            this.colW = Math.max(84, Math.min(248, (usableW - GAP_X * (cols - 1)) / cols));
            int totalW = this.colW * cols + GAP_X * (cols - 1);
            this.x0 = panelX + (panelW - totalW) / 2;
            this.rows = (itemCount + cols - 1) / cols;
            this.y0 = panelY + topOffset;
            int roomy = BTN_H + 6;
            this.step = this.rows <= 1
                ? roomy
                : Math.max(MIN_STEP, Math.min(roomy, (available - BTN_H) / (this.rows - 1)));
        }

        int x(int index) {
            return x0 + (index / rows) * (colW + GAP_X);
        }

        int y(int index) {
            return y0 + (index % rows) * step;
        }

        /** 网格最底边（最后一行按钮的下沿）。 */
        int bottom() {
            return y0 + (rows - 1) * step + BTN_H;
        }
    }

    /** 圆角矩形填充：逐行扫描近似圆弧，纯色 ARGB 填充。 */
    private static void fillRoundedRect(DrawContext context, int x, int y, int w, int h, int radius, int color) {
        int r = Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
        context.fill(x + r, y, x + w - r, y + h, color);
        context.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i;
            double inset = r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
            int in = (int) Math.round(inset);
            context.fill(x + in, y + i, x + w - in, y + i + 1, color);
            context.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, color);
        }
    }

    // ---------------------------------------------------------------
    // F3 调试信息开关
    //   GameOptions 上的这个字段名在 1.20.1 / 1.20.4 之间不一致（直接写会编译失败），
    //   所以用反射桥接：有就切换，没有就提示玩家自己按 F3。
    // ---------------------------------------------------------------

    private static Field debugInfoField(MinecraftClient client) {
        if (client == null || client.options == null) {
            return null;
        }
        try {
            return client.options.getClass().getField("debugEnabled");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isDebugInfoOn(MinecraftClient client) {
        Field field = debugInfoField(client);
        if (field == null) {
            return false;
        }
        try {
            return field.getBoolean(client.options);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** @return 切换后的状态；当前版本没有该字段时返回 null。 */
    private static Boolean toggleDebugInfo(MinecraftClient client) {
        Field field = debugInfoField(client);
        if (field == null) {
            return null;
        }
        try {
            boolean now = !field.getBoolean(client.options);
            field.setBoolean(client.options, now);
            return now;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // 标签页按钮
    // ---------------------------------------------------------------

    /**
     * 标签页按钮。用原版按钮而不是自绘控件：{@code PressableWidget} 的绘制钩子
     * 在 1.20.1 叫 {@code renderButton}、在 1.20.4 改名为 {@code renderWidget}，
     * 同一份源码没法在两边都写 @Override，所以统一用 ButtonWidget，
     * 选中页用「▸」前缀 + 置灰表示。
     */
    private ButtonWidget tabButton(int x, int y, int w, int h, int index, String langKey) {
        MutableText label = Text.translatable(langKey);
        if (tab == index) {
            label = Text.literal("▸ ").append(label);
        }
        ButtonWidget button = ButtonWidget.builder(label, b -> {
            if (tab != index) {
                tab = index;
                clearAndInit();
            }
        }).dimensions(x, y, w, h).build();
        button.active = tab != index;
        return button;
    }
}
