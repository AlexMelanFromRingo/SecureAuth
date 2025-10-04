package org.alex_melan.secureAuth.models;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerData {

    private static final Logger LOGGER = Logger.getLogger(PlayerData.class.getName());
    private static final Gson GSON = new Gson();

    private String username;
    private String passwordHash;
    private String salt;
    private UUID premiumUuid;
    private UUID crackedUuid;
    private String lastIp;
    private long lastLogin;
    private long registrationDate;

    // Данные местоположения
    private String worldName;
    private double x, y, z;
    private float yaw, pitch;

    // Данные игрока
    private String inventoryData;
    private String enderchestData;
    private int experience;
    private int level;
    private double health;
    private int food;
    private float saturation;
    private String gameMode;

    // НОВОЕ: Расширенные данные
    private String advancementsData;
    private String statisticsData;
    private String recipesData;
    private String potionEffectsData;

    // Системные поля
    private long createdAt;
    private long updatedAt;

    public PlayerData(String username) {
        this.username = username.toLowerCase();
        this.gameMode = "SURVIVAL";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public static PlayerData fromResultSet(ResultSet rs) throws SQLException {
        PlayerData data = new PlayerData(rs.getString("username"));

        data.passwordHash = rs.getString("password_hash");
        data.salt = rs.getString("salt");

        String premiumUuidStr = rs.getString("premium_uuid");
        if (premiumUuidStr != null && !premiumUuidStr.isEmpty()) {
            try {
                data.premiumUuid = UUID.fromString(premiumUuidStr);
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Некорректный premium UUID для пользователя " + data.username + ": " + premiumUuidStr);
            }
        }

        String crackedUuidStr = rs.getString("cracked_uuid");
        if (crackedUuidStr != null && !crackedUuidStr.isEmpty()) {
            try {
                data.crackedUuid = UUID.fromString(crackedUuidStr);
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Некорректный cracked UUID для пользователя " + data.username + ": " + crackedUuidStr);
            }
        }

        data.lastIp = rs.getString("last_ip");
        data.lastLogin = rs.getLong("last_login");
        data.registrationDate = rs.getLong("registration_date");

        data.worldName = rs.getString("world_name");
        if (data.worldName == null) {
            data.worldName = "world";
        }

        data.x = rs.getDouble("x");
        data.y = rs.getDouble("y");
        data.z = rs.getDouble("z");
        data.yaw = rs.getFloat("yaw");
        data.pitch = rs.getFloat("pitch");

        data.inventoryData = rs.getString("inventory_data");
        data.enderchestData = rs.getString("enderchest_data");
        data.experience = rs.getInt("experience");
        data.level = rs.getInt("level");
        data.health = rs.getDouble("health");
        data.food = rs.getInt("food");
        data.saturation = rs.getFloat("saturation");

        data.gameMode = rs.getString("game_mode");
        if (data.gameMode == null) {
            data.gameMode = "SURVIVAL";
        }

        // НОВОЕ: Загрузка расширенных данных
        try {
            data.advancementsData = rs.getString("advancements_data");
        } catch (SQLException ignored) {}

        try {
            data.statisticsData = rs.getString("statistics_data");
        } catch (SQLException ignored) {}

        try {
            data.recipesData = rs.getString("recipes_data");
        } catch (SQLException ignored) {}

        try {
            data.potionEffectsData = rs.getString("potion_effects_data");
        } catch (SQLException ignored) {}

        data.createdAt = rs.getLong("created_at");
        data.updatedAt = rs.getLong("updated_at");

        return data;
    }

    public void saveFromPlayer(Player player) {
        try {
            Location loc = player.getLocation();
            this.worldName = loc.getWorld().getName();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.yaw = loc.getYaw();
            this.pitch = loc.getPitch();

            this.inventoryData = serializeInventory(player.getInventory().getContents());
            this.enderchestData = serializeInventory(player.getEnderChest().getContents());
            this.experience = player.getTotalExperience();
            this.level = player.getLevel();
            this.health = player.getHealth();
            this.food = player.getFoodLevel();
            this.saturation = player.getSaturation();
            this.gameMode = player.getGameMode().name();

            // НОВОЕ: Сохранение расширенных данных
            this.advancementsData = serializeAdvancements(player);
            this.statisticsData = serializeStatistics(player);
            this.recipesData = serializeRecipes(player);
            this.potionEffectsData = serializePotionEffects(player);

            this.updatedAt = System.currentTimeMillis();

            LOGGER.info("Данные игрока " + username + " сохранены полностью: " +
                    "мир=" + worldName +
                    ", координаты=(" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z) + ")" +
                    ", режим=" + gameMode +
                    ", здоровье=" + Math.round(health) +
                    ", голод=" + food +
                    ", достижений=" + (advancementsData != null ? "да" : "нет") +
                    ", рецептов=" + (recipesData != null ? "да" : "нет"));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка сохранения данных игрока " + username, e);
            throw new RuntimeException("Не удалось сохранить данные игрока", e);
        }
    }

    public void applyToPlayer(Player player, World defaultWorld) {
        try {
            // Восстановление местоположения
            World world = player.getServer().getWorld(worldName);
            if (world == null) {
                LOGGER.warning("Мир " + worldName + " не найден для игрока " + username + ", используется " + defaultWorld.getName());
                world = defaultWorld;
            }

            double safeY = Math.max(-64, Math.min(320, y));
            if (safeY != y) {
                LOGGER.warning("Некорректная Y координата " + y + " для игрока " + username + ", используется " + safeY);
            }

            Location location = new Location(world, x, safeY, z, yaw, pitch);

            if (isLocationSafe(location)) {
                player.teleport(location);
            } else {
                LOGGER.warning("Небезопасная локация для игрока " + username + ", телепортируем на спавн");
                player.teleport(world.getSpawnLocation());
            }

            // Восстановление игрового режима
            try {
                GameMode mode = GameMode.valueOf(gameMode.toUpperCase());
                player.setGameMode(mode);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Некорректный игровой режим " + gameMode + " для игрока " + username + ", используется SURVIVAL");
                player.setGameMode(GameMode.SURVIVAL);
            }

            // Восстановление инвентаря
            if (inventoryData != null && !inventoryData.isEmpty()) {
                ItemStack[] inventory = deserializeInventory(inventoryData);
                if (inventory != null) {
                    player.getInventory().setContents(inventory);
                } else {
                    LOGGER.warning("Не удалось восстановить инвентарь для игрока " + username);
                }
            }

            if (enderchestData != null && !enderchestData.isEmpty()) {
                ItemStack[] enderchest = deserializeInventory(enderchestData);
                if (enderchest != null) {
                    player.getEnderChest().setContents(enderchest);
                } else {
                    LOGGER.warning("Не удалось восстановить эндер-сундук для игрока " + username);
                }
            }

            // Восстановление статов
            player.setTotalExperience(Math.max(0, experience));
            player.setLevel(Math.max(0, level));

            double safeHealth = Math.max(0.5, Math.min(player.getMaxHealth(), health));
            player.setHealth(safeHealth);

            player.setFoodLevel(Math.max(0, Math.min(20, food)));
            player.setSaturation(Math.max(0, Math.min(20, saturation)));

            // НОВОЕ: Восстановление расширенных данных
            if (advancementsData != null && !advancementsData.isEmpty()) {
                deserializeAdvancements(player, advancementsData);
            }

            if (statisticsData != null && !statisticsData.isEmpty()) {
                deserializeStatistics(player, statisticsData);
            }

            if (recipesData != null && !recipesData.isEmpty()) {
                deserializeRecipes(player, recipesData);
            }

            if (potionEffectsData != null && !potionEffectsData.isEmpty()) {
                deserializePotionEffects(player, potionEffectsData);
            }

            LOGGER.info("Данные игрока " + username + " восстановлены успешно");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка восстановления данных игрока " + username, e);

            // В случае критической ошибки отправляем на спавн с базовыми настройками
            player.teleport(defaultWorld.getSpawnLocation());
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
        }
    }

    private boolean isLocationSafe(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        if (location.getY() < -64 || location.getY() > 320) {
            return false;
        }

        return true;
    }

    private String serializeInventory(ItemStack[] items) {
        try {
            if (items == null) {
                return null;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка сериализации инвентаря для игрока " + username, e);
            return null;
        }
    }

    private ItemStack[] deserializeInventory(String data) {
        try {
            if (data == null || data.isEmpty()) {
                return null;
            }

            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];

            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }

            dataInput.close();
            return items;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Ошибка десериализации инвентаря для игрока " + username, e);
            return null;
        }
    }

    // === НОВОЕ: Методы сериализации достижений ===

    private String serializeAdvancements(Player player) {
        try {
            Map<String, Set<String>> advancementData = new HashMap<>();

            Iterator<org.bukkit.advancement.Advancement> it = Bukkit.getServer().advancementIterator();
            while (it.hasNext()) {
                org.bukkit.advancement.Advancement adv = it.next();
                org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(adv);

                if (progress != null && progress.getAwardedCriteria().size() > 0) {
                    advancementData.put(
                            adv.getKey().toString(),
                            new HashSet<>(progress.getAwardedCriteria())
                    );
                }
            }

            return GSON.toJson(advancementData);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка сериализации достижений для " + username, e);
            return null;
        }
    }

    private void deserializeAdvancements(Player player, String data) {
        try {
            Map<String, Set<String>> advancementData = GSON.fromJson(
                    data,
                    new TypeToken<Map<String, Set<String>>>(){}.getType()
            );

            if (advancementData == null) return;

            for (Map.Entry<String, Set<String>> entry : advancementData.entrySet()) {
                org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(entry.getKey());
                if (key == null) continue;

                org.bukkit.advancement.Advancement adv = Bukkit.getServer().getAdvancement(key);
                if (adv == null) continue;

                org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(adv);
                for (String criteria : entry.getValue()) {
                    if (!progress.getAwardedCriteria().contains(criteria)) {
                        progress.awardCriteria(criteria);
                    }
                }
            }

            LOGGER.fine("Достижения восстановлены для " + username);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка восстановления достижений для " + username, e);
        }
    }

    // === НОВОЕ: Методы сериализации статистики ===

    private String serializeStatistics(Player player) {
        try {
            Map<String, Integer> stats = new HashMap<>();

            for (org.bukkit.Statistic stat : org.bukkit.Statistic.values()) {
                try {
                    if (stat.getType() == org.bukkit.Statistic.Type.UNTYPED) {
                        int value = player.getStatistic(stat);
                        if (value > 0) {
                            stats.put(stat.name(), value);
                        }
                    }
                } catch (Exception ignored) {}
            }

            return GSON.toJson(stats);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка сериализации статистики для " + username, e);
            return null;
        }
    }

    private void deserializeStatistics(Player player, String data) {
        try {
            Map<String, Integer> stats = GSON.fromJson(
                    data,
                    new TypeToken<Map<String, Integer>>(){}.getType()
            );

            if (stats == null) return;

            for (Map.Entry<String, Integer> entry : stats.entrySet()) {
                try {
                    org.bukkit.Statistic stat = org.bukkit.Statistic.valueOf(entry.getKey());
                    if (stat.getType() == org.bukkit.Statistic.Type.UNTYPED) {
                        player.setStatistic(stat, entry.getValue());
                    }
                } catch (Exception ignored) {}
            }

            LOGGER.fine("Статистика восстановлена для " + username);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка восстановления статистики для " + username, e);
        }
    }

    // === НОВОЕ: Методы сериализации рецептов ===

    private String serializeRecipes(Player player) {
        try {
            Set<String> recipes = new HashSet<>();

            for (org.bukkit.NamespacedKey key : player.getDiscoveredRecipes()) {
                recipes.add(key.toString());
            }

            return GSON.toJson(recipes);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка сериализации рецептов для " + username, e);
            return null;
        }
    }

    private void deserializeRecipes(Player player, String data) {
        try {
            Set<String> recipes = GSON.fromJson(
                    data,
                    new TypeToken<Set<String>>(){}.getType()
            );

            if (recipes == null) return;

            for (String recipeStr : recipes) {
                org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(recipeStr);
                if (key != null) {
                    player.discoverRecipe(key);
                }
            }

            LOGGER.fine("Рецепты восстановлены для " + username);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка восстановления рецептов для " + username, e);
        }
    }

    // === НОВОЕ: Методы сериализации эффектов ===

    private String serializePotionEffects(Player player) {
        try {
            List<Map<String, Object>> effects = new ArrayList<>();

            for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                Map<String, Object> effectData = new HashMap<>();
                effectData.put("type", effect.getType().getName());
                effectData.put("duration", effect.getDuration());
                effectData.put("amplifier", effect.getAmplifier());
                effectData.put("ambient", effect.isAmbient());
                effectData.put("particles", effect.hasParticles());
                effectData.put("icon", effect.hasIcon());
                effects.add(effectData);
            }

            return GSON.toJson(effects);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка сериализации эффектов для " + username, e);
            return null;
        }
    }

    private void deserializePotionEffects(Player player, String data) {
        try {
            List<Map<String, Object>> effects = GSON.fromJson(
                    data,
                    new TypeToken<List<Map<String, Object>>>(){}.getType()
            );

            if (effects == null) return;

            // Удаляем старые эффекты
            for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }

            // Применяем сохраненные
            for (Map<String, Object> effectData : effects) {
                String typeName = (String) effectData.get("type");
                org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(typeName);

                if (type != null) {
                    int duration = ((Number) effectData.get("duration")).intValue();
                    int amplifier = ((Number) effectData.get("amplifier")).intValue();
                    boolean ambient = (Boolean) effectData.get("ambient");
                    boolean particles = (Boolean) effectData.get("particles");
                    boolean icon = (Boolean) effectData.get("icon");

                    org.bukkit.potion.PotionEffect effect = new org.bukkit.potion.PotionEffect(
                            type, duration, amplifier, ambient, particles, icon
                    );
                    player.addPotionEffect(effect);
                }
            }

            LOGGER.fine("Эффекты зелий восстановлены для " + username);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ошибка восстановления эффектов для " + username, e);
        }
    }

    // Создание данных для нового игрока
    public static PlayerData createNewPlayer(String username, String passwordHash, String salt, UUID crackedUuid) {
        PlayerData data = new PlayerData(username);
        data.passwordHash = passwordHash;
        data.salt = salt;
        data.crackedUuid = crackedUuid;
        data.registrationDate = System.currentTimeMillis();
        data.lastLogin = System.currentTimeMillis();

        data.worldName = "world";
        data.x = 0;
        data.y = 64;
        data.z = 0;
        data.yaw = 0;
        data.pitch = 0;
        data.experience = 0;
        data.level = 0;
        data.health = 20;
        data.food = 20;
        data.saturation = 5.0f;
        data.gameMode = "SURVIVAL";

        return data;
    }

    public boolean isValid() {
        return username != null && !username.isEmpty()
                && passwordHash != null && !passwordHash.isEmpty()
                && salt != null && !salt.isEmpty()
                && crackedUuid != null;
    }

    // Геттеры и сеттеры
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username.toLowerCase(); }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public UUID getPremiumUuid() { return premiumUuid; }
    public void setPremiumUuid(UUID premiumUuid) { this.premiumUuid = premiumUuid; }

    public UUID getCrackedUuid() { return crackedUuid; }
    public void setCrackedUuid(UUID crackedUuid) { this.crackedUuid = crackedUuid; }

    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }

    public long getLastLogin() { return lastLogin; }
    public void setLastLogin(long lastLogin) { this.lastLogin = lastLogin; }

    public long getRegistrationDate() { return registrationDate; }
    public void setRegistrationDate(long registrationDate) { this.registrationDate = registrationDate; }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }

    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }

    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }

    public String getInventoryData() { return inventoryData; }
    public void setInventoryData(String inventoryData) { this.inventoryData = inventoryData; }

    public String getEnderchestData() { return enderchestData; }
    public void setEnderchestData(String enderchestData) { this.enderchestData = enderchestData; }

    public int getExperience() { return experience; }
    public void setExperience(int experience) { this.experience = experience; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public double getHealth() { return health; }
    public void setHealth(double health) { this.health = health; }

    public int getFood() { return food; }
    public void setFood(int food) { this.food = food; }

    public float getSaturation() { return saturation; }
    public void setSaturation(float saturation) { this.saturation = saturation; }

    public String getGameMode() { return gameMode; }
    public void setGameMode(String gameMode) { this.gameMode = gameMode; }

    // НОВОЕ: Геттеры и сеттеры для расширенных данных
    public String getAdvancementsData() { return advancementsData; }
    public void setAdvancementsData(String advancementsData) { this.advancementsData = advancementsData; }

    public String getStatisticsData() { return statisticsData; }
    public void setStatisticsData(String statisticsData) { this.statisticsData = statisticsData; }

    public String getRecipesData() { return recipesData; }
    public void setRecipesData(String recipesData) { this.recipesData = recipesData; }

    public String getPotionEffectsData() { return potionEffectsData; }
    public void setPotionEffectsData(String potionEffectsData) { this.potionEffectsData = potionEffectsData; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return String.format("PlayerData{username='%s', worldName='%s', gameMode='%s', lastLogin=%d}",
                username, worldName, gameMode, lastLogin);
    }
}