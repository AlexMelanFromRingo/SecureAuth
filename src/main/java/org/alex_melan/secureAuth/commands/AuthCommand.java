package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

// Login Command
public class AuthCommand implements CommandExecutor {

    private final SecureAuthPlugin plugin;

    public AuthCommand(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cЭта команда доступна только игрокам!");
            return true;
        }

        Player player = (Player) sender;
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        // Проверка авторизации
        if (plugin.getSessionManager().isAuthenticated(username)) {
            player.sendMessage("§cВы уже авторизованы!");
            return true;
        }

        // Проверка блокировки IP
        if (plugin.getSessionManager().isLoginBlocked(ipAddress)) {
            player.sendMessage("§cСлишком много неудачных попыток входа! Попробуйте позже.");
            return true;
        }

        // Проверка аргументов
        if (args.length != 1) {
            player.sendMessage("§cИспользование: /login <пароль>");
            return true;
        }

        String password = args[0];

        // Асинхронная аутентификация
        plugin.getAuthManager().authenticatePlayer(username, password, ipAddress)
                .thenAccept(success -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (success) {
                            // Успешная аутентификация
                            plugin.getSessionManager().clearFailedLogins(ipAddress);

                            // Создание сессии
                            plugin.getSessionManager().createSession(username, ipAddress)
                                    .thenAccept(sessionCreated -> {
                                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                                            if (sessionCreated) {
                                                // Загружаем данные игрока
                                                plugin.getAuthManager().loadPlayerData(username)
                                                        .thenAccept(data -> {
                                                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                                                if (data != null) {
                                                                    plugin.getLobbyManager().returnFromAuthLobby(player);
                                                                } else {
                                                                    player.sendMessage("§cОшибка загрузки данных игрока!");
                                                                }
                                                            });
                                                        });
                                            } else {
                                                player.sendMessage("§cОшибка создания сессии!");
                                            }
                                        });
                                    });
                        } else {
                            // Неудачная аутентификация
                            plugin.getSessionManager().recordFailedLogin(ipAddress);
                            player.sendMessage("§cНеверный пароль!");

                            // Проверяем, не заблокирован ли IP теперь
                            if (plugin.getSessionManager().isLoginBlocked(ipAddress)) {
                                player.sendMessage("§cСлишком много неудачных попыток! IP заблокирован на 15 минут.");
                            }
                        }
                    });
                });

        return true;
    }
}