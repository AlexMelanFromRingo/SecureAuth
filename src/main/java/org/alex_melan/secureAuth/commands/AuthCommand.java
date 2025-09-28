package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AuthCommand implements CommandExecutor {

    private final SecureAuthPlugin plugin;

    public AuthCommand(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Проверка что команду выполняет игрок
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only"));
            return true;
        }

        Player player = (Player) sender;
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        // Проверка полной инициализации плагина
        if (!plugin.isFullyInitialized()) {
            player.sendMessage("§cПлагин авторизации еще не полностью загружен. Подождите немного...");
            return true;
        }

        // Проверка авторизации
        if (plugin.getSessionManager().isAuthenticated(username)) {
            player.sendMessage(plugin.getConfigManager().getMessage("login-already-authenticated"));
            return true;
        }

        // Проверка блокировки IP
        if (plugin.getSessionManager().isLoginBlocked(ipAddress)) {
            long remainingTime = plugin.getSessionManager().getRemainingBlockTime(ipAddress);
            int remainingMinutes = (int) (remainingTime / 1000 / 60) + 1;

            player.sendMessage(plugin.getConfigManager().getMessage("login-blocked",
                    String.valueOf(remainingMinutes)));
            return true;
        }

        // Проверка аргументов
        if (args.length != 1) {
            player.sendMessage(plugin.getConfigManager().getMessage("login-usage"));
            return true;
        }

        String password = args[0];

        // Базовая валидация пароля
        if (password.length() < 1 || password.length() > 100) {
            player.sendMessage("§cНекорректная длина пароля!");
            return true;
        }

        // Проверка существования аккаунта
        plugin.getDatabaseManager().isPlayerRegistered(username)
                .thenAccept(registered -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!registered) {
                            player.sendMessage(plugin.getConfigManager().getMessage("login-not-registered"));
                            return;
                        }

                        // Асинхронная аутентификация
                        authenticatePlayer(player, password, ipAddress);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка проверки регистрации для игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(plugin.getConfigManager().getMessage("error-database"));
                    });
                    return null;
                });

        return true;
    }

    private void authenticatePlayer(Player player, String password, String ipAddress) {
        String username = player.getName();

        plugin.getAuthManager().authenticatePlayer(username, password, ipAddress)
                .thenAccept(success -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        // Проверяем что игрок все еще онлайн
                        if (!player.isOnline()) {
                            plugin.getLogger().info("Игрок " + username + " отключился во время аутентификации");
                            return;
                        }

                        if (success) {
                            handleSuccessfulLogin(player, ipAddress);
                        } else {
                            handleFailedLogin(player, ipAddress);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка аутентификации игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-database"));
                        }
                    });
                    return null;
                });
    }

    private void handleSuccessfulLogin(Player player, String ipAddress) {
        String username = player.getName();

        // Очищаем неудачные попытки
        plugin.getSessionManager().clearFailedLogins(ipAddress);

        // Создание сессии
        plugin.getSessionManager().createSession(username, ipAddress)
                .thenAccept(sessionCreated -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (sessionCreated) {
                            // Загружаем данные игрока и возвращаем в мир
                            loadPlayerDataAndReturn(player);
                        } else {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-session"));
                            plugin.getLogger().warning("Не удалось создать сессию для игрока " + username);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка создания сессии для игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-session"));
                        }
                    });
                    return null;
                });
    }

    private void handleFailedLogin(Player player, String ipAddress) {
        String username = player.getName();

        // Записываем неудачную попытку
        plugin.getSessionManager().recordFailedLogin(ipAddress);

        // Отправляем сообщение об ошибке
        player.sendMessage(plugin.getConfigManager().getMessage("login-wrong-password"));

        // Проверяем, не заблокирован ли IP теперь
        if (plugin.getSessionManager().isLoginBlocked(ipAddress)) {
            long remainingTime = plugin.getSessionManager().getRemainingBlockTime(ipAddress);
            int remainingMinutes = (int) (remainingTime / 1000 / 60) + 1;

            player.sendMessage(plugin.getConfigManager().getMessage("login-blocked",
                    String.valueOf(remainingMinutes)));

            plugin.getLogger().warning(String.format(
                    "IP %s заблокирован после неудачной попытки входа игрока %s",
                    ipAddress, username
            ));
        }

        // Логируем неудачную попытку
        if (plugin.getConfigManager().isLogFailedLogins()) {
            plugin.getDatabaseManager().logSecurityAction(
                    username,
                    ipAddress,
                    "FAILED_LOGIN",
                    false,
                    "Invalid password attempt"
            );
        }
    }

    private void loadPlayerDataAndReturn(Player player) {
        String username = player.getName();

        plugin.getAuthManager().loadPlayerData(username)
                .thenAccept(data -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (data != null) {
                            // Возвращаем игрока из лобби авторизации
                            plugin.getLobbyManager().returnFromAuthLobby(player);

                            // Отправляем сообщение об успешном входе
                            player.sendMessage(plugin.getConfigManager().getMessage("login-success"));

                            // Логируем успешный вход
                            if (plugin.getConfigManager().isLogSuccessfulLogins()) {
                                plugin.getDatabaseManager().logSecurityAction(
                                        username,
                                        player.getAddress().getAddress().getHostAddress(),
                                        "SUCCESSFUL_LOGIN",
                                        true,
                                        "Player logged in successfully"
                                );
                            }

                            plugin.getLogger().info("Игрок " + username + " успешно авторизован");
                        } else {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-load-data"));
                            plugin.getLogger().warning("Не удалось загрузить данные игрока " + username);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка загрузки данных игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-load-data"));
                        }
                    });
                    return null;
                });
    }
}