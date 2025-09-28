package org.alex_melan.secureAuth.managers;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.database.DatabaseManager;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final SecureAuthPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();

    public SessionManager(SecureAuthPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Boolean> createSession(String username, String ipAddress) {
        if (isRapidIpChange(username, ipAddress)) {
            plugin.getLogger().warning("Быстрая смена IP для пользователя " + username + ": " + ipAddress);
            return CompletableFuture.completedFuture(false);
        }

        return databaseManager.createSession(username, ipAddress)
                .thenApply(sessionHash -> {
                    if (sessionHash != null) {
                        activeSessions.put(username.toLowerCase(), sessionHash);
                        plugin.getLogger().info("Создана сессия для пользователя " + username + " с IP " + ipAddress);
                        return true;
                    }
                    plugin.getLogger().warning("Не удалось создать сессию для пользователя " + username);
                    return false;
                });
    }

    public CompletableFuture<Boolean> validateSession(String username, String ipAddress) {
        String sessionHash = activeSessions.get(username.toLowerCase());
        if (sessionHash == null) {
            return CompletableFuture.completedFuture(false);
        }

        return databaseManager.validateSession(sessionHash, username, ipAddress)
                .thenApply(valid -> {
                    if (!valid) {
                        activeSessions.remove(username.toLowerCase());
                        plugin.getLogger().info("Недействительная сессия удалена для пользователя " + username);
                    }
                    return valid;
                });
    }

    public void invalidateSession(String username) {
        String sessionHash = activeSessions.remove(username.toLowerCase());
        if (sessionHash != null) {
            databaseManager.invalidateSession(sessionHash);
            plugin.getLogger().info("Сессия деактивирована для пользователя " + username);
        }
    }

    public void invalidateAllSessions() {
        plugin.getLogger().info("Деактивация всех активных сессий...");
        for (String sessionHash : activeSessions.values()) {
            databaseManager.invalidateSession(sessionHash);
        }
        activeSessions.clear();
        plugin.getLogger().info("Все сессии деактивированы");
    }

    public boolean isAuthenticated(String username) {
        return activeSessions.containsKey(username.toLowerCase());
    }

    /**
     * Помечает игрока как авторизованного (для восстановления сессий)
     */
    public void markAsAuthenticated(String username) {
        // Создаем временную сессию в памяти при восстановлении
        if (!activeSessions.containsKey(username.toLowerCase())) {
            activeSessions.put(username.toLowerCase(), "restored");
            plugin.getLogger().fine("Игрок " + username + " помечен как авторизованный при восстановлении сессии");
        }
    }

    public void cleanExpiredSessions() {
        databaseManager.cleanExpiredSessions().thenRun(() -> {
            // Также очищаем локальный кеш от потенциально недействительных сессий
            activeSessions.entrySet().removeIf(entry -> {
                String username = entry.getKey();
                String sessionHash = entry.getValue();

                // Проверяем каждую сессию в кеше
                try {
                    return !databaseManager.validateSession(sessionHash, username, "validate").get();
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка проверки сессии при очистке для " + username);
                    return true; // Удаляем проблемную сессию
                }
            });
        });
    }

    private boolean isRapidIpChange(String username, String ipAddress) {
        if (!plugin.getConfigManager().isIpCheckEnabled()) {
            return false;
        }

        try {
            return plugin.getAuthManager().loadPlayerData(username)
                    .thenApply(data -> {
                        if (data != null && data.getLastIp() != null) {
                            if (!data.getLastIp().equals(ipAddress)) {
                                long timeSinceLastLogin = System.currentTimeMillis() - data.getLastLogin();
                                boolean isRapid = timeSinceLastLogin < plugin.getConfigManager().getIpChangeGracePeriod();

                                if (isRapid) {
                                    plugin.getLogger().warning(String.format(
                                            "Быстрая смена IP для %s: %s -> %s (прошло %d мс)",
                                            username, data.getLastIp(), ipAddress, timeSinceLastLogin
                                    ));
                                }

                                return isRapid;
                            }
                        }
                        return false;
                    }).get(); // Синхронный результат
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка проверки смены IP для " + username + ": " + e.getMessage());
            return false; // В случае ошибки разрешаем вход
        }
    }

    public boolean isLoginBlocked(String ipAddress) {
        Long lastAttempt = loginAttempts.get(ipAddress);
        Integer attempts = attemptCounts.get(ipAddress);

        if (lastAttempt == null || attempts == null) {
            return false;
        }

        long timeSinceLastAttempt = System.currentTimeMillis() - lastAttempt;

        if (timeSinceLastAttempt > plugin.getConfigManager().getLoginBlockDuration()) {
            // Время блокировки истекло
            loginAttempts.remove(ipAddress);
            attemptCounts.remove(ipAddress);
            return false;
        }

        boolean blocked = attempts >= plugin.getConfigManager().getMaxLoginAttempts();

        if (blocked) {
            long remainingTime = plugin.getConfigManager().getLoginBlockDuration() - timeSinceLastAttempt;
            plugin.getLogger().info(String.format(
                    "IP %s заблокирован на %d секунд (%d попыток)",
                    ipAddress, remainingTime / 1000, attempts
            ));
        }

        return blocked;
    }

    public void recordFailedLogin(String ipAddress) {
        loginAttempts.put(ipAddress, System.currentTimeMillis());
        int newCount = attemptCounts.merge(ipAddress, 1, Integer::sum);

        plugin.getLogger().warning(String.format(
                "Неудачная попытка входа с IP %s (попытка %d/%d)",
                ipAddress, newCount, plugin.getConfigManager().getMaxLoginAttempts()
        ));

        // Логируем в базу данных
        if (plugin.getConfigManager().isLogFailedLogins()) {
            databaseManager.logSecurityAction(null, ipAddress, "FAILED_LOGIN", false,
                    "Failed login attempt " + newCount + "/" + plugin.getConfigManager().getMaxLoginAttempts());
        }
    }

    public void clearFailedLogins(String ipAddress) {
        Integer attempts = attemptCounts.remove(ipAddress);
        loginAttempts.remove(ipAddress);

        if (attempts != null && attempts > 0) {
            plugin.getLogger().info("Очищены неудачные попытки входа для IP " + ipAddress + " (" + attempts + " попыток)");
        }
    }

    // Получение статистики
    public int getActiveSessionsCount() {
        return activeSessions.size();
    }

    public int getFailedAttemptsCount(String ipAddress) {
        return attemptCounts.getOrDefault(ipAddress, 0);
    }

    public long getRemainingBlockTime(String ipAddress) {
        Long lastAttempt = loginAttempts.get(ipAddress);
        if (lastAttempt == null) {
            return 0;
        }

        long timeSinceLastAttempt = System.currentTimeMillis() - lastAttempt;
        long remainingTime = plugin.getConfigManager().getLoginBlockDuration() - timeSinceLastAttempt;

        return Math.max(0, remainingTime);
    }

    // Принудительная разлогинизация пользователя
    public void forceLogout(String username) {
        invalidateSession(username);

        // Отправляем игрока в лобби если он онлайн
        org.bukkit.entity.Player player = Bukkit.getPlayerExact(username);
        if (player != null) {
            plugin.getLobbyManager().sendToAuthLobby(player);
            player.sendMessage(plugin.getConfigManager().getMessage("force-logout"));
        }

        // Логируем принудительный выход
        databaseManager.logSecurityAction(username, "admin", "FORCE_LOGOUT", true, "Forced logout by admin");
    }

    // Очистка старых записей о неудачных попытках
    public void cleanupFailedAttempts() {
        long now = System.currentTimeMillis();
        long blockDuration = plugin.getConfigManager().getLoginBlockDuration();

        loginAttempts.entrySet().removeIf(entry ->
                now - entry.getValue() > blockDuration
        );

        attemptCounts.entrySet().removeIf(entry ->
                !loginAttempts.containsKey(entry.getKey())
        );
    }

    // Получение информации о сессии пользователя
    public String getSessionInfo(String username) {
        String sessionHash = activeSessions.get(username.toLowerCase());
        if (sessionHash == null) {
            return null;
        }

        return "Сессия: " + sessionHash.substring(0, 8) + "...";
    }
}