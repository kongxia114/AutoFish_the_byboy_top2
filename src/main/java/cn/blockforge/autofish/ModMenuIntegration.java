package cn.blockforge.autofish;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成：让「模组列表」里这个模组出现「配置」按钮，点开就是 Cloth Config 设置页。
 *
 * <p>Mod Menu 是<strong>可选</strong>依赖：没装 Mod Menu 时这个 entrypoint 不会被加载，
 * 设置页仍然可以用 F8 打开。所以 build.gradle 里用的是 {@code modCompileOnly}。
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AutoFishSettings::buildScreen;
    }
}
