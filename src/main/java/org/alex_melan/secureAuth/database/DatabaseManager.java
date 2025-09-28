package org.alex_melan.secureAuth.database;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.models.PlayerData;
import org.alex_melan.secureAuth.models.SessionData;
import org.alex_melan.secureAuth.utils.PasswordUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class DatabaseManager {

    private final SecureAuthPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() throws SQLException {
        setupDatabase();
        createTables();
    }

    private void setupDatabase() {
        // Проверка на то, доступен ли драйвер в момент инициализации. Тесты показали, что нужно оставить строки config.set и config.add
//        Enumeration<Driver> drivers = DriverManager.getDrivers();
//        while (drivers.hasMoreElements()) {
//            Driver d = drivers.nextElement();
//            try {
//                plugin.getLogger().info("Driver: " + d.getClass().getName()
//                        + " | classloader: " + d.getClass().getClassLoader()
//                        + " | accepts sqlite: " + d.acceptsURL("jdbc:sqlite:plugins/SecureAuth/database.db"));
//            } catch (Throwable ex) {
//                plugin.getLogger().info("Driver: " + d.getClass().getName()
//                        + " | classloader: " + d.getClass().getClassLoader()
//                        + " | acceptsURL threw: " + ex.getMessage());
//            }
//        }
//        plugin.getLogger().info("Thread context classloader: " + Thread.currentThread().getContextClassLoader());
//        plugin.getLogger().info("Plugin classloader: " + this.getClass().getClassLoader());


//        // Принудительная загрузка SQLite драйвера
//        try {
//            Class.forName("org.sqlite.JDBC");
//            plugin.getLogger().info("SQLite JDBC driver loaded successfully");
//        } catch (ClassNotFoundException e) {
//            plugin.getLogger().severe("SQLite JDBC driver not found in classpath!");
//            throw new RuntimeException("SQLite driver missing", e);
//        }
//
//        // Сначала тестируем прямое подключение
//        String jdbcUrl = "jdbc:sqlite:plugins/SecureAuth/database.db";
//        try (Connection testConn = DriverManager.getConnection(jdbcUrl)) {
//            plugin.getLogger().info("Direct SQLite connection test successful");
//        } catch (SQLException e) {
//            plugin.getLogger().severe("Direct SQLite connection failed: " + e.getMessage());
//            throw new RuntimeException("SQLite connection failed", e);
//        }


        HikariConfig config = new HikariConfig();

        // Починка инициализации БД
        config.setDataSourceClassName("org.sqlite.SQLiteDataSource");
        config.addDataSourceProperty("url", "jdbc:sqlite:plugins/SecureAuth/database.db");

        // SQLite для простоты, но можно легко заменить на MySQL/PostgreSQL
        config.setJdbcUrl("jdbc:sqlite:plugins/SecureAuth/database.db");
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Таблица игроков
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    premium_uuid TEXT,
                    cracked_uuid TEXT NOT NULL UNIQUE,
                    last_ip TEXT,
                    last_login BIGINT,
                    registration_date BIGINT,
                    world_name TEXT,
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
                    saturation REAL DEFAULT 5
                )
            """);

            // Таблица сессий
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_hash TEXT NOT NULL UNIQUE,
                    username TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    FOREIGN KEY (username) REFERENCES players(username)
                )
            """);

            // Индексы для оптимизации
            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_username ON players(username)");
            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_hash ON sessions(session_hash)");
            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)");
        }
    }

    // Асинхронные методы для работы с игроками

    public CompletableFuture<Boolean> isPlayerRegistered(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM players WHERE username = ?")) {

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

                return stmt.executeUpdate() > 0;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка регистрации игрока " + username, e);
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> authenticatePlayer(String username, String password, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT password_hash, salt FROM players WHERE username = ?
                 """)) {

                stmt.setString(1, username.toLowerCase());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String salt = rs.getString("salt");
                    String inputHash = PasswordUtils.hashPassword(password, salt);

                    if (storedHash.equals(inputHash)) {
                        // Обновляем данные последнего входа
                        updateLastLogin(username, ipAddress);
                        return true;
                    }
                }

                return false;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка аутентификации игрока " + username, e);
                return false;
            }
        });
    }

    public CompletableFuture<PlayerData> getPlayerData(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT * FROM players WHERE username = ?
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
                         health = ?, food = ?, saturation = ?
                     WHERE username = ?
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
                stmt.setString(14, playerData.getUsername().toLowerCase());

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка сохранения данных игрока " + playerData.getUsername(), e);
            }
        });
    }

    // Методы для работы с сессиями

    public CompletableFuture<String> createSession(String username, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO sessions (session_hash, username, ip_address, created_at, expires_at)
                     VALUES (?, ?, ?, ?, ?)
                 """)) {

                long now = System.currentTimeMillis();
                long expiresAt = now + plugin.getConfigManager().getSessionTTL();

                // Создаем хеш сессии из username + ip + timestamp для безопасности
                String sessionData = username + ":" + ipAddress + ":" + now;
                String sessionHash = PasswordUtils.hashPassword(sessionData, PasswordUtils.generateSalt());

                stmt.setString(1, sessionHash);
                stmt.setString(2, username.toLowerCase());
                stmt.setString(3, ipAddress);
                stmt.setLong(4, now);
                stmt.setLong(5, expiresAt);

                if (stmt.executeUpdate() > 0) {
                    return sessionHash;
                }

                return null;

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка создания сессии для " + username, e);
                return null;
            }
        });
    }

    public CompletableFuture<Boolean> validateSession(String sessionHash, String username, String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("""
                     SELECT expires_at FROM sessions 
                     WHERE session_hash = ? AND username = ? AND ip_address = ?
                 """)) {

                stmt.setString(1, sessionHash);
                stmt.setString(2, username.toLowerCase());
                stmt.setString(3, ipAddress);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    return System.currentTimeMillis() < expiresAt;
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
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE session_hash = ?")) {

                stmt.setString(1, sessionHash);
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка удаления сессии", e);
            }
        });
    }

    public CompletableFuture<Void> cleanExpiredSessions() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {

                stmt.setLong(1, System.currentTimeMillis());
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    plugin.getLogger().info("Удалено " + deleted + " просроченных сессий");
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
                     UPDATE players SET premium_uuid = ? WHERE username = ?
                 """)) {

                stmt.setString(1, premiumUuid.toString());
                stmt.setString(2, username.toLowerCase());

                return stmt.executeUpdate() > 0;

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
                     UPDATE players SET last_login = ?, last_ip = ? WHERE username = ?
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

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
































//import org.alex_melan.secureAuth.SecureAuthPlugin;
//import org.alex_melan.secureAuth.models.PlayerData;
//import org.alex_melan.secureAuth.models.SessionData;
//import org.alex_melan.secureAuth.utils.PasswordUtils;
//import com.zaxxer.hikari.HikariConfig;
//import com.zaxxer.hikari.HikariDataSource;
//
//import java.sql.*;
//import java.util.UUID;
//import java.util.concurrent.CompletableFuture;
//import java.util.logging.Level;
//
//public class DatabaseManager {
//
//    private final SecureAuthPlugin plugin;
//    private HikariDataSource dataSource;
//
//    public DatabaseManager(SecureAuthPlugin plugin) {
//        this.plugin = plugin;
//    }
//
//    public void initialize() throws SQLException {
//        setupDatabase();
//        createTables();
//    }
//
//    private void setupDatabase() {
//        // Принудительная загрузка SQLite драйвера
//        try {
//            Class.forName("org.sqlite.JDBC");
//            plugin.getLogger().info("SQLite JDBC driver loaded successfully");
//        } catch (ClassNotFoundException e) {
//            plugin.getLogger().severe("SQLite JDBC driver not found in classpath!");
//            throw new RuntimeException("SQLite driver missing", e);
//        }
//
//        // Сначала тестируем прямое подключение
//        String jdbcUrl = "jdbc:sqlite:plugins/SecureAuth/database.db";
//        try (Connection testConn = DriverManager.getConnection(jdbcUrl)) {
//            plugin.getLogger().info("Direct SQLite connection test successful");
//        } catch (SQLException e) {
//            plugin.getLogger().severe("Direct SQLite connection failed: " + e.getMessage());
//            throw new RuntimeException("SQLite connection failed", e);
//        }
//
//        HikariConfig config = new HikariConfig();
//
//        // Конфигурация HikariCP для SQLite (без relocation)
//        config.setDriverClassName("org.sqlite.JDBC");
//        config.setJdbcUrl(jdbcUrl);
//
//        // SQLite specific optimizations
//        config.setMaximumPoolSize(1); // SQLite works better with single connection
//        config.setMinimumIdle(1);
//        config.setConnectionTimeout(30000);
//        config.setIdleTimeout(600000);
//        config.setMaxLifetime(1800000);
//
//        // Отключаем проверки для SQLite (они могут мешать)
//        config.setConnectionTestQuery("SELECT 1");
//        config.setValidationTimeout(5000);
//
//        // Add SQLite specific properties
//        config.addDataSourceProperty("foreign_keys", "true");
//        config.addDataSourceProperty("journal_mode", "WAL");
//        config.addDataSourceProperty("synchronous", "NORMAL");
//
//        try {
//            dataSource = new HikariDataSource(config);
//            plugin.getLogger().info("Database connection pool initialized successfully");
//        } catch (Exception e) {
//            plugin.getLogger().severe("Failed to initialize database connection pool: " + e.getMessage());
//            e.printStackTrace(); // Добавляем полный стек для диагностики
//            throw new RuntimeException("Database initialization failed", e);
//        }
//    }
//
//    private void createTables() throws SQLException {
//        try (Connection conn = dataSource.getConnection()) {
//            // Таблица игроков
//            conn.createStatement().executeUpdate("""
//                CREATE TABLE IF NOT EXISTS players (
//                    id INTEGER PRIMARY KEY AUTOINCREMENT,
//                    username TEXT NOT NULL UNIQUE,
//                    password_hash TEXT NOT NULL,
//                    salt TEXT NOT NULL,
//                    premium_uuid TEXT,
//                    cracked_uuid TEXT NOT NULL UNIQUE,
//                    last_ip TEXT,
//                    last_login BIGINT,
//                    registration_date BIGINT,
//                    world_name TEXT,
//                    x REAL DEFAULT 0,
//                    y REAL DEFAULT 64,
//                    z REAL DEFAULT 0,
//                    yaw REAL DEFAULT 0,
//                    pitch REAL DEFAULT 0,
//                    inventory_data TEXT,
//                    enderchest_data TEXT,
//                    experience INTEGER DEFAULT 0,
//                    level INTEGER DEFAULT 0,
//                    health REAL DEFAULT 20,
//                    food INTEGER DEFAULT 20,
//                    saturation REAL DEFAULT 5
//                )
//            """);
//
//            // Таблица сессий
//            conn.createStatement().executeUpdate("""
//                CREATE TABLE IF NOT EXISTS sessions (
//                    id INTEGER PRIMARY KEY AUTOINCREMENT,
//                    session_hash TEXT NOT NULL UNIQUE,
//                    username TEXT NOT NULL,
//                    ip_address TEXT NOT NULL,
//                    created_at BIGINT NOT NULL,
//                    expires_at BIGINT NOT NULL,
//                    FOREIGN KEY (username) REFERENCES players(username)
//                )
//            """);
//
//            // Индексы для оптимизации
//            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_username ON players(username)");
//            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_hash ON sessions(session_hash)");
//            conn.createStatement().executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)");
//
//            plugin.getLogger().info("Database tables created/verified successfully");
//        }
//    }
//
//    // Асинхронные методы для работы с игроками
//
//    public CompletableFuture<Boolean> isPlayerRegistered(String username) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM players WHERE username = ?")) {
//
//                stmt.setString(1, username.toLowerCase());
//                return stmt.executeQuery().next();
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка проверки регистрации игрока " + username, e);
//                return false;
//            }
//        });
//    }
//
//    public CompletableFuture<Boolean> registerPlayer(String username, String password, UUID crackedUuid) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     INSERT INTO players (username, password_hash, salt, cracked_uuid, registration_date, last_login)
//                     VALUES (?, ?, ?, ?, ?, ?)
//                 """)) {
//
//                String salt = PasswordUtils.generateSalt();
//                String hash = PasswordUtils.hashPassword(password, salt);
//                long now = System.currentTimeMillis();
//
//                stmt.setString(1, username.toLowerCase());
//                stmt.setString(2, hash);
//                stmt.setString(3, salt);
//                stmt.setString(4, crackedUuid.toString());
//                stmt.setLong(5, now);
//                stmt.setLong(6, now);
//
//                return stmt.executeUpdate() > 0;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка регистрации игрока " + username, e);
//                return false;
//            }
//        });
//    }
//
//    public CompletableFuture<Boolean> authenticatePlayer(String username, String password, String ipAddress) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     SELECT password_hash, salt FROM players WHERE username = ?
//                 """)) {
//
//                stmt.setString(1, username.toLowerCase());
//                ResultSet rs = stmt.executeQuery();
//
//                if (rs.next()) {
//                    String storedHash = rs.getString("password_hash");
//                    String salt = rs.getString("salt");
//                    String inputHash = PasswordUtils.hashPassword(password, salt);
//
//                    if (storedHash.equals(inputHash)) {
//                        // Обновляем данные последнего входа
//                        updateLastLogin(username, ipAddress);
//                        return true;
//                    }
//                }
//
//                return false;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка аутентификации игрока " + username, e);
//                return false;
//            }
//        });
//    }
//
//    public CompletableFuture<PlayerData> getPlayerData(String username) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     SELECT * FROM players WHERE username = ?
//                 """)) {
//
//                stmt.setString(1, username.toLowerCase());
//                ResultSet rs = stmt.executeQuery();
//
//                if (rs.next()) {
//                    return PlayerData.fromResultSet(rs);
//                }
//
//                return null;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка получения данных игрока " + username, e);
//                return null;
//            }
//        });
//    }
//
//    public CompletableFuture<Void> savePlayerData(PlayerData playerData) {
//        return CompletableFuture.runAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     UPDATE players SET
//                         world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?,
//                         inventory_data = ?, enderchest_data = ?, experience = ?, level = ?,
//                         health = ?, food = ?, saturation = ?
//                     WHERE username = ?
//                 """)) {
//
//                stmt.setString(1, playerData.getWorldName());
//                stmt.setDouble(2, playerData.getX());
//                stmt.setDouble(3, playerData.getY());
//                stmt.setDouble(4, playerData.getZ());
//                stmt.setFloat(5, playerData.getYaw());
//                stmt.setFloat(6, playerData.getPitch());
//                stmt.setString(7, playerData.getInventoryData());
//                stmt.setString(8, playerData.getEnderchestData());
//                stmt.setInt(9, playerData.getExperience());
//                stmt.setInt(10, playerData.getLevel());
//                stmt.setDouble(11, playerData.getHealth());
//                stmt.setInt(12, playerData.getFood());
//                stmt.setFloat(13, playerData.getSaturation());
//                stmt.setString(14, playerData.getUsername().toLowerCase());
//
//                stmt.executeUpdate();
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка сохранения данных игрока " + playerData.getUsername(), e);
//            }
//        });
//    }
//
//    // Методы для работы с сессиями
//
//    public CompletableFuture<String> createSession(String username, String ipAddress) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     INSERT INTO sessions (session_hash, username, ip_address, created_at, expires_at)
//                     VALUES (?, ?, ?, ?, ?)
//                 """)) {
//
//                long now = System.currentTimeMillis();
//                long expiresAt = now + plugin.getConfigManager().getSessionTTL();
//
//                // Создаем хеш сессии из username + ip + timestamp для безопасности
//                String sessionData = username + ":" + ipAddress + ":" + now;
//                String sessionHash = PasswordUtils.hashPassword(sessionData, PasswordUtils.generateSalt());
//
//                stmt.setString(1, sessionHash);
//                stmt.setString(2, username.toLowerCase());
//                stmt.setString(3, ipAddress);
//                stmt.setLong(4, now);
//                stmt.setLong(5, expiresAt);
//
//                if (stmt.executeUpdate() > 0) {
//                    return sessionHash;
//                }
//
//                return null;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка создания сессии для " + username, e);
//                return null;
//            }
//        });
//    }
//
//    public CompletableFuture<Boolean> validateSession(String sessionHash, String username, String ipAddress) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     SELECT expires_at FROM sessions
//                     WHERE session_hash = ? AND username = ? AND ip_address = ?
//                 """)) {
//
//                stmt.setString(1, sessionHash);
//                stmt.setString(2, username.toLowerCase());
//                stmt.setString(3, ipAddress);
//
//                ResultSet rs = stmt.executeQuery();
//                if (rs.next()) {
//                    long expiresAt = rs.getLong("expires_at");
//                    return System.currentTimeMillis() < expiresAt;
//                }
//
//                return false;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка валидации сессии", e);
//                return false;
//            }
//        });
//    }
//
//    public CompletableFuture<Void> invalidateSession(String sessionHash) {
//        return CompletableFuture.runAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE session_hash = ?")) {
//
//                stmt.setString(1, sessionHash);
//                stmt.executeUpdate();
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка удаления сессии", e);
//            }
//        });
//    }
//
//    public CompletableFuture<Void> cleanExpiredSessions() {
//        return CompletableFuture.runAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
//
//                stmt.setLong(1, System.currentTimeMillis());
//                int deleted = stmt.executeUpdate();
//
//                if (deleted > 0) {
//                    plugin.getLogger().info("Удалено " + deleted + " просроченных сессий");
//                }
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка очистки просроченных сессий", e);
//            }
//        });
//    }
//
//    // Объединение UUID для лицензионных и пиратских аккаунтов
//    public CompletableFuture<Boolean> linkPremiumAccount(String username, UUID premiumUuid) {
//        return CompletableFuture.supplyAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     UPDATE players SET premium_uuid = ? WHERE username = ?
//                 """)) {
//
//                stmt.setString(1, premiumUuid.toString());
//                stmt.setString(2, username.toLowerCase());
//
//                return stmt.executeUpdate() > 0;
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка привязки премиум аккаунта", e);
//                return false;
//            }
//        });
//    }
//
//    private void updateLastLogin(String username, String ipAddress) {
//        CompletableFuture.runAsync(() -> {
//            try (Connection conn = dataSource.getConnection();
//                 PreparedStatement stmt = conn.prepareStatement("""
//                     UPDATE players SET last_login = ?, last_ip = ? WHERE username = ?
//                 """)) {
//
//                stmt.setLong(1, System.currentTimeMillis());
//                stmt.setString(2, ipAddress);
//                stmt.setString(3, username.toLowerCase());
//                stmt.executeUpdate();
//
//            } catch (SQLException e) {
//                plugin.getLogger().log(Level.SEVERE, "Ошибка обновления времени входа", e);
//            }
//        });
//    }
//
//    public void close() {
//        if (dataSource != null && !dataSource.isClosed()) {
//            dataSource.close();
//        }
//    }
//}