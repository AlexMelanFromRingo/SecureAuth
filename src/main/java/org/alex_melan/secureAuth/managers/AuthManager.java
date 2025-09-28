package org.alex_melan.secureAuth.managers;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.database.DatabaseManager;
import org.alex_melan.secureAuth.models.PlayerData;
import org.alex_melan.secureAuth.utils.PasswordUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final SecureAuthPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Map<String, PlayerData> playerDataCache = new ConcurrentHashMap<>();

    public AuthManager(SecureAuthPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Boolean> registerPlayer(String username, String password, UUID crackedUuid) {
        return CompletableFuture.supplyAsync(() -> {
            // Валидация пароля
            if (!PasswordUtils.isPasswordValid(password)) {
                return false;
            }

            return true;
        }).thenCompose(valid -> {
            if (!valid) {
                return CompletableFuture.completedFuture(false);
            }

            // Проверка, не зарегистрирован ли уже
            return databaseManager.isPlayerRegistered(username)
                    .thenCompose(registered -> {
                        if (registered) {
                            return CompletableFuture.completedFuture(false);
                        }

                        return databaseManager.registerPlayer(username, password, crackedUuid);
                    });
        });
    }

    public CompletableFuture<Boolean> authenticatePlayer(String username, String password, String ipAddress) {
        return databaseManager.authenticatePlayer(username, password, ipAddress);
    }

    public CompletableFuture<PlayerData> loadPlayerData(String username) {
        return databaseManager.getPlayerData(username)
                .thenApply(data -> {
                    if (data != null) {
                        playerDataCache.put(username.toLowerCase(), data);
                    }
                    return data;
                });
    }

    public void savePlayerData(Player player) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data != null) {
            data.saveFromPlayer(player);

            // Асинхронное сохранение в БД
            databaseManager.savePlayerData(data);
        }
    }

    public void saveAllPlayerData() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getSessionManager().isAuthenticated(player.getName())) {
                savePlayerData(player);
            }
        }
    }

    public PlayerData getCachedPlayerData(String username) {
        return playerDataCache.get(username.toLowerCase());
    }

    public void removeCachedData(String username) {
        playerDataCache.remove(username.toLowerCase());
    }
}