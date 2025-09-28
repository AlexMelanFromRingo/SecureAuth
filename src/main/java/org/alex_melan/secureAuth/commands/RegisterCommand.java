package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.alex_melan.secureAuth.utils.PasswordUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RegisterCommand implements CommandExecutor {

    private final SecureAuthPlugin plugin;

    public RegisterCommand(SecureAuthPlugin plugin) {
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

        // Проверка авторизации
        if (plugin.getSessionManager().isAuthenticated(username)) {
            player.sendMessage("§cВы уже авторизованы!");
            return true;
        }

        // Проверка аргументов
        if (args.length != 2) {
            player.sendMessage("§cИспользование: /register <пароль> <повтор_пароля>");
            return true;
        }

        String password = args[0];
        String confirmPassword = args[1];

        // Проверка совпадения паролей
        if (!password.equals(confirmPassword)) {
            player.sendMessage("§cПароли не совпадают!");
            return true;
        }

        // Валидация пароля
        if (!PasswordUtils.isPasswordValid(password)) {
            player.sendMessage("§cПароль должен содержать:");
            player.sendMessage("§7- От 8 до 32 символов");
            player.sendMessage("§7- Минимум одну заглавную букву");
            player.sendMessage("§7- Минимум одну строчную букву");
            player.sendMessage("§7- Минимум одну цифру");
            return true;
        }

        // Асинхронная регистрация
        plugin.getAuthManager().registerPlayer(username, password, player.getUniqueId())
                .thenAccept(success -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (success) {
                            player.sendMessage("§aРегистрация успешна! Теперь войдите с помощью /login <пароль>");
                        } else {
                            player.sendMessage("§cОшибка регистрации! Возможно, этот ник уже зарегистрирован.");
                        }
                    });
                });

        return true;
    }
}