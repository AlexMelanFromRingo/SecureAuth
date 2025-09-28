package org.alex_melan.secureAuth.commands;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AuthAdminCommand implements CommandExecutor, TabCompleter {

    private final SecureAuthPlugin plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public AuthAdminCommand(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("secureauth.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                handleReload(sender);
                break;

            case "forcelogout":
                handleForceLogout(sender, args);
                break;

            case "sessions":
                handleSessions(sender);
                break;

            case "cleanup":
                handleCleanup(sender);
                break;

            case "stats":
                handleStats(sender);
                break;

            case "info":
                handlePlayerInfo(sender, args);
                break;

            case "version":
                handleVersion(sender);
                break;

            case "debug":
                handleDebug(sender, args);
                break;

            case "help":
                sendHelp(sender);
                break;

            default:
                sender.sendMessage(plugin.getConfigManager().getMessage("unknown-command"));
                break;
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("secureauth.reload")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        sender.sendMessage("§7Перезагрузка конфигурации...");

        plugin.reloadPlugin().thenAccept(success -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (success) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("plugin-reload"));
                } else {
                    sender.sendMessage("§cОшибка перезагрузки конфигурации!");
                }
            });
        });
    }

    private void handleForceLogout(CommandSender sender, String[] args) {
        if (!sender.hasPermission("secureauth.forcelogout")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /secureauth forcelogout <игрок>");
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);

        if (target != null) {
            plugin.getSessionManager().forceLogout(targetName);
            sender.sendMessage(plugin.getConfigManager().getMessage("admin-forcelogout-success", targetName));

            // Логируем действие админа
            plugin.getDatabaseManager().logSecurityAction(
                    targetName,
                    sender.getName(),
                    "ADMIN_FORCE_LOGOUT",
                    true,
                    "Forced logout by " + sender.getName()
            );
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessage("admin-forcelogout-offline"));
        }
    }

    private void handleSessions(CommandSender sender) {
        if (!sender.hasPermission("secureauth.sessions")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        CompletableFuture.runAsync(() -> {
            plugin.getDatabaseManager().getActiveSessionsCount().thenAccept(dbCount -> {
                int onlineAuth;
                int totalOnline = Bukkit.getOnlinePlayers().size();

                onlineAuth = (int) Bukkit.getOnlinePlayers().stream().filter(player -> plugin.getSessionManager().isAuthenticated(player.getName())).count();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§6=== Активные сессии ===");
                    sender.sendMessage("§7Игроков онлайн: §e" + totalOnline);
                    sender.sendMessage("§7Авторизованных: §a" + onlineAuth);
                    sender.sendMessage("§7Неавторизованных: §c" + (totalOnline - onlineAuth));
                    sender.sendMessage("§7Сессий в БД: §e" + dbCount);
                    sender.sendMessage("§7В памяти: §e" + plugin.getSessionManager().getActiveSessionsCount());
                    sender.sendMessage("§6========================");
                });
            });
        });
    }

    private void handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("secureauth.cleanup")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        sender.sendMessage("§7Запуск очистки просроченных данных...");

        CompletableFuture.runAsync(() -> {
            // Очищаем просроченные сессии
            plugin.getSessionManager().cleanExpiredSessions();

            // Очищаем неудачные попытки входа
            plugin.getSessionManager().cleanupFailedAttempts();

            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(plugin.getConfigManager().getMessage("admin-cleanup-success"));
            });
        });
    }

    private void handleStats(CommandSender sender) {
        if (!sender.hasPermission("secureauth.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        CompletableFuture.runAsync(() -> {
            plugin.getDatabaseManager().getActiveSessionsCount().thenAccept(sessionCount -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    long uptime = System.currentTimeMillis() - plugin.getServer().getWorlds().get(0).getFullTime();

                    sender.sendMessage("§6=== Статистика SecureAuth ===");
                    sender.sendMessage("§7Версия плагина: §e" + plugin.getDescription().getVersion());
                    sender.sendMessage("§7Время работы: §e" + formatUptime(uptime));
                    sender.sendMessage("§7Игроков онлайн: §e" + Bukkit.getOnlinePlayers().size());
                    sender.sendMessage("§7Активных сессий: §e" + sessionCount);
                    sender.sendMessage("§7TTL сессий: §e" + (plugin.getConfigManager().getSessionTTL() / 1000 / 60 / 60) + "ч");
                    sender.sendMessage("§7Проверка IP: §e" + (plugin.getConfigManager().isIpCheckEnabled() ? "включена" : "отключена"));
                    sender.sendMessage("§7Мир лобби: §e" + plugin.getConfigManager().getLobbyWorld());
                    sender.sendMessage("§7Полная инициализация: §e" + (plugin.isFullyInitialized() ? "да" : "нет"));
                    sender.sendMessage("§6===============================");
                });
            });
        });
    }

    private void handlePlayerInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("secureauth.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /secureauth info <игрок>");
            return;
        }

        String playerName = args[1];

        CompletableFuture.runAsync(() -> {
            plugin.getDatabaseManager().getPlayerData(playerName).thenAccept(data -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (data != null) {
                        boolean isOnline = Bukkit.getPlayerExact(playerName) != null;
                        boolean isAuthenticated = plugin.getSessionManager().isAuthenticated(playerName);

                        sender.sendMessage("§6=== Информация об игроке " + playerName + " ===");
                        sender.sendMessage("§7Зарегистрирован: §a" + formatDate(data.getRegistrationDate()));
                        sender.sendMessage("§7Последний вход: §e" + formatDate(data.getLastLogin()));
                        sender.sendMessage("§7Последний IP: §e" + (data.getLastIp() != null ? data.getLastIp() : "неизвестен"));
                        sender.sendMessage("§7Онлайн: §e" + (isOnline ? "да" : "нет"));
                        sender.sendMessage("§7Авторизован: §e" + (isAuthenticated ? "да" : "нет"));
                        sender.sendMessage("§7Мир: §e" + data.getWorldName());
                        sender.sendMessage("§7Координаты: §e" + Math.round(data.getX()) + ", " + Math.round(data.getY()) + ", " + Math.round(data.getZ()));
                        sender.sendMessage("§7Premium UUID: §e" + (data.getPremiumUuid() != null ? data.getPremiumUuid() : "не привязан"));
                        sender.sendMessage("§7Cracked UUID: §e" + data.getCrackedUuid());
                        sender.sendMessage("§6========================================");
                    } else {
                        sender.sendMessage("§cИгрок §e" + playerName + "§c не зарегистрирован!");
                    }
                });
            });
        });
    }

    private void handleVersion(CommandSender sender) {
        sender.sendMessage("§6=== SecureAuth ===");
        sender.sendMessage("§7Версия: §e" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Автор: §e" + String.join(", ", plugin.getDescription().getAuthors()));
        sender.sendMessage("§7Сайт: §e" + plugin.getDescription().getWebsite());
        sender.sendMessage("§7API версия: §e" + plugin.getDescription().getAPIVersion());
        sender.sendMessage("§6==================");
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("secureauth.admin")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /secureauth debug <on|off|info>");
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "on":
                plugin.getConfigManager().setValue("debug.enabled", true);
                sender.sendMessage("§aРежим отладки включен!");
                break;

            case "off":
                plugin.getConfigManager().setValue("debug.enabled", false);
                sender.sendMessage("§cРежим отладки отключен!");
                break;

            case "info":
                boolean enabled = plugin.getConfigManager().getConfig().getBoolean("debug.enabled", false);
                sender.sendMessage("§7Режим отладки: §e" + (enabled ? "включен" : "отключен"));
                break;

            default:
                sender.sendMessage("§cИспользование: /secureauth debug <on|off|info>");
                break;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== SecureAuth Admin v" + plugin.getDescription().getVersion() + " ===");
        sender.sendMessage("§e/secureauth reload §7- перезагрузить конфигурацию");
        sender.sendMessage("§e/secureauth forcelogout <игрок> §7- принудительный выход");
        sender.sendMessage("§e/secureauth sessions §7- показать активные сессии");
        sender.sendMessage("§e/secureauth cleanup §7- очистить просроченные данные");
        sender.sendMessage("§e/secureauth stats §7- статистика плагина");
        sender.sendMessage("§e/secureauth info <игрок> §7- информация об игроке");
        sender.sendMessage("§e/secureauth version §7- информация о версии");
        sender.sendMessage("§e/secureauth debug <on|off|info> §7- режим отладки");
        sender.sendMessage("§6===============================================");
    }

    private String formatDate(long timestamp) {
        if (timestamp == 0) {
            return "никогда";
        }
        return dateFormat.format(new Date(timestamp));
    }

    private String formatUptime(long uptime) {
        long seconds = uptime / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "д " + (hours % 24) + "ч " + (minutes % 60) + "м";
        } else if (hours > 0) {
            return hours + "ч " + (minutes % 60) + "м " + (seconds % 60) + "с";
        } else if (minutes > 0) {
            return minutes + "м " + (seconds % 60) + "с";
        } else {
            return seconds + "с";
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("secureauth.admin")) {
            return completions;
        }

        if (args.length == 1) {
            // Первый аргумент - подкоманды
            List<String> subCommands = Arrays.asList(
                    "reload", "forcelogout", "sessions", "cleanup",
                    "stats", "info", "version", "debug", "help"
            );

            String partial = args[0].toLowerCase();
            for (String sub : subCommands) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();

            if ("forcelogout".equals(subCommand) || "info".equals(subCommand)) {
                // Автодополнение имен игроков
                String partial = args[1].toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(partial)) {
                        completions.add(player.getName());
                    }
                }
            } else if ("debug".equals(subCommand)) {
                // Автодополнение для debug команды
                List<String> debugArgs = Arrays.asList("on", "off", "info");
                String partial = args[1].toLowerCase();
                for (String arg : debugArgs) {
                    if (arg.startsWith(partial)) {
                        completions.add(arg);
                    }
                }
            }
        }

        return completions;
    }
}