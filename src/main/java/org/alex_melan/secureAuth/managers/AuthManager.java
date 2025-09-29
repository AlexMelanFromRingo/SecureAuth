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
import java.util.logging.Level;

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
                plugin.getLogger().warning("Попытка регистрации с невалидным паролем для игрока " + username);
                return false;
            }

            // Дополнительная проверка длины никнейма
            if (username.length() < 3 || username.length() > 16) {
                plugin.getLogger().warning("Некорректная длина никнейма при регистрации: " + username);
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
                            plugin.getLogger().info("Попытка повторной регистрации игрока " + username);
                            return CompletableFuture.completedFuture(false);
                        }

                        return databaseManager.registerPlayer(username, password, crackedUuid);
                    });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Ошибка в процессе регистрации игрока " + username, ex);
            return false;
        });
    }

    public CompletableFuture<Boolean> authenticatePlayer(String username, String password, String ipAddress) {
        return databaseManager.authenticatePlayer(username, password, ipAddress)
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка аутентификации игрока " + username, ex);
                    return false;
                });
    }

    public CompletableFuture<PlayerData> loadPlayerData(String username) {
        return databaseManager.getPlayerData(username)
                .thenApply(data -> {
                    if (data != null) {
                        // Валидируем загруженные данные
                        if (validatePlayerData(data)) {
                            playerDataCache.put(username.toLowerCase(), data);
                            plugin.getLogger().info("Данные игрока " + username + " загружены и кешированы");
                        } else {
                            plugin.getLogger().warning("Некорректные данные игрока " + username + ", создаются значения по умолчанию");
                            data = createDefaultPlayerData(username);
                            playerDataCache.put(username.toLowerCase(), data);
                        }
                    } else {
                        plugin.getLogger().info("Данные игрока " + username + " не найдены, создаются значения по умолчанию");
                        data = createDefaultPlayerData(username);
                        playerDataCache.put(username.toLowerCase(), data);
                    }
                    return data;
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка загрузки данных игрока " + username, ex);
                    // Возвращаем дефолтные данные в случае ошибки
                    PlayerData defaultData = createDefaultPlayerData(username);
                    playerDataCache.put(username.toLowerCase(), defaultData);
                    return defaultData;
                });
    }

    private boolean validatePlayerData(PlayerData data) {
        if (data == null || !data.isValid()) {
            return false;
        }

        // Проверяем разумность координат
        if (data.getY() < -100 || data.getY() > 500) {
            plugin.getLogger().warning("Некорректная Y координата для игрока " + data.getUsername() + ": " + data.getY());
            data.setY(64); // Безопасная высота
        }

        // Проверяем валидность мира
        if (data.getWorldName() == null || data.getWorldName().isEmpty()) {
            plugin.getLogger().warning("Некорректное имя мира для игрока " + data.getUsername());
            data.setWorldName("world"); // Дефолтный мир
        }

        // Проверяем здоровье
        if (data.getHealth() <= 0 || data.getHealth() > 20) {
            plugin.getLogger().warning("Некорректное значение здоровья для игрока " + data.getUsername() + ": " + data.getHealth());
            data.setHealth(20);
        }

        // Проверяем голод
        if (data.getFood() < 0 || data.getFood() > 20) {
            plugin.getLogger().warning("Некорректное значение голода для игрока " + data.getUsername() + ": " + data.getFood());
            data.setFood(20);
        }

        return true;
    }

    private PlayerData createDefaultPlayerData(String username) {
        PlayerData data = new PlayerData(username);

        // Устанавливаем безопасные значения по умолчанию
        data.setWorldName(getDefaultWorldName());
        data.setX(0);
        data.setY(64);
        data.setZ(0);
        data.setYaw(0);
        data.setPitch(0);
        data.setHealth(20);
        data.setFood(20);
        data.setSaturation(5.0f);
        data.setGameMode("SURVIVAL");
        data.setExperience(0);
        data.setLevel(0);

        return data;
    }

    private String getDefaultWorldName() {
        // Получаем имя основного мира (не лобби)
        String lobbyWorld = plugin.getConfigManager().getLobbyWorld();

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (!world.getName().equals(lobbyWorld)) {
                return world.getName();
            }
        }

        // Если не найден другой мир, возвращаем первый доступный
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
    }

    public void savePlayerData(Player player) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data != null) {
            try {
                data.saveFromPlayer(player);

                // Асинхронное сохранение в БД
                databaseManager.savePlayerData(data)
                        .thenRun(() -> {
                            plugin.getLogger().fine("Данные игрока " + username + " сохранены в БД");
                        })
                        .exceptionally(ex -> {
                            plugin.getLogger().log(Level.SEVERE, "Ошибка сохранения данных игрока " + username, ex);
                            return null;
                        });

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка сохранения данных игрока " + username, e);
            }
        } else {
            plugin.getLogger().warning("Попытка сохранить данные для игрока " + username + ", но данные не найдены в кеше");
        }
    }

    public void saveAllPlayerData() {
        plugin.getLogger().info("Сохранение данных всех онлайн игроков...");

        int savedCount = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getSessionManager().isAuthenticated(player.getName())) {
                savePlayerData(player);
                savedCount++;
            }
        }

        plugin.getLogger().info("Данные " + savedCount + " игроков сохранены");
    }

    public PlayerData getCachedPlayerData(String username) {
        return playerDataCache.get(username.toLowerCase());
    }

    public void removeCachedData(String username) {
        PlayerData removed = playerDataCache.remove(username.toLowerCase());
        if (removed != null) {
            plugin.getLogger().fine("Данные игрока " + username + " удалены из кеша");
        }
    }

    // Обработка смены игрового режима
    public void handleGameModeChange(Player player, org.bukkit.GameMode newMode) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data != null) {
            data.setGameMode(newMode.name());
            plugin.getLogger().fine("Игровой режим игрока " + username + " изменен на " + newMode.name());
        }
    }

    // Обработка телепортации
    public void handlePlayerTeleport(Player player, org.bukkit.Location to) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data != null && to != null && to.getWorld() != null) {
            // Обновляем только если это не лобби авторизации
            if (!to.getWorld().getName().equals(plugin.getConfigManager().getLobbyWorld())) {
                data.setWorldName(to.getWorld().getName());
                data.setX(to.getX());
                data.setY(to.getY());
                data.setZ(to.getZ());
                data.setYaw(to.getYaw());
                data.setPitch(to.getPitch());

                plugin.getLogger().fine("Позиция игрока " + username + " обновлена: " + to.getWorld().getName() +
                        " (" + Math.round(to.getX()) + ", " + Math.round(to.getY()) + ", " + Math.round(to.getZ()) + ")");
            }
        }
    }

    // Привязка Premium UUID
    public CompletableFuture<Boolean> linkPremiumUuid(String username, UUID premiumUuid) {
        if (!plugin.getConfigManager().isUuidLinkingEnabled()) {
            return CompletableFuture.completedFuture(false);
        }

        return databaseManager.linkPremiumAccount(username, premiumUuid)
                .thenApply(success -> {
                    if (success) {
                        // Обновляем кеш
                        PlayerData data = playerDataCache.get(username.toLowerCase());
                        if (data != null) {
                            data.setPremiumUuid(premiumUuid);
                        }

                        plugin.getLogger().info("Premium UUID " + premiumUuid + " привязан к аккаунту " + username);
                    }
                    return success;
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.SEVERE, "Ошибка привязки Premium UUID для игрока " + username, ex);
                    return false;
                });
    }

    // Получение статистики
    public int getCachedPlayersCount() {
        return playerDataCache.size();
    }

    public void clearCache() {
        int size = playerDataCache.size();
        playerDataCache.clear();
        plugin.getLogger().info("Кеш данных игроков очищен (" + size + " записей)");
    }

    // Предварительная загрузка данных
    public CompletableFuture<Void> preloadPlayerData(String username) {
        return loadPlayerData(username).thenAccept(data -> {
            plugin.getLogger().fine("Данные игрока " + username + " предварительно загружены");
        });
    }

    // Валидация целостности данных
    public CompletableFuture<Boolean> validatePlayerDataIntegrity(String username) {
        return databaseManager.getPlayerData(username)
                .thenApply(data -> {
                    if (data == null) {
                        return false;
                    }

                    return validatePlayerData(data);
                })
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "Ошибка валидации данных игрока " + username, ex);
                    return false;
                });
    }

    /**
     * Принудительное обновление кеша с текущими данными игрока
     * Используется перед сохранением при выходе или разлогинивании
     */
    public void forceCacheUpdate(Player player) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data == null) {
            // Если данных нет в кеше, создаем новые
            data = new PlayerData(username);
            playerDataCache.put(username, data);
            plugin.getLogger().warning("Создан новый кеш для игрока " + username + " при принудительном обновлении");
        }

        // Сохраняем все текущие данные игрока в кеш
        try {
            // ДОБАВЛЕНО: Логируем ДО сохранения
            plugin.getLogger().info("Принудительное обновление кеша для " + username +
                    " - текущий режим: " + player.getGameMode().name());

            data.saveFromPlayer(player);

            // ДОБАВЛЕНО: Логируем ПОСЛЕ сохранения
            plugin.getLogger().info("Кеш игрока " + username + " обновлен - сохранен режим: " + data.getGameMode());

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка принудительного обновления кеша для " + username, e);
        }
    }

    /**
     * Обновление позиции игрока в кеше
     * Вызывается при движении для постоянной актуальности данных
     */
    public void updatePlayerPosition(Player player) {
        String username = player.getName().toLowerCase();
        PlayerData data = playerDataCache.get(username);

        if (data != null) {
            org.bukkit.Location loc = player.getLocation();

            // Обновляем только если это не лобби авторизации
            String lobbyWorld = plugin.getConfigManager().getLobbyWorld();
            if (!loc.getWorld().getName().equals(lobbyWorld)) {
                data.setWorldName(loc.getWorld().getName());
                data.setX(loc.getX());
                data.setY(loc.getY());
                data.setZ(loc.getZ());
                data.setYaw(loc.getYaw());
                data.setPitch(loc.getPitch());

                data.setUpdatedAt(System.currentTimeMillis());
            }
        }
    }
}