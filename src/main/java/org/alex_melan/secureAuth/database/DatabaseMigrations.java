package org.alex_melan.secureAuth.database;

import org.alex_melan.secureAuth.SecureAuthPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Система миграций базы данных для SecureAuth
 *
 * Автоматически обновляет структуру базы данных при обновлениях плагина
 *
 * @author Alex Melan
 * @version 1.0
 */
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

    /**
     * Инициализация списка миграций
     */
    private void initializeMigrations() {
        // Миграция 1: Создание таблицы версий миграций
        migrations.add(new Migration(1, "Create migrations table", this::createMigrationsTable));

        // Миграция 2: Добавление колонки game_mode в таблицу players
        migrations.add(new Migration(2, "Add game_mode column to players", this::addGameModeColumn));

        // Миграция 3: Добавление индексов для оптимизации
        migrations.add(new Migration(3, "Add performance indexes", this::addPerformanceIndexes));

        // Миграция 4: Добавление колонок created_at и updated_at
        migrations.add(new Migration(4, "Add timestamp columns", this::addTimestampColumns));

        // Миграция 5: Добавление таблицы логов безопасности
        migrations.add(new Migration(5, "Create security_logs table", this::createSecurityLogsTable));

        // Миграция 6: Добавление колонки last_activity в сессии
        migrations.add(new Migration(6, "Add last_activity to sessions", this::addLastActivityColumn));

        // Миграция 7: Добавление колонки is_active в сессии
        migrations.add(new Migration(7, "Add is_active to sessions", this::addIsActiveColumn));

        // Добавляйте новые миграции здесь с увеличивающимися номерами
    }

    /**
     * Выполнение всех необходимых миграций
     */
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

    /**
     * Получение текущей версии миграций
     */
    private int getCurrentMigrationVersion() throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            // Проверяем существование таблицы миграций
            if (!tableExists(conn, "migrations")) {
                return 0; // База данных новая
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

    /**
     * Проверка существования таблицы
     */
    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    /**
     * Проверка существования колонки в таблице
     */
    private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    /**
     * Выполнение одной миграции
     */
    private void runMigration(Migration migration) throws SQLException {
        plugin.getLogger().info("Выполнение миграции " + migration.getVersion() + ": " + migration.getDescription());

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Выполняем миграцию
                migration.getExecutor().execute(conn);

                // Записываем успешное выполнение
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

    /**
     * Запись информации о выполненной миграции
     */
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

    // Конкретные миграции

    /**
     * Миграция 1: Создание таблицы миграций
     */
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

    /**
     * Миграция 2: Добавление колонки game_mode
     */
    private void addGameModeColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "players", "game_mode")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE players ADD COLUMN game_mode TEXT DEFAULT 'SURVIVAL'"
            )) {
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Миграция 3: Добавление индексов для производительности
     */
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

    /**
     * Миграция 4: Добавление колонок времени
     */
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

            // Создаем триггер для автообновления
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

    /**
     * Миграция 5: Создание таблицы логов безопасности
     */
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

            // Добавляем индексы
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

    /**
     * Миграция 6: Добавление колонки last_activity в сессии
     */
    private void addLastActivityColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "sessions", "last_activity")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE sessions ADD COLUMN last_activity BIGINT DEFAULT 0"
            )) {
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Миграция 7: Добавление колонки is_active в сессии
     */
    private void addIsActiveColumn(Connection conn) throws SQLException {
        if (!columnExists(conn, "sessions", "is_active")) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE sessions ADD COLUMN is_active BOOLEAN DEFAULT 1"
            )) {
                stmt.executeUpdate();
            }

            // Добавляем индекс
            try (PreparedStatement indexStmt = conn.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_sessions_active ON sessions(is_active)"
            )) {
                indexStmt.executeUpdate();
            }
        }
    }

    /**
     * Очистка старых данных (вызывается периодически)
     */
    public void cleanupOldData() {
        plugin.getLogger().info("Запуск очистки старых данных...");

        try (Connection conn = databaseManager.getConnection()) {
            // Очищаем старые неактивные сессии (старше 30 дней)
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

            // Очищаем старые логи безопасности (старше 90 дней)
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

    /**
     * Проверка целостности базы данных
     */
    public boolean verifyDatabaseIntegrity() {
        plugin.getLogger().info("Проверка целостности базы данных...");

        try (Connection conn = databaseManager.getConnection()) {
            // Проверяем основные таблицы
            String[] requiredTables = {"players", "sessions", "security_logs", "migrations"};

            for (String table : requiredTables) {
                if (!tableExists(conn, table)) {
                    plugin.getLogger().severe("Отсутствует обязательная таблица: " + table);
                    return false;
                }
            }

            // Проверяем основные колонки в таблице players
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

    /**
     * Создание резервной копии базы данных
     */
    public boolean createBackup() {
        // Реализация зависит от типа базы данных
        // Для SQLite можно просто скопировать файл
        // Для MySQL/PostgreSQL нужно выполнить dump

        plugin.getLogger().info("Создание резервной копии базы данных...");

        // TODO: Реализовать создание резервных копий
        // В зависимости от типа БД из конфигурации

        return true;
    }

    // Вспомогательные классы

    /**
     * Представляет одну миграцию базы данных
     */
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

    /**
     * Функциональный интерфейс для выполнения миграций
     */
    @FunctionalInterface
    private interface MigrationExecutor {
        void execute(Connection connection) throws SQLException;
    }
}