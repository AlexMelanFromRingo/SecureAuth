package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AuthAdminCommand implements CommandExecutor {

    private final SecureAuthPlugin plugin;

    public AuthAdminCommand(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("secureauth.admin")) {
            sender.sendMessage("§cУ вас нет прав для использования этой команды!");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                plugin.getConfigManager().loadConfig();
                sender.sendMessage("§aКонфигурация перезагружена!");
                break;

            case "forcelogout":
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /secureauth forcelogout <игрок>");
                    return true;
                }

                String targetName = args[1];
                Player target = Bukkit.getPlayerExact(targetName);

                if (target != null) {
                    plugin.getSessionManager().invalidateSession(targetName);
                    plugin.getLobbyManager().sendToAuthLobby(target);
                    sender.sendMessage("§aИгрок " + targetName + " принудительно разлогинен!");
                } else {
                    sender.sendMessage("§cИгрок не найден!");
                }
                break;

            case "sessions":
                // Показать активные сессии (можно расширить)
                int activeSessions = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (plugin.getSessionManager().isAuthenticated(player.getName())) {
                        activeSessions++;
                    }
                }
                sender.sendMessage("§eАктивных сессий: §a" + activeSessions);
                break;

            case "cleanup":
                plugin.getSessionManager().cleanExpiredSessions();
                sender.sendMessage("§aОчистка просроченных сессий запущена!");
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== SecureAuth Admin ===");
        sender.sendMessage("§e/secureauth reload §7- перезагрузить конфигурацию");
        sender.sendMessage("§e/secureauth forcelogout <игрок> §7- принудительный выход");
        sender.sendMessage("§e/secureauth sessions §7- показать активные сессии");
        sender.sendMessage("§e/secureauth cleanup §7- очистить просроченные сессии");
    }
}