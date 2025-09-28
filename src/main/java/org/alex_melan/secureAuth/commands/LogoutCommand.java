package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LogoutCommand implements CommandExecutor {

    private final SecureAuthPlugin plugin;

    public LogoutCommand(SecureAuthPlugin plugin) {
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

        // Проверка полной инициализации плагина
        if (!plugin.isFullyInitialized()) {
            player.sendMessage("§cПлагин авторизации еще не полностью загружен.");
            return true;
        }

        // Проверка авторизации
        if (!plugin.getSessionManager().isAuthenticated(username)) {
            player.sendMessage(plugin.getConfigManager().getMessage("logout-not-authenticated"));
            return true;
        }

        // Сохраняем данные игрока перед выходом
        plugin.getAuthManager().savePlayerData(player);

        // Деактивируем сессию
        plugin.getSessionManager().invalidateSession(username);

        // Отправляем в лобби авторизации
        plugin.getLobbyManager().sendToAuthLobby(player);

        // Отправляем сообщение
        player.sendMessage(plugin.getConfigManager().getMessage("logout-success"));

        // Логируем действие
        plugin.getDatabaseManager().logSecurityAction(
                username,
                player.getAddress().getAddress().getHostAddress(),
                "LOGOUT",
                true,
                "Player logged out manually"
        );

        plugin.getLogger().info("Игрок " + username + " вышел из аккаунта командой /logout");

        return true;
    }
}