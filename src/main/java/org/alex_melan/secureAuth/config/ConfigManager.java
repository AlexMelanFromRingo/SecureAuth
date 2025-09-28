package org.alex_melan.secureAuth.config;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final SecureAuthPlugin plugin;
    private FileConfiguration config;

    // Настройки
    private long sessionTTL;
    private int maxLoginAttempts;
    private long loginBlockDuration;
    private boolean enforcePasswordComplexity;
    private boolean enableIpCheck;
    private long ipChangeGracePeriod;
    private int bcryptRounds;

    public ConfigManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        // Загружаем все настройки
        sessionTTL = config.getLong("session.ttl-hours", 24) * 60 * 60 * 1000;
        maxLoginAttempts = config.getInt("security.max-login-attempts", 5);
        loginBlockDuration = config.getLong("security.login-block-minutes", 15) * 60 * 1000;
        enforcePasswordComplexity = config.getBoolean("security.enforce-password-complexity", true);
        enableIpCheck = config.getBoolean("security.enable-ip-check", true);
        ipChangeGracePeriod = config.getLong("security.ip-change-grace-minutes", 30) * 60 * 1000;
        bcryptRounds = Math.max(10, Math.min(15, config.getInt("security.bcrypt-rounds", 12)));

        plugin.getLogger().info("Конфигурация загружена:");
        plugin.getLogger().info("- TTL сессий: " + (sessionTTL / 1000 / 60 / 60) + " часов");
        plugin.getLogger().info("- Макс. попыток входа: " + maxLoginAttempts);
        plugin.getLogger().info("- BCrypt раундов: " + bcryptRounds);
        plugin.getLogger().info("- Проверка IP: " + (enableIpCheck ? "включена" : "отключена"));
    }

    // Все геттеры
    public long getSessionTTL() { return sessionTTL; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public long getLoginBlockDuration() { return loginBlockDuration; }
    public boolean isPasswordComplexityEnforced() { return enforcePasswordComplexity; }
    public boolean isIpCheckEnabled() { return enableIpCheck; }
    public long getIpChangeGracePeriod() { return ipChangeGracePeriod; }
    public int getBcryptRounds() { return bcryptRounds; }

    public String getMessage(String key) {
        return config.getString("messages." + key, "§cСообщение не найдено: " + key)
                .replace("{prefix}", config.getString("messages.prefix", "§8[§6SecureAuth§8]§r "));
    }
}