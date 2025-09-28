package org.alex_melan.secureAuth.managers;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class LobbyManager {

    private final SecureAuthPlugin plugin;
    private World authWorld;
    private Location authLocation;
    private boolean customLobby = false;

    public LobbyManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
        initializeAuthWorld();
    }

    /**
     * Инициализация мира для авторизации
     */
    private void initializeAuthWorld() {
        String worldName = plugin.getConfigManager().getLobbyWorld();
        plugin.getLogger().info("Инициализация мира авторизации: " + worldName);

        // Пытаемся загрузить существующий мир
        authWorld = Bukkit.getWorld(worldName);

        if (authWorld == null) {
            plugin.getLogger().info("Мир " + worldName + " не найден, проверяем существование...");

            // Проверяем существует ли папка мира на диске
            File worldFolder = new File(Bukkit.getWorldContainer(), worldName);

            if (worldFolder.exists() && worldFolder.isDirectory()) {
                plugin.getLogger().info("Найдена папка мира " + worldName + ", загружаем...");
                try {
                    WorldCreator creator = new WorldCreator(worldName);
                    authWorld = creator.createWorld();
                    customLobby = true;
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка загрузки мира " + worldName + ": " + e.getMessage());
                    authWorld = null;
                }
            }

            // Если мир все еще не загружен, создаем новый
            if (authWorld == null) {
                plugin.getLogger().info("Создание нового мира авторизации: " + worldName);
                authWorld = createAuthWorld(worldName);
                customLobby = false;
            }
        } else {
            plugin.getLogger().info("Мир " + worldName + " найден и загружен");
            customLobby = !worldName.equals("auth_lobby"); // Считаем кастомным если не наш дефолтный
        }

        // Если так и не удалось создать мир, используем основной
        if (authWorld == null) {
            plugin.getLogger().warning("Не удалось создать/загрузить мир авторизации! Используется основной мир.");
            authWorld = Bukkit.getWorlds().get(0);
            customLobby = true; // Считаем что это кастомный лобби
        }

        setupAuthLocation();
        configureAuthWorld();
    }

    private World createAuthWorld(String worldName) {
        try {
            WorldCreator creator = new WorldCreator(worldName);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);

            // Создаем плоский мир с только одним слоем воздуха
            creator.generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}");

            World world = creator.createWorld();

            if (world != null) {
                plugin.getLogger().info("Мир авторизации " + worldName + " создан успешно!");
                return world;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка создания мира " + worldName + ": " + e.getMessage());
        }

        return null;
    }

    private void setupAuthLocation() {
        double x = plugin.getConfigManager().getLobbyX();
        double y = plugin.getConfigManager().getLobbyY();
        double z = plugin.getConfigManager().getLobbyZ();
        float yaw = plugin.getConfigManager().getLobbyYaw();
        float pitch = plugin.getConfigManager().getLobbyPitch();

        authLocation = new Location(authWorld, x, y, z, yaw, pitch);

        plugin.getLogger().info("Точка авторизации установлена: " + authWorld.getName() +
                " (" + Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z) + ")");
    }

    private void configureAuthWorld() {
        if (authWorld == null) return;

        // Настройки мира только если это наш созданный мир
        if (!customLobby) {
            authWorld.setDifficulty(Difficulty.PEACEFUL);
            authWorld.setSpawnFlags(false, false);
            authWorld.setPVP(false);
            authWorld.setStorm(false);
            authWorld.setThundering(false);
            authWorld.setWeatherDuration(Integer.MAX_VALUE);
            authWorld.setAutoSave(false);
            authWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            authWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            authWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            authWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            authWorld.setTime(6000); // Полдень

            // Создаем платформу с задержкой, чтобы сервер полностью загрузился
            if (plugin.getConfig().getBoolean("lobby.auto-create-platform", true)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    createAuthPlatform();
                }, 40L); // 2 секунды задержки
            }
        }
    }

    /**
     * Убеждаемся что платформа существует
     */
    private void ensurePlatformExists() {
        if (authWorld == null || customLobby) return;

        int centerX = (int) plugin.getConfigManager().getLobbyX();
        int centerY = (int) plugin.getConfigManager().getLobbyY();
        int centerZ = (int) plugin.getConfigManager().getLobbyZ();
        int platformOffset = plugin.getConfig().getInt("lobby.platform-offset", -1);
        int platformY = centerY + platformOffset;

        // Проверяем центральный блок
        Block centerBlock = authWorld.getBlockAt(centerX, platformY, centerZ);
        if (centerBlock.getType() != Material.EMERALD_BLOCK) {
            plugin.getLogger().info("Платформа не найдена, создаем...");
            createAuthPlatform();
        }
    }

    /**
     * Создание платформы для авторизации
     */
    private void createAuthPlatform() {
        if (authWorld == null || customLobby) return;

        // ИСПРАВЛЕНО: Выполняем в основном потоке, а не асинхронно
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // ИСПРАВЛЕНО: Убрана некорректная проверка isLoaded()
                if (authWorld == null) {
                    plugin.getLogger().warning("Мир авторизации не инициализирован, отменяем создание платформы");
                    return;
                }

                int centerX = (int) plugin.getConfigManager().getLobbyX();
                int centerY = (int) plugin.getConfigManager().getLobbyY();
                int centerZ = (int) plugin.getConfigManager().getLobbyZ();
                int platformOffset = plugin.getConfig().getInt("lobby.platform-offset", -1);

                int platformY = centerY + platformOffset;

                plugin.getLogger().info("Создание платформы авторизации в " + authWorld.getName() +
                        " на координатах " + centerX + ", " + platformY + ", " + centerZ);

                // Убеждаемся что чанк загружен
                authWorld.loadChunk(centerX >> 4, centerZ >> 4);

                // ИСПРАВЛЕНО: Создаем более надежную платформу 11x11
                for (int x = centerX - 5; x <= centerX + 5; x++) {
                    for (int z = centerZ - 5; z <= centerZ + 5; z++) {
                        Block block = authWorld.getBlockAt(x, platformY, z);

                        // Центр - изумрудный блок
                        if (x == centerX && z == centerZ) {
                            block.setType(Material.EMERALD_BLOCK);
                        }
                        // Внутренний квадрат 5x5 - кварц
                        else if (Math.abs(x - centerX) <= 2 && Math.abs(z - centerZ) <= 2) {
                            block.setType(Material.QUARTZ_BLOCK);
                        }
                        // Края - стекло
                        else {
                            block.setType(Material.GLASS);
                        }

                        // Убираем блоки сверху для свободного пространства
                        for (int y = platformY + 1; y <= platformY + 10; y++) {
                            Block airBlock = authWorld.getBlockAt(x, y, z);
                            if (airBlock.getType() != Material.AIR) {
                                airBlock.setType(Material.AIR);
                            }
                        }
                    }
                }

                // Создаем стены из барьеров только по самым краям (для безопасности)
                for (int x = centerX - 6; x <= centerX + 6; x++) {
                    for (int z = centerZ - 6; z <= centerZ + 6; z++) {
                        for (int y = platformY + 1; y <= platformY + 3; y++) {
                            // Только по внешним краям
                            if (Math.abs(x - centerX) == 6 || Math.abs(z - centerZ) == 6) {
                                Block block = authWorld.getBlockAt(x, y, z);
                                block.setType(Material.BARRIER);
                            }
                        }
                    }
                }

                // Информационная табличка
                Block signBlock = authWorld.getBlockAt(centerX, platformY + 1, centerZ - 3);
                signBlock.setType(Material.OAK_SIGN);

                // Обновляем табличку в том же потоке
                if (signBlock.getState() instanceof Sign) {
                    Sign sign = (Sign) signBlock.getState();
                    sign.setLine(0, "§c§l=== AUTH ===");
                    sign.setLine(1, "§7/register пароль");
                    sign.setLine(2, "§7/login пароль");
                    sign.setLine(3, "§c§l============");
                    sign.update();
                }

                plugin.getLogger().info("Платформа авторизации создана успешно в мире " + authWorld.getName());

            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка создания платформы авторизации: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void sendToAuthLobby(Player player) {
        if (authWorld == null || authLocation == null) {
            plugin.getLogger().severe("Мир авторизации не инициализирован! Игрок " + player.getName() + " не может быть отправлен в лобби.");
            return;
        }

        // Сохраняем данные игрока перед телепортацией (если авторизован)
        if (plugin.getSessionManager().isAuthenticated(player.getName())) {
            plugin.getAuthManager().savePlayerData(player);
        }

        // Убеждаемся что платформа существует перед телепортацией
        ensurePlatformExists();

        // Телепортируем в лобби
        player.teleport(authLocation);

        // Настраиваем игрока для лобби
        setupLobbyPlayer(player);

        // Отправляем сообщения о необходимости авторизации
        sendAuthMessages(player);

        plugin.getLogger().info("Игрок " + player.getName() + " отправлен в лобби авторизации");
    }

    private void setupLobbyPlayer(Player player) {
        try {
            // Очищаем инвентарь (он уже сохранен или будет восстановлен)
            player.getInventory().clear();
            player.getEnderChest().clear();

            // Убираем все эффекты
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }

            // Полное восстановление
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
            player.setExhaustion(0);

            // Режим приключений (не может ломать/ставить блоки)
            player.setGameMode(GameMode.ADVENTURE);

            // ИСПРАВЛЕНО: Разрешаем полет в лобби авторизации
            player.setAllowFlight(true);
            player.setFlying(false); // Не летим сразу, но можем

            // Сбрасываем опыт для лобби
            player.setLevel(0);
            player.setExp(0);

            // Очищаем весь инвентарь
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка настройки игрока в лобби: " + e.getMessage());
        }
    }

    public void returnFromAuthLobby(Player player) {
        plugin.getAuthManager().loadPlayerData(player.getName())
                .thenAccept(data -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            if (!player.isOnline()) {
                                return;
                            }

                            if (data != null) {
                                // Восстанавливаем все данные игрока
                                World defaultWorld = getDefaultWorld();
                                data.applyToPlayer(player, defaultWorld);

                                // Если это кастомный лобби, оставляем игрока там
                                if (customLobby) {
                                    plugin.getLogger().info("Игрок " + player.getName() + " остается в кастомном лобби");
                                    setupPlayerForCustomLobby(player);
                                } else {
                                    plugin.getLogger().info("Игрок " + player.getName() + " возвращен в игровой мир");
                                }

                            } else {
                                // Первый вход - отправляем на спавн
                                handleFirstTimePlayer(player);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().severe("Ошибка возврата игрока из лобби авторизации: " + e.getMessage());
                            // В случае ошибки отправляем на спавн основного мира
                            player.teleport(getDefaultSpawnLocation());
                            setupNewPlayer(player);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка загрузки данных при возврате из лобби для игрока " + player.getName() + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.teleport(getDefaultSpawnLocation());
                            setupNewPlayer(player);
                        }
                    });
                    return null;
                });
    }

    private void setupPlayerForCustomLobby(Player player) {
        // Если лобби кастомный, даем игроку возможность нормально играть
        player.setGameMode(GameMode.SURVIVAL); // Или из конфига
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    private void handleFirstTimePlayer(Player player) {
        if (customLobby) {
            // В кастомном лобби оставляем игрока там где он есть
            setupNewPlayer(player);
            player.sendMessage("§eПервый вход! Добро пожаловать на сервер!");
        } else {
            // В нашем лобби отправляем в основной мир
            player.teleport(getDefaultSpawnLocation());
            setupNewPlayer(player);
            player.sendMessage("§eПервый вход! Добро пожаловать на сервер!");
        }
    }

    private void setupNewPlayer(Player player) {
        // Настройки для нового игрока
        player.setGameMode(GameMode.SURVIVAL); // Или из конфига
        player.getInventory().clear();

        // Стартовые предметы если включено
        if (plugin.getConfigManager().isGiveStarterItems()) {
            giveStarterItems(player);
        }
    }

    private void giveStarterItems(Player player) {
        try {
            String[] items = plugin.getConfig().getStringList("misc.starter-items").toArray(new String[0]);

            for (String itemString : items) {
                String[] parts = itemString.split(":");
                if (parts.length >= 2) {
                    Material material = Material.valueOf(parts[0].toUpperCase());
                    int amount = Integer.parseInt(parts[1]);

                    player.getInventory().addItem(new org.bukkit.inventory.ItemStack(material, amount));
                }
            }

            plugin.getLogger().info("Стартовые предметы выданы игроку " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка выдачи стартовых предметов игроку " + player.getName() + ": " + e.getMessage());
        }
    }

    private void sendAuthMessages(Player player) {
        // Небольшая задержка для лучшего восприятия
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            player.sendMessage("");
            player.sendMessage(plugin.getConfigManager().getMessage("auth-lobby-title"));

            // Асинхронно проверяем регистрацию
            plugin.getDatabaseManager().isPlayerRegistered(player.getName())
                    .thenAccept(registered -> {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;

                            if (registered) {
                                String[] messages = plugin.getConfigManager().getMessageList("auth-lobby-welcome-back");
                                for (String message : messages) {
                                    player.sendMessage(message.replace("{player}", player.getName()));
                                }
                            } else {
                                String[] messages = plugin.getConfigManager().getMessageList("auth-lobby-welcome-new");
                                for (String message : messages) {
                                    player.sendMessage(message.replace("{player}", player.getName()));
                                }

                                // Показываем требования к паролю
                                if (plugin.getConfigManager().isPasswordComplexityEnforced()) {
                                    String[] requirements = plugin.getConfigManager().getMessageList("auth-lobby-password-requirements");
                                    for (String req : requirements) {
                                        player.sendMessage(req
                                                .replace("{min}", "8")
                                                .replace("{max}", "32"));
                                    }
                                }
                            }

                            player.sendMessage(plugin.getConfigManager().getMessage("auth-lobby-footer"));
                            player.sendMessage("");
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Ошибка проверки регистрации для отображения сообщений: " + ex.getMessage());
                        return null;
                    });
        }, 20L); // 1 секунда задержки
    }

    private Location getDefaultSpawnLocation() {
        World world = getDefaultWorld();
        return world.getSpawnLocation();
    }

    private World getDefaultWorld() {
        // Получаем основной мир (не лобби)
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equals(authWorld.getName())) {
                return world;
            }
        }

        // Если не найден другой мир, возвращаем первый доступный
        return Bukkit.getWorlds().isEmpty() ? authWorld : Bukkit.getWorlds().get(0);
    }

    // Геттеры
    public World getAuthWorld() {
        return authWorld;
    }

    public Location getAuthLocation() {
        return authLocation != null ? authLocation.clone() : null;
    }

    public boolean isCustomLobby() {
        return customLobby;
    }

    public boolean isInAuthWorld(Player player) {
        return player.getWorld().equals(authWorld);
    }

    // Обновление координат лобби
    public void updateAuthLocation(Location newLocation) {
        this.authLocation = newLocation.clone();
        plugin.getConfigManager().setValue("lobby.world", newLocation.getWorld().getName());
        plugin.getConfigManager().setValue("lobby.x", newLocation.getX());
        plugin.getConfigManager().setValue("lobby.y", newLocation.getY());
        plugin.getConfigManager().setValue("lobby.z", newLocation.getZ());
        plugin.getConfigManager().setValue("lobby.yaw", newLocation.getYaw());
        plugin.getConfigManager().setValue("lobby.pitch", newLocation.getPitch());

        plugin.getLogger().info("Координаты лобби авторизации обновлены: " +
                newLocation.getWorld().getName() + " " +
                Math.round(newLocation.getX()) + " " +
                Math.round(newLocation.getY()) + " " +
                Math.round(newLocation.getZ()));
    }
}