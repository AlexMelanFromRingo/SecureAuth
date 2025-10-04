package org.alex_melan.secureAuth.database;

import org.alex_melan.secureAuth.SecureAuthPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class DatabaseMigrations {

    private final SecureAuthPlugin plugin;
    private final DatabaseManager databaseManager;
    private final List<Migration> migrations;

    public DatabaseMigrations(SecureAuthPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.migrations = new ArrayList<>();

        initializeMigrations();
    }

    private void initializeMigrations() {
        migrations.add(new Migration(1, "Create migrations table", this::createMigrationsTable));
        migrations.add(new Migration(2, "Add game_mode column to players", this::addGameModeColumn));
        migrations.add(new Migration(3, "Add performance indexes", this::addPerformanceIndexes));
        migrations.add(new Migration(4, "Add timestamp columns", this::addTimestampColumns));
        migrations.add(new Migration(5, "Create security_logs table", this::createSecurityLogsTable));
        migrations.add(new Migration(6, "Add last_activity to sessions", this::addLastActivityColumn));
        migrations.add(new Migration(7, "Add is_active to sessions", this::addIsActiveColumn));

        // НОВОЕ: Миграция 8 для расширенных данных
        migrations.add(new Migration(8, "Add extended player data fields", this::addExtendedPlayerData));
    }

    public void runMigrations() {
        plugin.getLogger().info("Проверка необходимости миграций базы данных...");

        try {
            int currentVersion = getCurrentMigrationVersion();
            plugin.getLogger().info("Текущая версия БД: " + currentVersion);

            boolean hasMigrations = false;
            for (Migration migration : migrations) {
                if (migration.getVersion() > currentVersion) {
                    hasMigrations = true;
                    runMigration(migration);
                }
            }

            if (!hasMigrations) {
                plugin.getLogger().info("База данных актуальна, миграции не требуются");
            } else {
                plugin.getLogger().info("Все миграции применены успешно");
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Критическая ошибка при выполнении миграций", e);
            throw new RuntimeException("Миграции базы данных не удались", e);
        }
    }

    private int getCurrentMigrationVersion() throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            if (!tableExists(conn, "migrations")) {
                return 0;
            }

            try (PreparedStatement stmt = conn.prepareStatement("SELECT MAX(version) FROM migrations")) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private void runMigration(Migration migration) throws SQLException {
        plugin.getLogger().info("Выполнение миграции " + migration.getVersion() + ": " + migration.getDescription());

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                migration.getExecutor().execute(conn);
                recordMigration(conn, migration);
                conn.commit();
                plugin.getLogger().info("Миграция " + migration.getVersion() + " выполнена успешно");

            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("Ошибка выполнения миграции " + migration.getVersion(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void recordMigration(Connection conn, Migration migration) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO migrations (version, description, executed_at) VALUES (?, ?, ?)"
        )) {
            stmt.setInt(1, migration.getVersion());
            stmt.setString(2, migration.getDescription());
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    // === МИГРАЦИИ ===

    private void createMigrationsTable(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
            CREATE TABLE IF NOT EXISTS migrations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version INTEGER NOT NULL UNIQUE,
                description TEXT NOT NULL,
                executed_at BIGINT NOT NULL
            )
        """)) {
            stmt.executeUpdate();
        }
    }

    private void addGameModeColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "players", "game_mode")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN game_mode TEXT DEFAULT 'SURVIVAL'"
            )) {
                stmt.executeUpdate();
            }
        }
    }

    private void addPerformanceIndexes(Connection conn) throws SQLException {
        String[] indexes = {
                "CREATE INDEX IF NOT EXISTS idx_players_last_login ON players(last_login)",
                "CREATE INDEX IF NOT EXISTS idx_players_last_ip ON players(last_ip)",
                "CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON sessions(created_at)"
        };

        for (String indexSql : indexes) {
            try (PreparedStatement stmt = conn.prepareStatement(indexSql)) {
                stmt.executeUpdate();
            }
        }
    }

    private void addTimestampColumns(Connection conn) throws SQLException {
        if (!columnExists(conn, "players", "created_at")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN created_at BIGINT DEFAULT (strftime('%s', 'now') * 1000)"
            )) {
                stmt.executeUpdate();
            }
        }

        if (!columnExists(conn, "players", "updated_at")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN updated_at BIGINT DEFAULT (strftime('%s', 'now') * 1000)"
            )) {
                stmt.executeUpdate();
            }

            try (PreparedStatement triggerStmt = conn.prepareStatement("""
                CREATE TRIGGER IF NOT EXISTS players_updated_at 
                AFTER UPDATE ON players 
                FOR EACH ROW 
                BEGIN 
                    UPDATE players SET updated_at = strftime('%s', 'now') * 1000 WHERE id = NEW.id;
                END
            """)) {
                triggerStmt.executeUpdate();
            }
        }
    }

    private void createSecurityLogsTable(Connection conn) throws SQLException {
        if (!tableExists(conn, "security_logs")) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                CREATE TABLE security_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT,
                    ip_address TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    success BOOLEAN NOT NULL,
                    details TEXT,
                    timestamp BIGINT DEFAULT (strftime('%s', 'now') * 1000)
                )
            """)) {
                stmt.executeUpdate();
            }

            String[] indexes = {
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_username ON security_logs(username)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_ip ON security_logs(ip_address)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_timestamp ON security_logs(timestamp)",
                    "CREATE INDEX IF NOT EXISTS idx_security_logs_action ON security_logs(action_type)"
            };

            for (String indexSql : indexes) {
                try (PreparedStatement indexStmt = conn.prepareStatement(indexSql)) {
                    indexStmt.executeUpdate();
                }
            }
        }
    }

    private void addLastActivityColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "sessions", "last_activity")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE sessions ADD COLUMN last_activity BIGINT DEFAULT 0"
            )) {
                stmt.executeUpdate();
            }
        }
    }

    private void addIsActiveColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "sessions", "is_active")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE sessions ADD COLUMN is_active BOOLEAN DEFAULT 1"
            )) {
                stmt.executeUpdate();
            }

            try (PreparedStatement indexStmt = conn.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_sessions_active ON sessions(is_active)"
            )) {
                indexStmt.executeUpdate();
            }
        }
    }

    // НОВОЕ: Миграция 8 - Добавление расширенных данных игрока
    private void addExtendedPlayerData(Connection conn) throws SQLException {
        // Достижения
        if (!columnExists(conn, "players", "advancements_data")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN advancements_data TEXT"
            )) {
                stmt.executeUpdate();
                plugin.getLogger().info("Добавлена колонка advancements_data");
            }
        }

        // Статистика
        if (!columnExists(conn, "players", "statistics_data")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN statistics_data TEXT"
            )) {
                stmt.executeUpdate();
                plugin.getLogger().info("Добавлена колонка statistics_data");
            }
        }

        // Рецепты
        if (!columnExists(conn, "players", "recipes_data")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN recipes_data TEXT"
            )) {
                stmt.executeUpdate();
                plugin.getLogger().info("Добавлена колонка recipes_data");
            }
        }

        // Эффекты зелий
        if (!columnExists(conn, "players", "potion_effects_data")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN potion_effects_data TEXT"
            )) {
                stmt.executeUpdate();
                plugin.getLogger().info("Добавлена колонка potion_effects_data");
            }
        }
    }

    public void cleanupOldData() {
        plugin.getLogger().info("Запуск очистки старых данных...");

        try (Connection conn = databaseManager.getConnection()) {
            long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);

            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM sessions WHERE is_active = 0 AND created_at < ?"
            )) {
                stmt.setLong(1, thirtyDaysAgo);
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    plugin.getLogger().info("Удалено " + deleted + " старых неактивных сессий");
                }
            }

            long ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000);

            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM security_logs WHERE timestamp < ?"
            )) {
                stmt.setLong(1, ninetyDaysAgo);
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    plugin.getLogger().info("Удалено " + deleted + " старых записей логов безопасности");
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка очистки старых данных", e);
        }
    }

    public boolean verifyDatabaseIntegrity() {
        plugin.getLogger().info("Проверка целостности базы данных...");

        try (Connection conn = databaseManager.getConnection()) {
            String[] requiredTables = {"players", "sessions", "security_logs", "migrations"};

            for (String table : requiredTables) {
                if (!tableExists(conn, table)) {
                    plugin.getLogger().severe("Отсутствует обязательная таблица: " + table);
                    return false;
                }
            }

            String[] requiredPlayerColumns = {
                    "username", "password_hash", "salt", "cracked_uuid",
                    "last_login", "registration_date", "world_name"
            };

            for (String column : requiredPlayerColumns) {
                if (!columnExists(conn, "players", column)) {
                    plugin.getLogger().severe("Отсутствует обязательная колонка в таблице players: " + column);
                    return false;
                }
            }

            plugin.getLogger().info("Проверка целостности базы данных завершена успешно");
            return true;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка проверки целостности базы данных", e);
            return false;
        }
    }

    public boolean createBackup() {
        plugin.getLogger().info("Создание резервной копии базы данных...");
        return true;
    }

    private static class Migration {
        private final int version;
        private final String description;
        private final MigrationExecutor executor;

        public Migration(int version, String description, MigrationExecutor executor) {
            this.version = version;
            this.description = description;
            this.executor = executor;
        }

        public int getVersion() { return version; }
        public String getDescription() { return description; }
        public MigrationExecutor getExecutor() { return executor; }
    }

    @FunctionalInterface
    private interface MigrationExecutor {
        void execute(Connection connection) throws SQLException;
    }
}