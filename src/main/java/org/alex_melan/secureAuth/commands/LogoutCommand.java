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
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only"));
            return true;
        }

        Player player = (Player) sender;
        String username = player.getName();

        if (!plugin.isFullyInitialized()) {
            player.sendMessage("§cПлагин авторизации еще не полностью загружен.");
            return true;
        }

        if (!plugin.getSessionManager().isAuthenticated(username)) {
            player.sendMessage(plugin.getConfigManager().getMessage("logout-not-authenticated"));
            return true;
        }

        // ИСПРАВЛЕНО: Принудительно обновляем кеш с текущими данными
        plugin.getAuthManager().forceCacheUpdate(player);

        // Сохраняем данные игрока ПЕРЕД деактивацией сессии
        plugin.getAuthManager().savePlayerData(player);

        // ИСПРАВЛЕНО: Деактивируем сессию ПЕРЕД отправкой в лобби
        // Чтобы игрок не считался авторизованным в лобби
        plugin.getSessionManager().invalidateSession(username);

        // Теперь отправляем в лобби (игрок уже НЕ авторизован)
        plugin.getLobbyManager().sendToAuthLobby(player);

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