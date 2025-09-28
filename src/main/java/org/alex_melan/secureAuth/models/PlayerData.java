package org.alex_melan.secureAuth.models;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerData {

    private static final Logger LOGGER = Logger.getLogger(PlayerData.class.getName());

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

    // Системные поля
    private long createdAt;
    private long updatedAt;

    public PlayerData(String username) {
        this.username = username.toLowerCase();
        this.gameMode = "SURVIVAL"; // Значение по умолчанию
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
            data.worldName = "world"; // Значение по умолчанию
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
            data.gameMode = "SURVIVAL"; // Значение по умолчанию
        }

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

            this.updatedAt = System.currentTimeMillis();

            LOGGER.info("Данные игрока " + username + " сохранены: мир=" + worldName +
                    ", координаты=(" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z) + ")");

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

            // Проверка безопасности координат
            double safeY = Math.max(-64, Math.min(320, y));
            if (safeY != y) {
                LOGGER.warning("Некорректная Y координата " + y + " для игрока " + username + ", используется " + safeY);
            }

            Location location = new Location(world, x, safeY, z, yaw, pitch);

            // Проверяем что локация безопасна
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

            // Восстановление статов с проверками
            player.setTotalExperience(Math.max(0, experience));
            player.setLevel(Math.max(0, level));

            double safeHealth = Math.max(0.5, Math.min(player.getMaxHealth(), health));
            player.setHealth(safeHealth);

            player.setFoodLevel(Math.max(0, Math.min(20, food)));
            player.setSaturation(Math.max(0, Math.min(20, saturation)));

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

        // Проверяем что координаты в разумных пределах
        if (location.getY() < -64 || location.getY() > 320) {
            return false;
        }

        // Можно добавить дополнительные проверки безопасности
        // например, проверка на лаву, пустоту и т.д.

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

    // Создание данных для нового игрока
    public static PlayerData createNewPlayer(String username, String passwordHash, String salt, UUID crackedUuid) {
        PlayerData data = new PlayerData(username);
        data.passwordHash = passwordHash;
        data.salt = salt;
        data.crackedUuid = crackedUuid;
        data.registrationDate = System.currentTimeMillis();
        data.lastLogin = System.currentTimeMillis();

        // Значения по умолчанию для нового игрока
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

    // Валидация данных
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