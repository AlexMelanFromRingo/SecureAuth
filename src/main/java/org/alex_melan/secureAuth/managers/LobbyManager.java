package org.alex_melan.secureAuth.managers;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

public class LobbyManager {

    private final SecureAuthPlugin plugin;
    private World authWorld;
    private Location authLocation;

    public LobbyManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
        initializeAuthWorld();
    }

    /**
     * Инициализация мира для авторизации
     */
    private void initializeAuthWorld() {
        String worldName = plugin.getConfig().getString("lobby.world", "auth_lobby");

        // Пытаемся загрузить существующий мир
        authWorld = Bukkit.getWorld(worldName);

        if (authWorld == null) {
            plugin.getLogger().info("Создание мира авторизации: " + worldName);

            // Создаем новый мир
            WorldCreator creator = new WorldCreator(worldName);
            creator.type(WorldType.FLAT); // Плоский мир
            creator.generateStructures(false); // Без структур
            creator.generator("minecraft:void"); // Пустой мир (если поддерживается)

            authWorld = creator.createWorld();

            if (authWorld != null) {
                // Настройки мира
                authWorld.setDifficulty(Difficulty.PEACEFUL);
                authWorld.setSpawnFlags(false, false); // Нет мобов
                authWorld.setPVP(false);
                authWorld.setStorm(false);
                authWorld.setThundering(false);
                authWorld.setWeatherDuration(Integer.MAX_VALUE);
                authWorld.setAutoSave(false); // Не нужно сохранять лобби

                // Создаем платформу для авторизации
                createAuthPlatform();

                plugin.getLogger().info("Мир авторизации создан успешно!");
            } else {
                plugin.getLogger().warning("Не удалось создать мир авторизации! Используется основной мир.");
                authWorld = Bukkit.getWorlds().get(0);
            }
        }

        // Устанавливаем точку авторизации
        double x = plugin.getConfig().getDouble("lobby.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.y", 100);
        double z = plugin.getConfig().getDouble("lobby.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0);
        float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0);

        authLocation = new Location(authWorld, x, y, z, yaw, pitch);
    }

    /**
     * Создание платформы для авторизации
     */
    private void createAuthPlatform() {
        if (authWorld == null) return;

        int centerX = (int) plugin.getConfig().getDouble("lobby.x", 0);
        int centerY = (int) plugin.getConfig().getDouble("lobby.y", 100);
        int centerZ = (int) plugin.getConfig().getDouble("lobby.z", 0);

        // Создаем платформу 7x7 из стекла
        for (int x = centerX - 3; x <= centerX + 3; x++) {
            for (int z = centerZ - 3; z <= centerZ + 3; z++) {
                Block block = authWorld.getBlockAt(x, centerY - 1, z);

                // Центр - другой материал
                if (x == centerX && z == centerZ) {
                    block.setType(Material.EMERALD_BLOCK);
                } else if (Math.abs(x - centerX) <= 1 && Math.abs(z - centerZ) <= 1) {
                    // Внутренняя часть
                    block.setType(Material.QUARTZ_BLOCK);
                } else {
                    // Края
                    block.setType(Material.GLASS);
                }
            }
        }

        // Барьеры по краям чтобы не упасть
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                for (int y = centerY; y <= centerY + 3; y++) {
                    // Только по краям
                    if (Math.abs(x - centerX) == 4 || Math.abs(z - centerZ) == 4) {
                        Block block = authWorld.getBlockAt(x, y, z);
                        block.setType(Material.BARRIER);
                    }
                }
            }
        }

        // Информационные таблички
        Block signBlock = authWorld.getBlockAt(centerX, centerY + 1, centerZ - 2);
        signBlock.setType(Material.OAK_SIGN);

        if (signBlock.getState() instanceof org.bukkit.block.Sign) {
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) signBlock.getState();
            sign.setLine(0, "§c§l=== AUTH ===");
            sign.setLine(1, "§7/register пароль");
            sign.setLine(2, "§7/login пароль");
            sign.setLine(3, "§c§l============");
            sign.update();
        }

        plugin.getLogger().info("Платформа авторизации создана в мире " + authWorld.getName());
    }

    public void sendToAuthLobby(Player player) {
        // Сохраняем данные игрока перед телепортацией
        plugin.getAuthManager().savePlayerData(player);

        // Телепортируем в лобби
        player.teleport(authLocation);

        // Настраиваем игрока для лобби
        setupLobbyPlayer(player);

        // Отправляем сообщения
        sendAuthMessages(player);
    }

    private void setupLobbyPlayer(Player player) {
        // Очищаем инвентарь (он уже сохранен)
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

        // Отключаем полет
        player.setAllowFlight(false);
        player.setFlying(false);

        // Сбрасываем опыт для лобби
        player.setLevel(0);
        player.setExp(0);

        // Очищаем инвентарь от стрелок, если есть
        player.getInventory().clear();
    }

    public void returnFromAuthLobby(Player player) {
        plugin.getAuthManager().loadPlayerData(player.getName())
                .thenAccept(data -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (data != null) {
                            // Восстанавливаем все данные игрока
                            data.applyToPlayer(player, getDefaultWorld());
                            player.sendMessage(getMessage("login-success"));
                        } else {
                            // Первый вход - отправляем на спавн основного мира
                            player.teleport(getDefaultSpawnLocation());
                            setupNewPlayer(player);
                            player.sendMessage("§eПервый вход! Добро пожаловать на сервер!");
                        }
                    });
                });
    }

    private void setupNewPlayer(Player player) {
        // Настройки для нового игрока
        player.setGameMode(GameMode.SURVIVAL); // Или из конфига
        player.getInventory().clear();

        // Можно дать стартовые предметы
        if (plugin.getConfig().getBoolean("lobby.give-starter-items", false)) {
            player.getInventory().addItem(
                    new org.bukkit.inventory.ItemStack(Material.BREAD, 5),
                    new org.bukkit.inventory.ItemStack(Material.WOODEN_SWORD),
                    new org.bukkit.inventory.ItemStack(Material.WOODEN_PICKAXE)
            );
        }
    }

    private void sendAuthMessages(Player player) {
        player.sendMessage("");
        player.sendMessage(getMessage("auth-lobby-title"));

        // Асинхронно проверяем регистрацию
        plugin.getDatabaseManager().isPlayerRegistered(player.getName())
                .thenAccept(registered -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (registered) {
                            player.sendMessage("§7Добро пожаловать обратно, §e" + player.getName() + "§7!");
                            player.sendMessage("§7Введите: §a/login <пароль>");
                        } else {
                            player.sendMessage("§7Добро пожаловать на сервер, §e" + player.getName() + "§7!");
                            player.sendMessage("§7Создайте аккаунт: §a/register <пароль> <повтор>");
                            player.sendMessage("");
                            player.sendMessage("§7§lТребования к паролю:");
                            player.sendMessage("§7• От 8 до 32 символов");
                            player.sendMessage("§7• Заглавные и строчные буквы");
                            player.sendMessage("§7• Минимум одна цифра");
                        }
                        player.sendMessage(getMessage("auth-lobby-footer"));
                        player.sendMessage("");
                    });
                });
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
        return Bukkit.getWorlds().get(0);
    }

    private String getMessage(String key) {
        String prefix = plugin.getConfig().getString("messages.prefix", "§8[§6SecureAuth§8]§r ");
        String message = plugin.getConfig().getString("messages." + key, "§cСообщение не найдено: " + key);
        return prefix + message;
    }

    public World getAuthWorld() {
        return authWorld;
    }

    public Location getAuthLocation() {
        return authLocation.clone();
    }
}