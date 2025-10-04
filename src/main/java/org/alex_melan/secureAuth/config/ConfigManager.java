package org.alex_melan.secureAuth.config;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final SecureAuthPlugin plugin;
    private FileConfiguration config;

    // Настройки безопасности
    private long sessionTTL;
    private int maxLoginAttempts;
    private long loginBlockDuration;
    private boolean enforcePasswordComplexity;
    private boolean enableIpCheck;
    private long ipChangeGracePeriod;
    private int bcryptRounds;

    // Настройки системы
    private int autoSaveInterval;
    private boolean logSuccessfulLogins;
    private boolean logFailedLogins;
    private boolean logRegistrations;
    private boolean enableUuidLinking;

    // Настройки лобби
    private String lobbyWorld;
    private double lobbyX, lobbyY, lobbyZ;
    private float lobbyYaw, lobbyPitch;
    private boolean giveStarterItems;

    public ConfigManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        // Сохраняем дефолтную конфигурацию если её нет
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Загружаем настройки безопасности
        loadSecuritySettings();

        // Загружаем системные настройки
        loadSystemSettings();

        // Загружаем настройки лобби
        loadLobbySettings();

        // Валидация настроек
        validateSettings();

        // Логируем загруженные настройки
        logConfiguration();
    }

    private void loadSecuritySettings() {
        sessionTTL = config.getLong("session.ttl-hours", 24) * 60 * 60 * 1000;
        maxLoginAttempts = config.getInt("security.max-login-attempts", 5);
        loginBlockDuration = config.getLong("security.login-block-minutes", 15) * 60 * 1000;
        enforcePasswordComplexity = config.getBoolean("security.enforce-password-complexity", true);
        enableIpCheck = config.getBoolean("security.enable-ip-check", true);
        ipChangeGracePeriod = config.getLong("security.ip-change-grace-minutes", 30) * 60 * 1000;
        bcryptRounds = config.getInt("security.bcrypt-rounds", 12);
    }

    private void loadSystemSettings() {
        autoSaveInterval = config.getInt("misc.auto-save-interval-minutes", 5);
        logSuccessfulLogins = config.getBoolean("misc.log-successful-logins", true);
        logFailedLogins = config.getBoolean("misc.log-failed-logins", true);
        logRegistrations = config.getBoolean("misc.log-registrations", true);
        enableUuidLinking = config.getBoolean("misc.enable-uuid-linking", true);
    }

    private void loadLobbySettings() {
        lobbyWorld = config.getString("lobby.world", "world");
        lobbyX = config.getDouble("lobby.x", 0.5);
        lobbyY = config.getDouble("lobby.y", 100);
        lobbyZ = config.getDouble("lobby.z", 0.5);
        lobbyYaw = (float) config.getDouble("lobby.yaw", 0);
        lobbyPitch = (float) config.getDouble("lobby.pitch", 0);
        giveStarterItems = config.getBoolean("misc.give-starter-items", false);
    }

    private void validateSettings() {
        // Валидация TTL сессий (от 1 часа до 30 дней)
        if (sessionTTL < 60 * 60 * 1000 || sessionTTL > 30L * 24 * 60 * 60 * 1000) {
            plugin.getLogger().warning("Некорректное значение TTL сессий, использую значение по умолчанию (24 часа)");
            sessionTTL = 24 * 60 * 60 * 1000;
        }

        // Валидация максимального количества попыток входа
        if (maxLoginAttempts < 1 || maxLoginAttempts > 50) {
            plugin.getLogger().warning("Некорректное значение максимальных попыток входа, использую значение по умолчанию (5)");
            maxLoginAttempts = 5;
        }

        // Валидация времени блокировки
        if (loginBlockDuration < 60 * 1000 || loginBlockDuration > 24 * 60 * 60 * 1000) {
            plugin.getLogger().warning("Некорректное время блокировки входа, использую значение по умолчанию (15 минут)");
            loginBlockDuration = 15 * 60 * 1000;
        }

        // Валидация раундов BCrypt
        if (bcryptRounds < 10 || bcryptRounds > 15) {
            plugin.getLogger().warning("Некорректное количество раундов BCrypt, использую значение по умолчанию (12)");
            bcryptRounds = 12;
        }

        // Валидация интервала автосохранения
        if (autoSaveInterval < 1 || autoSaveInterval > 60) {
            plugin.getLogger().warning("Некорректный интервал автосохранения, использую значение по умолчанию (5 минут)");
            autoSaveInterval = 5;
        }

        // Валидация координат лобби
        if (lobbyY < -64 || lobbyY > 320) {
            plugin.getLogger().warning("Некорректная Y координата лобби, использую значение по умолчанию (100)");
            lobbyY = 100;
        }
    }

    private void logConfiguration() {
        plugin.getLogger().info("=== Конфигурация загружена ===");
        plugin.getLogger().info("TTL сессий: " + (sessionTTL / 1000 / 60 / 60) + " часов");
        plugin.getLogger().info("Максимум попыток входа: " + maxLoginAttempts);
        plugin.getLogger().info("Время блокировки: " + (loginBlockDuration / 1000 / 60) + " минут");
        plugin.getLogger().info("BCrypt раундов: " + bcryptRounds);
        plugin.getLogger().info("Проверка IP: " + (enableIpCheck ? "включена" : "отключена"));
        plugin.getLogger().info("Сложность паролей: " + (enforcePasswordComplexity ? "включена" : "отключена"));
        plugin.getLogger().info("Автосохранение: каждые " + autoSaveInterval + " минут");
        plugin.getLogger().info("Мир лобби: " + lobbyWorld);
        plugin.getLogger().info("================================");
    }

    // Геттеры для настроек безопасности
    public long getSessionTTL() { return sessionTTL; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public long getLoginBlockDuration() { return loginBlockDuration; }
    public boolean isPasswordComplexityEnforced() { return enforcePasswordComplexity; }
    public boolean isIpCheckEnabled() { return enableIpCheck; }
    public long getIpChangeGracePeriod() { return ipChangeGracePeriod; }
    public int getBcryptRounds() { return bcryptRounds; }

    // Геттеры для системных настроек
    public int getAutoSaveInterval() { return autoSaveInterval; }
    public boolean isLogSuccessfulLogins() { return logSuccessfulLogins; }
    public boolean isLogFailedLogins() { return logFailedLogins; }
    public boolean isLogRegistrations() { return logRegistrations; }
    public boolean isUuidLinkingEnabled() { return enableUuidLinking; }

    // Геттеры для настроек лобби
    public String getLobbyWorld() { return lobbyWorld; }
    public double getLobbyX() { return lobbyX; }
    public double getLobbyY() { return lobbyY; }
    public double getLobbyZ() { return lobbyZ; }
    public float getLobbyYaw() { return lobbyYaw; }
    public float getLobbyPitch() { return lobbyPitch; }
    public boolean isGiveStarterItems() { return giveStarterItems; }

    // Получение сообщений с поддержкой плейсхолдеров
    public String getMessage(String key) {
        return getMessage(key, (String) null);
    }

    public String getMessage(String key, String... placeholders) {
        String message = config.getString("messages." + key, "§cСообщение не найдено: " + key);
        String prefix = config.getString("messages.prefix", "§8[§6SecureAuth§8]§r ");

        // Добавляем префикс если его нет
        if (!message.contains(prefix) && !key.endsWith("-title") && !key.endsWith("-footer")) {
            message = prefix + message;
        }

        // Заменяем плейсхолдеры
        if (placeholders != null && placeholders.length > 0) {
            for (int i = 0; i < placeholders.length; i++) {
                message = message.replace("{" + i + "}", placeholders[i]);
            }
        }

        // Стандартные плейсхолдеры
        message = message.replace("{prefix}", prefix);
        message = message.replace("{plugin}", plugin.getDescription().getName());
        message = message.replace("{version}", plugin.getDescription().getVersion());

        return message.replace("&", "§");
    }

    // Получение списка строк (для многострочных сообщений)
    public String[] getMessageList(String key) {
        if (config.isList("messages." + key)) {
            return config.getStringList("messages." + key).toArray(new String[0]);
        } else {
            return new String[]{getMessage(key)};
        }
    }

    // Проверка существования сообщения
    public boolean hasMessage(String key) {
        return config.contains("messages." + key);
    }

    // Получение сырого значения из конфига
    public FileConfiguration getConfig() {
        return config;
    }

    // Сохранение изменений в конфиг
    public void saveConfig() {
        plugin.saveConfig();
    }

    // Установка значения в конфиг
    public void setValue(String path, Object value) {
        config.set(path, value);
        saveConfig();
    }
}