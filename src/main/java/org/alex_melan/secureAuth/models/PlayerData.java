package org.alex_melan.secureAuth.models;

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

public class PlayerData {

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

    public PlayerData(String username) {
        this.username = username.toLowerCase();
    }

    public static PlayerData fromResultSet(ResultSet rs) throws SQLException {
        PlayerData data = new PlayerData(rs.getString("username"));

        data.passwordHash = rs.getString("password_hash");
        data.salt = rs.getString("salt");

        String premiumUuidStr = rs.getString("premium_uuid");
        if (premiumUuidStr != null) {
            data.premiumUuid = UUID.fromString(premiumUuidStr);
        }

        data.crackedUuid = UUID.fromString(rs.getString("cracked_uuid"));
        data.lastIp = rs.getString("last_ip");
        data.lastLogin = rs.getLong("last_login");
        data.registrationDate = rs.getLong("registration_date");

        data.worldName = rs.getString("world_name");
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

        return data;
    }

    public void saveFromPlayer(Player player) {
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
    }

    public void applyToPlayer(Player player, World defaultWorld) {
        // Восстановление местоположения
        World world = player.getServer().getWorld(worldName);
        if (world == null) {
            world = defaultWorld;
        }

        Location location = new Location(world, x, y, z, yaw, pitch);
        player.teleport(location);

        // Восстановление инвентаря
        if (inventoryData != null) {
            ItemStack[] inventory = deserializeInventory(inventoryData);
            if (inventory != null) {
                player.getInventory().setContents(inventory);
            }
        }

        if (enderchestData != null) {
            ItemStack[] enderchest = deserializeInventory(enderchestData);
            if (enderchest != null) {
                player.getEnderChest().setContents(enderchest);
            }
        }

        // Восстановление статов
        player.setTotalExperience(experience);
        player.setLevel(level);
        player.setHealth(Math.min(health, player.getMaxHealth()));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
    }

    private String serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (Exception e) {
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
            return null;
        }
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
}

