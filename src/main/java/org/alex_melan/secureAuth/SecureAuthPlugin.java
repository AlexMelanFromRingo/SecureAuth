package org.alex_melan.secureAuth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.alex_melan.secureAuth.commands.AuthCommand;
import org.alex_melan.secureAuth.commands.RegisterCommand;
import org.alex_melan.secureAuth.database.DatabaseManager;
import org.alex_melan.secureAuth.listeners.PlayerListener;
import org.alex_melan.secureAuth.managers.AuthManager;
import org.alex_melan.secureAuth.managers.SessionManager;
import org.alex_melan.secureAuth.managers.LobbyManager;
import org.alex_melan.secureAuth.config.ConfigManager;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SecureAuthPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private AuthManager authManager;
    private SessionManager sessionManager;
    private LobbyManager lobbyManager;
    private ConfigManager configManager;
    private BukkitTask sessionCleanupTask;

    @Override
    public void onEnable() {
        getLogger().info("Загрузка SecureAuth...");

        // Инициализация конфигурации
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // Асинхронная инициализация БД
        CompletableFuture.runAsync(() -> {
            try {
                databaseManager = new DatabaseManager(this);
                databaseManager.initialize();

                // Инициализация менеджеров после БД
                Bukkit.getScheduler().runTask(this, () -> {
                    authManager = new AuthManager(this, databaseManager);
                    sessionManager = new SessionManager(this, databaseManager);
                    lobbyManager = new LobbyManager(this);

                    // Регистрация событий и команд
                    registerListeners();
                    registerCommands();

                    // Запуск очистки просроченных сессий
                    startSessionCleanup();

                    getLogger().info("SecureAuth успешно загружен!");
                });
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Ошибка инициализации БД:", e);
                Bukkit.getPluginManager().disablePlugin(this);
            }
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("Выгрузка SecureAuth...");

        // Остановка задач
        if (sessionCleanupTask != null && !sessionCleanupTask.isCancelled()) {
            sessionCleanupTask.cancel();
        }

        // Сохранение данных игроков и закрытие БД
        if (authManager != null) {
            authManager.saveAllPlayerData();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("SecureAuth выгружен!");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    private void registerCommands() {
        getCommand("login").setExecutor(new AuthCommand(this));
        getCommand("register").setExecutor(new RegisterCommand(this));
    }

    private void startSessionCleanup() {
        // Очистка просроченных сессий каждые 5 минут
        sessionCleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (sessionManager != null) {
                sessionManager.cleanExpiredSessions();
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // 5 минут в тиках
    }

    // Геттеры для менеджеров
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public AuthManager getAuthManager() { return authManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public LobbyManager getLobbyManager() { return lobbyManager; }
    public ConfigManager getConfigManager() { return configManager; }
}