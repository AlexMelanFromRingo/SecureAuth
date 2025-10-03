package org.alex_melan.secureAuth.managers;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.database.DatabaseManager;
import org.bukkit.Bukkit;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        plugin.getLogger().info("Запуск очистки просроченных сессий...");

        // Получаем список онлайн игроков для защиты их сессий
        Set<String> onlinePlayers = new HashSet<>();
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getName().toLowerCase());
        }

        // Очищаем только просроченные сессии в БД
        databaseManager.cleanExpiredSessions().thenRun(() -> {
            plugin.getLogger().info("Очистка просроченных сессий в БД завершена");

            // ИСПРАВЛЕНО: Очищаем из кеша только сессии ОФЛАЙН игроков, которые не в БД
            int removedFromCache = 0;
            Iterator<Map.Entry<String, String>> iterator = activeSessions.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                String username = entry.getKey();

                // КРИТИЧНО: НЕ трогаем сессии онлайн игроков!
                if (onlinePlayers.contains(username)) {
                    plugin.getLogger().fine("Пропускаем активную сессию онлайн игрока: " + username);
                    continue;
                }

                // Проверяем только офлайн игроков
                org.bukkit.entity.Player player = Bukkit.getPlayerExact(username);
                if (player == null || !player.isOnline()) {
                    // Игрок офлайн - можем проверить его сессию в БД
                    String sessionHash = entry.getValue();

                    // Получаем IP из базы для проверки (асинхронно)
                    CompletableFuture<Boolean> validationFuture = plugin.getDatabaseManager()
                            .getPlayerData(username)
                            .thenCompose(data -> {
                                if (data != null && data.getLastIp() != null) {
                                    return plugin.getDatabaseManager()
                                            .validateSession(sessionHash, username, data.getLastIp());
                                }
                                return CompletableFuture.completedFuture(false);
                            });

                    try {
                        // Ждем результат (это уже в async задаче)
                        boolean isValid = validationFuture.get(5, TimeUnit.SECONDS);

                        if (!isValid) {
                            iterator.remove();
                            removedFromCache++;
                            plugin.getLogger().fine("Удалена недействительная сессия из кеша: " + username);
                        }
                    } catch (Exception e) {
                        // В случае ошибки НЕ удаляем сессию - безопаснее
                        plugin.getLogger().warning("Ошибка проверки сессии при очистке для офлайн игрока " +
                                username + ": " + e.getMessage());
                    }
                }
            }

            if (removedFromCache > 0) {
                plugin.getLogger().info("Удалено " + removedFromCache + " недействительных сессий из кеша");
            }
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Ошибка при очистке просроченных сессий: " + ex.getMessage());
            return null;
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

    /**
     * ДОБАВЛЕНО: Синхронная деактивация всех сессий для корректного завершения плагина
     * Используется при onDisable чтобы избежать проблем с закрытым DataSource
     */
    public void invalidateAllSessionsSync() {
        plugin.getLogger().info("Синхронная деактивация всех активных сессий...");

        int count = activeSessions.size();
        if (count == 0) {
            plugin.getLogger().info("Активных сессий нет");
            return;
        }

        // Деактивируем все сессии синхронно
        for (Map.Entry<String, String> entry : activeSessions.entrySet()) {
            String username = entry.getKey();
            String sessionHash = entry.getValue();

            try {
                // Вызываем синхронный метод деактивации
                databaseManager.invalidateSessionSync(sessionHash);
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка деактивации сессии для " + username + ": " + e.getMessage());
            }
        }

        activeSessions.clear();
        plugin.getLogger().info("Деактивировано " + count + " сессий");
    }
}