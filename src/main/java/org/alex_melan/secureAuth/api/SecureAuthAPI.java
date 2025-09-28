package org.alex_melan.secureAuth.api;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.models.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * SecureAuth API для интеграции с другими плагинами
 *
 * Этот класс предоставляет публичные методы для взаимодействия
 * с системой авторизации SecureAuth из других плагинов.
 *
 * @author Alex Melan
 * @version 1.0
 */
public class SecureAuthAPI {

    private final SecureAuthPlugin plugin;

    public SecureAuthAPI(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Проверка авторизации игрока
     *
     * @param playerName имя игрока
     * @return true если игрок авторизован
     */
    public boolean isPlayerAuthenticated(String playerName) {
        if (playerName == null || !plugin.isFullyInitialized()) {
            return false;
        }

        return plugin.getSessionManager().isAuthenticated(playerName);
    }

    /**
     * Проверка авторизации игрока
     *
     * @param player игрок
     * @return true если игрок авторизован
     */
    public boolean isPlayerAuthenticated(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        return isPlayerAuthenticated(player.getName());
    }

    /**
     * Проверка регистрации игрока (асинхронно)
     *
     * @param playerName имя игрока
     * @return CompletableFuture с результатом
     */
    public CompletableFuture<Boolean> isPlayerRegistered(String playerName) {
        if (playerName == null || !plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(false);
        }

        return plugin.getDatabaseManager().isPlayerRegistered(playerName)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "API: Ошибка проверки регистрации для " + playerName, ex);
                    return false;
                });
    }

    /**
     * Получение данных игрока (асинхронно)
     *
     * @param playerName имя игрока
     * @return CompletableFuture с данными игрока или null
     */
    public CompletableFuture<PlayerData> getPlayerData(String playerName) {
        if (playerName == null || !plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(null);
        }

        return plugin.getDatabaseManager().getPlayerData(playerName)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "API: Ошибка получения данных для " + playerName, ex);
                    return null;
                });
    }

    /**
     * Принудительный выход игрока из системы
     *
     * @param playerName имя игрока
     * @return true если операция выполнена успешно
     */
    public boolean forceLogout(String playerName) {
        if (playerName == null || !plugin.isFullyInitialized()) {
            return false;
        }

        try {
            plugin.getSessionManager().forceLogout(playerName);

            // Вызываем событие
            PlayerForceLogoutEvent event = new PlayerForceLogoutEvent(playerName, "API");
            plugin.getServer().getPluginManager().callEvent(event);

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "API: Ошибка принудительного выхода для " + playerName, e);
            return false;
        }
    }

    /**
     * Создание сессии для игрока (асинхронно)
     *
     * @param playerName имя игрока
     * @param ipAddress IP адрес
     * @return CompletableFuture с результатом
     */
    public CompletableFuture<Boolean> createSession(String playerName, String ipAddress) {
        if (playerName == null || ipAddress == null || !plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(false);
        }

        return plugin.getSessionManager().createSession(playerName, ipAddress)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "API: Ошибка создания сессии для " + playerName, ex);
                    return false;
                });
    }

    /**
     * Деактивация сессии игрока
     *
     * @param playerName имя игрока
     * @return true если операция выполнена успешно
     */
    public boolean invalidateSession(String playerName) {
        if (playerName == null || !plugin.isFullyInitialized()) {
            return false;
        }

        try {
            plugin.getSessionManager().invalidateSession(playerName);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "API: Ошибка деактивации сессии для " + playerName, e);
            return false;
        }
    }

    /**
     * Получение количества активных сессий (асинхронно)
     *
     * @return CompletableFuture с количеством сессий
     */
    public CompletableFuture<Integer> getActiveSessionsCount() {
        if (!plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(0);
        }

        return plugin.getDatabaseManager().getActiveSessionsCount()
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "API: Ошибка получения количества сессий", ex);
                    return 0;
                });
    }

    /**
     * Привязка Premium UUID к аккаунту (асинхронно)
     *
     * @param playerName имя игрока
     * @param premiumUuid Premium UUID
     * @return CompletableFuture с результатом
     */
    public CompletableFuture<Boolean> linkPremiumUuid(String playerName, UUID premiumUuid) {
        if (playerName == null || premiumUuid == null || !plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(false);
        }

        return plugin.getAuthManager().linkPremiumUuid(playerName, premiumUuid)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "API: Ошибка привязки Premium UUID для " + playerName, ex);
                    return false;
                });
    }

    /**
     * Получение информации о блокировке IP
     *
     * @param ipAddress IP адрес
     * @return информация о блокировке
     */
    public IpBlockInfo getIpBlockInfo(String ipAddress) {
        if (ipAddress == null || !plugin.isFullyInitialized()) {
            return new IpBlockInfo(false, 0, 0);
        }

        boolean isBlocked = plugin.getSessionManager().isLoginBlocked(ipAddress);
        int attempts = plugin.getSessionManager().getFailedAttemptsCount(ipAddress);
        long remainingTime = plugin.getSessionManager().getRemainingBlockTime(ipAddress);

        return new IpBlockInfo(isBlocked, attempts, remainingTime);
    }

    /**
     * Очистка неудачных попыток входа для IP
     *
     * @param ipAddress IP адрес
     * @return true если операция выполнена успешно
     */
    public boolean clearFailedAttempts(String ipAddress) {
        if (ipAddress == null || !plugin.isFullyInitialized()) {
            return false;
        }

        try {
            plugin.getSessionManager().clearFailedLogins(ipAddress);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "API: Ошибка очистки неудачных попыток для IP " + ipAddress, e);
            return false;
        }
    }

    /**
     * Проверка нахождения игрока в лобби авторизации
     *
     * @param player игрок
     * @return true если игрок в лобби авторизации
     */
    public boolean isPlayerInAuthLobby(Player player) {
        if (player == null || !player.isOnline() || !plugin.isFullyInitialized()) {
            return false;
        }

        return plugin.getLobbyManager().isInAuthWorld(player);
    }

    /**
     * Получение статистики плагина
     *
     * @return объект со статистикой
     */
    public AuthStatistics getStatistics() {
        if (!plugin.isFullyInitialized()) {
            return new AuthStatistics(0, 0, 0, 0);
        }

        int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
        int authenticatedPlayers = 0;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isPlayerAuthenticated(player)) {
                authenticatedPlayers++;
            }
        }

        int cachedPlayers = plugin.getAuthManager().getCachedPlayersCount();
        int activeSessions = plugin.getSessionManager().getActiveSessionsCount();

        return new AuthStatistics(onlinePlayers, authenticatedPlayers, cachedPlayers, activeSessions);
    }

    /**
     * Проверка готовности плагина
     *
     * @return true если плагин полностью инициализирован
     */
    public boolean isReady() {
        return plugin.isFullyInitialized();
    }

    /**
     * Получение версии плагина
     *
     * @return версия плагина
     */
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * Вызов перезагрузки конфигурации (асинхронно)
     *
     * @return CompletableFuture с результатом
     */
    public CompletableFuture<Boolean> reloadConfiguration() {
        if (!plugin.isFullyInitialized()) {
            return CompletableFuture.completedFuture(false);
        }

        return plugin.reloadPlugin();
    }

    // Вложенные классы для возврата данных

    /**
     * Информация о блокировке IP адреса
     */
    public static class IpBlockInfo {
        private final boolean blocked;
        private final int attempts;
        private final long remainingTimeMs;

        public IpBlockInfo(boolean blocked, int attempts, long remainingTimeMs) {
            this.blocked = blocked;
            this.attempts = attempts;
            this.remainingTimeMs = remainingTimeMs;
        }

        public boolean isBlocked() { return blocked; }
        public int getAttempts() { return attempts; }
        public long getRemainingTimeMs() { return remainingTimeMs; }
        public long getRemainingTimeSeconds() { return remainingTimeMs / 1000; }
        public long getRemainingTimeMinutes() { return remainingTimeMs / 1000 / 60; }
    }

    /**
     * Статистика работы плагина
     */
    public static class AuthStatistics {
        private final int onlinePlayers;
        private final int authenticatedPlayers;
        private final int cachedPlayers;
        private final int activeSessions;

        public AuthStatistics(int onlinePlayers, int authenticatedPlayers, int cachedPlayers, int activeSessions) {
            this.onlinePlayers = onlinePlayers;
            this.authenticatedPlayers = authenticatedPlayers;
            this.cachedPlayers = cachedPlayers;
            this.activeSessions = activeSessions;
        }

        public int getOnlinePlayers() { return onlinePlayers; }
        public int getAuthenticatedPlayers() { return authenticatedPlayers; }
        public int getUnauthenticatedPlayers() { return onlinePlayers - authenticatedPlayers; }
        public int getCachedPlayers() { return cachedPlayers; }
        public int getActiveSessions() { return activeSessions; }
        public double getAuthenticationRate() {
            return onlinePlayers > 0 ? (double) authenticatedPlayers / onlinePlayers * 100 : 0;
        }
    }

    // События для других плагинов

    /**
     * Событие принудительного выхода игрока
     */
    public static class PlayerForceLogoutEvent extends Event {
        private static final HandlerList handlers = new HandlerList();
        private final String playerName;
        private final String source;

        public PlayerForceLogoutEvent(String playerName, String source) {
            this.playerName = playerName;
            this.source = source;
        }

        public String getPlayerName() { return playerName; }
        public String getSource() { return source; }

        @Override
        public HandlerList getHandlers() { return handlers; }
        public static HandlerList getHandlerList() { return handlers; }
    }

    /**
     * Событие успешной авторизации игрока
     */
    public static class PlayerAuthenticateEvent extends Event {
        private static final HandlerList handlers = new HandlerList();
        private final Player player;
        private final String ipAddress;

        public PlayerAuthenticateEvent(Player player, String ipAddress) {
            this.player = player;
            this.ipAddress = ipAddress;
        }

        public Player getPlayer() { return player; }
        public String getIpAddress() { return ipAddress; }

        @Override
        public HandlerList getHandlers() { return handlers; }
        public static HandlerList getHandlerList() { return handlers; }
    }

    /**
     * Событие регистрации нового игрока
     */
    public static class PlayerRegisterEvent extends Event {
        private static final HandlerList handlers = new HandlerList();
        private final Player player;
        private final String ipAddress;

        public PlayerRegisterEvent(Player player, String ipAddress) {
            this.player = player;
            this.ipAddress = ipAddress;
        }

        public Player getPlayer() { return player; }
        public String getIpAddress() { return ipAddress; }

        @Override
        public HandlerList getHandlers() { return handlers; }
        public static HandlerList getHandlerList() { return handlers; }
    }

    /**
     * Событие деактивации сессии
     */
    public static class SessionInvalidatedEvent extends Event {
        private static final HandlerList handlers = new HandlerList();
        private final String playerName;
        private final String reason;

        public SessionInvalidatedEvent(String playerName, String reason) {
            this.playerName = playerName;
            this.reason = reason;
        }

        public String getPlayerName() { return playerName; }
        public String getReason() { return reason; }

        @Override
        public HandlerList getHandlers() { return handlers; }
        public static HandlerList getHandlerList() { return handlers; }
    }
}