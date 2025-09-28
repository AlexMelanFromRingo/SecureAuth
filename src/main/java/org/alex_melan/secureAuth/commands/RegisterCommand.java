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
            player.sendMessage(plugin.getConfigManager().getMessage("register-already-registered"));
            return true;
        }

        // Проверка аргументов
        if (args.length != 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("register-usage"));
            showPasswordRequirements(player);
            return true;
        }

        String password = args[0];
        String confirmPassword = args[1];

        // Проверка совпадения паролей
        if (!password.equals(confirmPassword)) {
            player.sendMessage(plugin.getConfigManager().getMessage("register-passwords-not-match"));
            return true;
        }

        // Валидация пароля
        if (!isPasswordValid(player, password)) {
            return true;
        }

        // Проверка блокировки IP (применяется и к регистрации)
        if (plugin.getSessionManager().isLoginBlocked(ipAddress)) {
            long remainingTime = plugin.getSessionManager().getRemainingBlockTime(ipAddress);
            int remainingMinutes = (int) (remainingTime / 1000 / 60) + 1;

            player.sendMessage("§cСлишком много попыток с вашего IP! Попробуйте через " +
                    remainingMinutes + " минут.");
            return true;
        }

        // Проверка существования аккаунта
        plugin.getDatabaseManager().isPlayerRegistered(username)
                .thenAccept(alreadyRegistered -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (alreadyRegistered) {
                            player.sendMessage(plugin.getConfigManager().getMessage("register-already-registered"));
                            return;
                        }

                        // Асинхронная регистрация
                        registerPlayer(player, password);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка проверки регистрации для игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-database"));
                        }
                    });
                    return null;
                });

        return true;
    }

    private boolean isPasswordValid(Player player, String password) {
        // Базовые проверки длины
        if (password.length() < 1 || password.length() > 100) {
            player.sendMessage("§cНекорректная длина пароля!");
            return false;
        }

        // Проверка сложности пароля (если включена)
        if (plugin.getConfigManager().isPasswordComplexityEnforced()) {
            if (!PasswordUtils.isPasswordValid(password)) {
                int minLength = plugin.getConfigManager().getConfig().getInt("security.min-password-length", 8);
                int maxLength = plugin.getConfigManager().getConfig().getInt("security.max-password-length", 32);

                player.sendMessage("§cПароль слишком слабый! Требования:");
                player.sendMessage("§7• От §e" + minLength + "§7 до §e" + maxLength + "§7 символов");
                player.sendMessage("§7• Минимум одна заглавная буква");
                player.sendMessage("§7• Минимум одна строчная буква");
                player.sendMessage("§7• Минимум одна цифра");

                showPasswordRequirements(player);
                return false;
            }
        } else {
            // Минимальная проверка длины даже при отключенной сложности
            int minLength = plugin.getConfigManager().getConfig().getInt("security.min-password-length", 8);
            int maxLength = plugin.getConfigManager().getConfig().getInt("security.max-password-length", 32);

            if (password.length() < minLength || password.length() > maxLength) {
                player.sendMessage("§cПароль должен содержать от " + minLength + " до " + maxLength + " символов!");
                return false;
            }
        }

        // Проверка на простые пароли
        if (isCommonPassword(password)) {
            player.sendMessage("§cЭтот пароль слишком простой! Выберите более сложный пароль.");
            return false;
        }

        // Проверка что пароль не совпадает с никнеймом
        if (password.toLowerCase().contains(player.getName().toLowerCase()) ||
                player.getName().toLowerCase().contains(password.toLowerCase())) {
            player.sendMessage("§cПароль не должен содержать ваш никнейм!");
            return false;
        }

        return true;
    }

    private boolean isCommonPassword(String password) {
        String[] commonPasswords = {
                "password", "123456", "123456789", "qwerty", "abc123",
                "password123", "admin", "root", "guest", "user",
                "12345", "1234567", "12345678", "qwerty123", "letmein",
                "welcome", "monkey", "dragon", "master", "hello"
        };

        String lowerPassword = password.toLowerCase();
        for (String common : commonPasswords) {
            if (lowerPassword.equals(common)) {
                return true;
            }
        }

        return false;
    }

    private void showPasswordRequirements(Player player) {
        int minLength = plugin.getConfigManager().getConfig().getInt("security.min-password-length", 8);
        int maxLength = plugin.getConfigManager().getConfig().getInt("security.max-password-length", 32);

        if (plugin.getConfigManager().isPasswordComplexityEnforced()) {
            player.sendMessage("§7§lТребования к паролю:");
            player.sendMessage("§7• От §e" + minLength + "§7 до §e" + maxLength + "§7 символов");
            player.sendMessage("§7• Минимум одна заглавная буква (A-Z)");
            player.sendMessage("§7• Минимум одна строчная буква (a-z)");
            player.sendMessage("§7• Минимум одна цифра (0-9)");
            player.sendMessage("§7• Разрешенные спецсимволы:");
            player.sendMessage("§7  @$!%*?&.,_-+=~`|[]{}():;\"'<>/\\^#");
            player.sendMessage("§7• Не должен содержать ваш никнейм");
        } else {
            player.sendMessage("§7Пароль должен содержать от §e" + minLength + "§7 до §e" + maxLength + "§7 символов");
            player.sendMessage("§7Разрешены: буквы, цифры и символы @$!%*?&.,_-+=~`|[]{}():;\"'<>/\\^#");
        }
    }

    private void registerPlayer(Player player, String password) {
        String username = player.getName();

        plugin.getAuthManager().registerPlayer(username, password, player.getUniqueId())
                .thenAccept(success -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (success) {
                            handleSuccessfulRegistration(player);
                        } else {
                            handleFailedRegistration(player);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка регистрации игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getConfigManager().getMessage("error-database"));
                        }
                    });
                    return null;
                });
    }

    private void handleSuccessfulRegistration(Player player) {
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        // Отправляем сообщение об успешной регистрации
        player.sendMessage(plugin.getConfigManager().getMessage("register-success"));

        // Логируем регистрацию
        if (plugin.getConfigManager().isLogRegistrations()) {
            plugin.getDatabaseManager().logSecurityAction(
                    username,
                    ipAddress,
                    "REGISTRATION",
                    true,
                    "User registered successfully"
            );
        }

        plugin.getLogger().info("Новый пользователь зарегистрирован: " + username + " с IP " + ipAddress);

        // ДОБАВЛЕНО: Автоматическая авторизация после регистрации
        player.sendMessage("§aАвтоматический вход в аккаунт...");

        // Создаем сессию
        plugin.getSessionManager().createSession(username, ipAddress)
                .thenAccept(sessionCreated -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (sessionCreated) {
                            // Загружаем данные игрока и возвращаем в мир
                            plugin.getAuthManager().loadPlayerData(username)
                                    .thenAccept(data -> {
                                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                                            if (!player.isOnline()) {
                                                return;
                                            }

                                            if (data != null) {
                                                plugin.getLobbyManager().returnFromAuthLobby(player);
                                                player.sendMessage("§aДобро пожаловать на сервер! Вы автоматически авторизованы.");

                                                // Логируем автоматический вход
                                                plugin.getDatabaseManager().logSecurityAction(
                                                        username,
                                                        ipAddress,
                                                        "AUTO_LOGIN",
                                                        true,
                                                        "Automatic login after registration"
                                                );
                                            } else {
                                                player.sendMessage("§cОшибка загрузки данных!");
                                            }
                                        });
                                    });
                        } else {
                            player.sendMessage("§cОшибка автоматического входа! Используйте /login <пароль>");
                        }
                    });
                });
    }

    private void handleFailedRegistration(Player player) {
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        player.sendMessage(plugin.getConfigManager().getMessage("register-error"));

        // Логируем неудачную регистрацию
        plugin.getDatabaseManager().logSecurityAction(
                username,
                ipAddress,
                "REGISTRATION",
                false,
                "Registration failed - possibly duplicate username"
        );

        plugin.getLogger().warning("Неудачная попытка регистрации: " + username + " с IP " + ipAddress);
    }
}