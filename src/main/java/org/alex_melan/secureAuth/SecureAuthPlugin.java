package org.alex_melan.secureAuth;

import org.alex_melan.secureAuth.commands.LogoutCommand;
import org.alex_melan.secureAuth.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.alex_melan.secureAuth.commands.AuthCommand;
import org.alex_melan.secureAuth.commands.RegisterCommand;
import org.alex_melan.secureAuth.commands.AuthAdminCommand;
import org.alex_melan.secureAuth.api.SecureAuthAPI;
import org.alex_melan.secureAuth.database.DatabaseMigrations;
import org.alex_melan.secureAuth.listeners.PlayerListener;
import org.alex_melan.secureAuth.managers.AuthManager;
import org.alex_melan.secureAuth.managers.SessionManager;
import org.alex_melan.secureAuth.managers.LobbyManager;
import org.alex_melan.secureAuth.config.ConfigManager;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SecureAuthPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private DatabaseMigrations databaseMigrations;
    private AuthManager authManager;
    private SessionManager sessionManager;
    private LobbyManager lobbyManager;
    private ConfigManager configManager;
    private SecureAuthAPI api;

    private BukkitTask sessionCleanupTask;
    private BukkitTask autoSaveTask;

    private boolean fullyInitialized = false;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        getLogger().info("=== Загрузка SecureAuth v" + getDescription().getVersion() + " ===");

        try {
            // Инициализация конфигурации
            configManager = new ConfigManager(this);
            configManager.loadConfig();

            // Асинхронная инициализация БД
            CompletableFuture.runAsync(() -> {
                try {
                    getLogger().info("Инициализация базы данных...");
                    databaseManager = new DatabaseManager(this);
                    databaseManager.initialize();

                    // Выполнение миграций
                    getLogger().info("Проверка миграций базы данных...");
                    databaseMigrations = new DatabaseMigrations(this, databaseManager);
                    databaseMigrations.runMigrations();

                    // Проверка целостности БД
                    if (!databaseMigrations.verifyDatabaseIntegrity()) {
                        throw new RuntimeException("Проверка целостности базы данных не пройдена");
                    }

                    // Инициализация менеджеров после БД (в основном потоке)
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            initializeManagers();
                            registerListeners();
                            registerCommands();
                            startTasks();

                            fullyInitialized = true;
                            long loadTime = System.currentTimeMillis() - startTime;
                            getLogger().info("=== SecureAuth загружен успешно за " + loadTime + "ms ===");

                        } catch (Exception e) {
                            getLogger().log(Level.SEVERE, "Ошибка инициализации менеджеров:", e);
                            disablePlugin();
                        }
                    });

                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Критическая ошибка инициализации БД:", e);
                    Bukkit.getScheduler().runTask(this, this::disablePlugin);
                }
            });

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Критическая ошибка загрузки плагина:", e);
            disablePlugin();
        }
    }

    private void initializeManagers() {
        getLogger().info("Инициализация менеджеров...");

        authManager = new AuthManager(this, databaseManager);
        sessionManager = new SessionManager(this, databaseManager);
        lobbyManager = new LobbyManager(this);

        // Инициализируем API
        api = new SecureAuthAPI(this);

        getLogger().info("Менеджеры инициализированы успешно");
    }

    private void registerListeners() {
        getLogger().info("Регистрация слушателей событий...");
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    private void registerCommands() {
        getLogger().info("Регистрация команд...");

        // Основные команды
        getCommand("login").setExecutor(new AuthCommand(this));
        getCommand("register").setExecutor(new RegisterCommand(this));

        // Админ команды
        getCommand("secureauth").setExecutor(new AuthAdminCommand(this));

        // Команда выхода
        getCommand("logout").setExecutor(new LogoutCommand(this));

        getLogger().info("Команды зарегистрированы успешно");
    }

    private void startTasks() {
        getLogger().info("Запуск фоновых задач...");

        // Очистка просроченных сессий каждые 5 минут
        long cleanupInterval = 20L * 60 * 5; // 5 минут в тиках
        sessionCleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (sessionManager != null && fullyInitialized) {
                sessionManager.cleanExpiredSessions();
            }
        }, cleanupInterval, cleanupInterval);

        // Автосохранение данных игроков каждые 5 минут
        long autoSaveInterval = 20L * 60 * configManager.getAutoSaveInterval();
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (authManager != null && fullyInitialized) {
                authManager.saveAllPlayerData();
            }
        }, autoSaveInterval, autoSaveInterval);

        getLogger().info("Фоновые задачи запущены");
    }

    @Override
    public void onDisable() {
        getLogger().info("=== Выгрузка SecureAuth ===");

        // Отмечаем что плагин выгружается
        fullyInitialized = false;

        // Остановка задач
        cancelTasks();

        // Сохранение данных игроков и закрытие БД
        if (authManager != null) {
            getLogger().info("Сохранение данных игроков...");
            authManager.saveAllPlayerData();

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (sessionManager != null) {
            getLogger().info("Деактивация активных сессий...");
            sessionManager.invalidateAllSessionsSync();
        }

        if (databaseManager != null) {
            getLogger().info("Закрытие соединения с базой данных...");

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            databaseManager.close();
        }

        getLogger().info("=== SecureAuth выгружен ===");
    }

    private void cancelTasks() {
        if (sessionCleanupTask != null && !sessionCleanupTask.isCancelled()) {
            sessionCleanupTask.cancel();
            getLogger().info("Задача очистки сессий остановлена");
        }

        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
            getLogger().info("Задача автосохранения остановлена");
        }
    }

    private void disablePlugin() {
        getLogger().severe("Отключение плагина из-за критических ошибок...");
        Bukkit.getPluginManager().disablePlugin(this);
    }

    // Геттеры для менеджеров
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public LobbyManager getLobbyManager() {
        return lobbyManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Получение API для других плагинов
     * @return SecureAuthAPI экземпляр
     */
    public SecureAuthAPI getAPI() {
        return api;
    }

    /**
     * Проверка полной инициализации плагина
     */
    public boolean isFullyInitialized() {
        return fullyInitialized;
    }

    /**
     * Перезагрузка плагина
     */
    public CompletableFuture<Boolean> reloadPlugin() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                getLogger().info("Перезагрузка конфигурации SecureAuth...");

                // Перезагружаем конфигурацию
                configManager.loadConfig();

                // Перезапускаем задачи с новыми интервалами
                cancelTasks();
                startTasks();

                getLogger().info("Конфигурация SecureAuth перезагружена успешно");
                return true;

            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Ошибка перезагрузки конфигурации:", e);
                return false;
            }
        });
    }

    /**
     * Получение статистики плагина
     */
    public void printStatistics() {
        if (!fullyInitialized) {
            getLogger().warning("Плагин еще не полностью инициализирован");
            return;
        }

        CompletableFuture.runAsync(() -> {
            databaseManager.getActiveSessionsCount().thenAccept(activeCount -> {
                int onlineAuth = 0;
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (sessionManager.isAuthenticated(player.getName())) {
                        onlineAuth++;
                    }
                }

                getLogger().info("=== Статистика SecureAuth ===");
                getLogger().info("Игроков онлайн: " + Bukkit.getOnlinePlayers().size());
                getLogger().info("Авторизованных: " + onlineAuth);
                getLogger().info("Активных сессий в БД: " + activeCount);
                getLogger().info("=============================");
            });
        });
    }
}