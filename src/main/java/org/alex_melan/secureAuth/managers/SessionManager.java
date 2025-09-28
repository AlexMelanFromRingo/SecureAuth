package org.alex_melan.secureAuth.managers;

// SessionManager (полная версия)

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
            return CompletableFuture.completedFuture(false);
        }

        return databaseManager.createSession(username, ipAddress)
                .thenApply(sessionHash -> {
                    if (sessionHash != null) {
                        activeSessions.put(username.toLowerCase(), sessionHash);
                        return true;
                    }
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
                    }
                    return valid;
                });
    }

    public void invalidateSession(String username) {
        String sessionHash = activeSessions.remove(username.toLowerCase());
        if (sessionHash != null) {
            databaseManager.invalidateSession(sessionHash);
        }
    }

    public boolean isAuthenticated(String username) {
        return activeSessions.containsKey(username.toLowerCase());
    }

    public void cleanExpiredSessions() {
        databaseManager.cleanExpiredSessions();
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
                                return timeSinceLastLogin < plugin.getConfigManager().getIpChangeGracePeriod();
                            }
                        }
                        return false;
                    }).get(); // Синхронный результат
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка проверки смены IP для " + username);
            return false;
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
            loginAttempts.remove(ipAddress);
            attemptCounts.remove(ipAddress);
            return false;
        }

        return attempts >= plugin.getConfigManager().getMaxLoginAttempts();
    }

    public void recordFailedLogin(String ipAddress) {
        loginAttempts.put(ipAddress, System.currentTimeMillis());
        attemptCounts.merge(ipAddress, 1, Integer::sum);
    }

    public void clearFailedLogins(String ipAddress) {
        loginAttempts.remove(ipAddress);
        attemptCounts.remove(ipAddress);
    }
}