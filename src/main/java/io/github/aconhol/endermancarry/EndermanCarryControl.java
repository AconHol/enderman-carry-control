package io.github.aconhol.endermancarry;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EndermanCarryControl implements ModInitializer {
    public static final String MOD_ID = "enderman_carry_control";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ConfigStore CONFIG = new ConfigStore(
            FabricLoader.getInstance().getConfigDir().resolve("enderman-carry-control.json"),
            BlockRules::validateCanonical);

    public static ConfigStore config() { return CONFIG; }

    @Override
    public void onInitialize() {
        try {
            CONFIG.initialize();
        } catch (Exception e) {
            // Refuse to start rather than silently enabling griefing with a broken configuration.
            throw new IllegalStateException("无法加载 " + CONFIG.path() + "。请修复配置后重启；文件未被覆盖。", e);
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CarryCommands.register(dispatcher));
        LOGGER.info("Enderman Carry Control initialized. Config: {}", CONFIG.path());
    }
}
