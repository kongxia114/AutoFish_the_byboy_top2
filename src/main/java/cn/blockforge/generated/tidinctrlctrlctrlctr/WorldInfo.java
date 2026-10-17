package cn.blockforge.generated.tidinctrlctrlctrlctr;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 面板「世界」页与 HUD 信息浮层共用的实时信息格式化。全部只读，不改动任何世界数据。 */
public final class WorldInfo {
    private static final String[] COMPASS = {"南", "西南", "西", "西北", "北", "东北", "东", "东南"};

    private WorldInfo() { }

    public static List<String> summaryLines(MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            lines.add("当前不在世界中");
            return lines;
        }
        lines.add("维度：" + dimensionName(world));
        lines.add("生物群系：" + biomeName(world, player.getBlockPos()));
        lines.add(String.format("坐标：X %.0f　Y %.0f　Z %.0f", player.getX(), player.getY(), player.getZ()));
        lines.add("朝向：" + compass(player.getYaw()));
        lines.add("时间：" + clock(world.getTimeOfDay()));
        lines.add("天气：" + (world.isRaining() ? "雨 / 雪" : "晴"));
        return lines;
    }

    /** 复制到剪贴板用的一行坐标；不在世界中时返回 null。 */
    public static String clipboardLine(MinecraftClient client) {
        if (client == null || client.player == null) {
            return null;
        }
        return String.format("X: %.1f  Y: %.1f  Z: %.1f",
            client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private static String dimensionName(ClientWorld world) {
        try {
            return world.getRegistryKey().getValue().getPath();
        } catch (Exception | LinkageError e) {
            return "未知";
        }
    }

    public static String biomeName(ClientWorld world, BlockPos pos) {
        try {
            RegistryEntry<?> entry = world.getBiomeAccess().getBiome(pos);
            Optional<?> key = entry.getKey();
            if (key.isPresent() && key.get() instanceof RegistryKey<?> registryKey) {
                return Text.translatable(registryKey.getValue().toTranslationKey("biome")).getString();
            }
        } catch (Exception | LinkageError e) {
            // 落到默认值
        }
        return "未知";
    }

    public static String compass(float yaw) {
        float r = ((yaw % 360.0F) + 360.0F) % 360.0F;
        int idx = (int) (Math.round(r / 45.0F) % 8.0F);
        return COMPASS[idx];
    }

    /** 游戏内钟点：0 tick = 06:00，一天 24000 tick。 */
    public static String clock(long timeOfDay) {
        long t = ((timeOfDay % 24000L) + 24000L) % 24000L;
        long hour = (t / 1000L + 6L) % 24L;
        long minute = (t % 1000L) * 60L / 1000L;
        return String.format("%02d:%02d", hour, minute);
    }
}
