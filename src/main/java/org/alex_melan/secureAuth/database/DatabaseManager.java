package org.alex_melan.secureAuth.database;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.models.PlayerData;
import org.alex_melan.secureAuth.utils.PasswordUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class DatabaseManager {

    private final SecureAuthPlugin plugin;
    private HikariDataSource dataSource;
    private final Object initLock = new Object();

    public DatabaseManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() throws SQLException {
        synchronized (initLock) {
            setupDatabase();
            createTables();
            plugin.getLogger().info("База данных инициализирована успешно!");
        }
    }

    private void setupDatabase() {
        try {
            // Убеждаемся что папка плагина существует
            File pluginDir = plugin.getDataFolder();
            if (!pluginDir.exists()) {
                pluginDir.mkdirs();
            }

            String dbPath = new File(pluginDir, "database.db").getAbsolutePath();
            plugin.getLogger().info("Инициализация базы данных: " + dbPath);

            HikariConfig config = new HikariConfig();

            // SQLite конфигурация
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setDriverClassName("org.sqlite.JDBC");

            // Оптимизация для SQLite
            config.setMaximumPoolSize(1); // SQLite лучше работает с одним соединением
            config.setMinimumIdle(1);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setLeakDetectionThreshold(60000);

            // SQLite специфичные настройки
            config.addDataSourceProperty("foreign_keys", "true");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");
            config.addDataSourceProperty("cache_size", "10000");
            config.addDataSourceProperty("temp_store", "memory");

            // Проверка соединения
            config.setConnectionTestQuery("SELECT 1");
            config.setValidationTimeout(5000);

            dataSource = new HikariDataSource(config);

            // Тестируем соединение
            try (Connection testConn = dataSource.getConnection()) {
                plugin.getLogger().info("Тестовое соединение с базой данных успешно");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка настройки базы данных: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Ошибка инициализации базы данных", e);
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Включаем внешние ключи
            conn.createStatement().execute("PRAGMA foreign_keys = ON");

            // Таблица игроков
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    premium_uuid TEXT,
                    cracked_uuid TEXT NOT NULL UNIQUE,
                    last_ip TEXT,
                    last_login BIGINT DEFAULT 0,
                    registration_date BIGINT DEFAULT 0,
                    world_name TEXT DEFAULT 'world',
                    x REAL DEFAULT 0,
                    y REAL DEFAULT 64,
                    z REAL DEFAULT 0,
                    yaw REAL DEFAULT 0,
                    pitch REAL DEFAULT 0,
                    inventory_data TEXT,
                    enderchest_data TEXT,
                    experience INTEGER DEFAULT 0,
                    level INTEGER DEFAULT 0,
                    health REAL DEFAULT 20,
                    food INTEGER DEFAULT 20,
                    saturation REAL DEFAULT 5,
                    game_mode TEXT DEFAULT 'SURVIVAL',
                    created_at BIGINT DEFAULT (strftime('%s', 'now') * 1000),
                    updated_at BIGINT DEFAULT (strftime('%s', 'now') * 1000)
                )
            """);

            // Таблица сессий
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_hash TEXT NOT NULL UNIQUE,
                    username TEXT NOT NULL COLLATE NOCASE,
                    ip_address TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    last_activity BIGINT DEFAULT 0,
                    is_active BOOLEAN DEFAULT 1,
                    FOREIGN KEY (username) REFERENCES players(username) ON DELETE CASCADE
                )
            """);

            // Таблица логов безопасности
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS security_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT,
                    ip_address TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    success BOOLEAN NOT NULL,
                    details TEXT,
                    timestamp BIGINT DEFAULT (strftime('%s', 'now') * 1000)
                )
            """);

            // Индексы для оптимизации
            String[] indexes = {
                    "CREATE INDEX IF NOT EXISTS idx_players_username ON players(username)",
                    "CREATE INDEX IF NOT EXISTS idx_players_premium_uuid ON players(premium_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_players_cracked_uuid ON players(cracked_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_sessions_hash ON sessions(session_hash)",
                    "CREATE INDEX IF NOT EXISTS idx_sessions_username ON sessions(username)",
                    "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)",
                    "CREATE INDEX IF NOT EXISTS idx_sessions_active ON sessions(is_active)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_username ON security_logs(username)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_ip ON security_logs(ip_address)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_timestamp ON security_logs(timestamp)"
            };

            for (String index : indexes) {
                conn.createStatement().executeUpdate(index);
            }

            // Триггеры для обновления updated_at
            conn.createStatement().executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS players_updated_at 
                AFTER UPDATE ON players 
                FOR EACH ROW 
                BEGIN 
                    UPDATE players SET updated_at = strftime('%s', 'now') * 1000 WHERE id = NEW.id;
                END
            """);

            plugin.getLogger().info("Таблицы базы данных созданы/проверены успешно");
        }
    }

    // Логирование действий безопасности
    public CompletableFuture<Void> logSecurityAction(String username, String ipAddress,
                                                     String actionType, boolean success, String details) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO security_logs (username, ip_address, action_type, success, details, timestamp)
                     VALUES (?, ?, ?, ?, ?, ?)
                 """)) {

                stmt.setString(1, username);
                stmt.setString(2, ipAddress);
                stmt.setString(3, actionType);
                stmt.setBoolean(4, success);
                stmt.setString(5, details);
                stmt.setLong(6, System.currentTimeMillis());

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка записи лога безопасности", e);
            }
        });
    }

    // Асинхронные методы для работы с игроками
    public CompletableFuture<Boolean> isPlayerRegistered(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM players WHERE username = ? COLLATE NOCASE")) {

                stmt.setString(1, username.toLowerCase());
                return stmt.executeQuery().next();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка проверки регистрации игрока " + username, e);
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> registerPlayer(String username, String password, UUID crackedUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO players (username, password_hash, salt, cracked_uuid, registration_date, last_login)
                     VALUES (?, ?, ?, ?, ?, ?)
                 """)) {

                String salt = PasswordUtils.generateSalt();
                String hash = PasswordUtils.hashPassword(password, salt);
                long now = System.currentTimeMillis();

                stmt.setString(1, username.toLowerCase());
                stmt.setString(2, hash);
                stmt.setString(3, salt);
                stmt.setString(4, crackedUuid.toString());
                stmt.setLong(5, now);
                stmt.setLong(6, now);

                boolean success = stmt.executeUpdate() > 0;

                // Логируем регистрацию
                logSecurityAction(username, "unknown", "REGISTER", success,
                        success ? "User registered successfully" : "Registration failed");

                return success;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка регистрации игрока " + username, e);
                logSecurityAction(username, "unknown", "REGISTER", false, "Database error: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> authenticatePlayer(String username, String password, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT password_hash, salt FROM players WHERE username = ? COLLATE NOCASE
                 """)) {

                stmt.setString(1, username.toLowerCase());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String salt = rs.getString("salt");
                    String inputHash = PasswordUtils.hashPassword(password, salt);

                    boolean success = storedHash.equals(inputHash);

                    if (success) {
                        // Обновляем данные последнего входа
                        updateLastLogin(username, ipAddress);
                        logSecurityAction(username, ipAddress, "LOGIN", true, "Successful authentication");
                    } else {
                        logSecurityAction(username, ipAddress, "LOGIN", false, "Invalid password");
                    }

                    return success;
                }

                logSecurityAction(username, ipAddress, "LOGIN", false, "User not found");
                return false;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка аутентификации игрока " + username, e);
                logSecurityAction(username, ipAddress, "LOGIN", false, "Database error: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<PlayerData> getPlayerData(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT * FROM players WHERE username = ? COLLATE NOCASE
                 """)) {

                stmt.setString(1, username.toLowerCase());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return PlayerData.fromResultSet(rs);
                }

                return null;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка получения данных игрока " + username, e);
                return null;
            }
        });
    }

    public CompletableFuture<Void> savePlayerData(PlayerData playerData) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     UPDATE players SET 
                         world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?,
                         inventory_data = ?, enderchest_data = ?, experience = ?, level = ?,
                         health = ?, food = ?, saturation = ?, game_mode = ?
                     WHERE username = ? COLLATE NOCASE
                 """)) {

                stmt.setString(1, playerData.getWorldName());
                stmt.setDouble(2, playerData.getX());
                stmt.setDouble(3, playerData.getY());
                stmt.setDouble(4, playerData.getZ());
                stmt.setFloat(5, playerData.getYaw());
                stmt.setFloat(6, playerData.getPitch());
                stmt.setString(7, playerData.getInventoryData());
                stmt.setString(8, playerData.getEnderchestData());
                stmt.setInt(9, playerData.getExperience());
                stmt.setInt(10, playerData.getLevel());
                stmt.setDouble(11, playerData.getHealth());
                stmt.setInt(12, playerData.getFood());
                stmt.setFloat(13, playerData.getSaturation());
                stmt.setString(14, playerData.getGameMode());
                stmt.setString(15, playerData.getUsername().toLowerCase());

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка сохранения данных игрока " + playerData.getUsername(), e);
            }
        });
    }

    // Методы для работы с сессиями
    public CompletableFuture<String> createSession(String username, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // Удаляем старые сессии этого пользователя
                try (PreparedStatement deleteStmt = conn.prepareStatement("""
                     UPDATE sessions SET is_active = 0 WHERE username = ? COLLATE NOCASE
                """)) {
                    deleteStmt.setString(1, username.toLowerCase());
                    deleteStmt.executeUpdate();
                }

                // Создаем новую сессию
                try (PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO sessions (session_hash, username, ip_address, created_at, expires_at, last_activity, is_active)
                     VALUES (?, ?, ?, ?, ?, ?, 1)
                 """)) {

                    long now = System.currentTimeMillis();
                    long expiresAt = now + plugin.getConfigManager().getSessionTTL();

                    String sessionHash = PasswordUtils.createSessionHash(username, ipAddress, now);

                    stmt.setString(1, sessionHash);
                    stmt.setString(2, username.toLowerCase());
                    stmt.setString(3, ipAddress);
                    stmt.setLong(4, now);
                    stmt.setLong(5, expiresAt);
                    stmt.setLong(6, now);

                    if (stmt.executeUpdate() > 0) {
                        logSecurityAction(username, ipAddress, "SESSION_CREATE", true, "Session created");
                        return sessionHash;
                    }
                }

                return null;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка создания сессии для " + username, e);
                logSecurityAction(username, ipAddress, "SESSION_CREATE", false, "Database error: " + e.getMessage());
                return null;
            }
        });
    }

    public CompletableFuture<Boolean> validateSession(String sessionHash, String username, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT expires_at FROM sessions 
                     WHERE session_hash = ? AND username = ? COLLATE NOCASE AND ip_address = ? AND is_active = 1
                 """)) {

                stmt.setString(1, sessionHash);
                stmt.setString(2, username.toLowerCase());
                stmt.setString(3, ipAddress);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    boolean valid = System.currentTimeMillis() < expiresAt;

                    if (valid) {
                        // Обновляем время последней активности
                        updateSessionActivity(sessionHash);
                    } else {
                        // Деактивируем просроченную сессию
                        invalidateSession(sessionHash);
                    }

                    return valid;
                }

                return false;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка валидации сессии", e);
                return false;
            }
        });
    }

    public CompletableFuture<Void> invalidateSession(String sessionHash) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("UPDATE sessions SET is_active = 0 WHERE session_hash = ?")) {

                stmt.setString(1, sessionHash);
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка деактивации сессии", e);
            }
        });
    }

    public void invalidateSessionSync(String sessionHash) {
        if (dataSource == null || dataSource.isClosed()) {
            plugin.getLogger().warning("DataSource уже закрыт, пропускаем деактивацию сессии");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("UPDATE sessions SET is_active = 0 WHERE session_hash = ?")) {

            stmt.setString(1, sessionHash);
            stmt.executeUpdate();

        } catch (SQLException e) {
            // Логируем только если это не ошибка закрытого DataSource
            if (!e.getMessage().contains("has been closed")) {
                plugin.getLogger().log(Level.WARNING, "Ошибка синхронной деактивации сессии", e);
            }
        }
    }

    public CompletableFuture<Void> invalidateUserSessions(String username) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("UPDATE sessions SET is_active = 0 WHERE username = ? COLLATE NOCASE")) {

                stmt.setString(1, username.toLowerCase());
                int updated = stmt.executeUpdate();

                logSecurityAction(username, "admin", "SESSION_INVALIDATE", true,
                        "Invalidated " + updated + " sessions");

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка деактивации сессий пользователя " + username, e);
            }
        });
    }

    private void updateSessionActivity(String sessionHash) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("UPDATE sessions SET last_activity = ? WHERE session_hash = ?")) {

                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, sessionHash);
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка обновления активности сессии", e);
            }
        });
    }

    public CompletableFuture<Void> cleanExpiredSessions() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("UPDATE sessions SET is_active = 0 WHERE expires_at < ? AND is_active = 1")) {

                stmt.setLong(1, System.currentTimeMillis());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    plugin.getLogger().info("Деактивировано " + deleted + " просроченных сессий");
                }

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка очистки просроченных сессий", e);
            }
        });
    }

    // Объединение UUID для лицензионных и пиратских аккаунтов
    public CompletableFuture<Boolean> linkPremiumAccount(String username, UUID premiumUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     UPDATE players SET premium_uuid = ? WHERE username = ? COLLATE NOCASE
                 """)) {

                stmt.setString(1, premiumUuid.toString());
                stmt.setString(2, username.toLowerCase());

                boolean success = stmt.executeUpdate() > 0;

                logSecurityAction(username, "system", "UUID_LINK", success,
                        "Premium UUID linked: " + premiumUuid);

                return success;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка привязки премиум аккаунта", e);
                return false;
            }
        });
    }

    private void updateLastLogin(String username, String ipAddress) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     UPDATE players SET last_login = ?, last_ip = ? WHERE username = ? COLLATE NOCASE
                 """)) {

                stmt.setLong(1, System.currentTimeMillis());
                stmt.setString(2, ipAddress);
                stmt.setString(3, username.toLowerCase());
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка обновления времени входа", e);
            }
        });
    }

    // Статистика
    public CompletableFuture<Integer> getActiveSessionsCount() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT COUNT(*) FROM sessions WHERE is_active = 1 AND expires_at > ?
                 """)) {

                stmt.setLong(1, System.currentTimeMillis());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return rs.getInt(1);
                }

                return 0;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка получения количества активных сессий", e);
                return 0;
            }
        });
    }

    // Метод для получения соединения (для миграций)
    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource не инициализирован");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            plugin.getLogger().info("Закрытие соединения с базой данных...");
            dataSource.close();
        }
    }
}