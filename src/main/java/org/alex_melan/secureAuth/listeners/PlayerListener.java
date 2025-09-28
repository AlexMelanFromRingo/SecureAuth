package org.alex_melan.secureAuth.listeners;

import org.alex_melan.secureAuth.SecureAuthPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Arrays;
import java.util.List;

public class PlayerListener implements Listener {

    private final SecureAuthPlugin plugin;

    // Разрешенные команды для неавторизованных игроков
    private final List<String> allowedCommands = Arrays.asList(
            "/login", "/l", "/auth", "/log",
            "/register", "/reg", "/signup"
    );

    // Команды которые требуют авторизации (для авторизованных игроков)
    private final List<String> authRequiredCommands = Arrays.asList(
            "/logout", "/exit", "/quit"
    );

    public PlayerListener(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        plugin.getLogger().info("Игрок " + username + " подключился с IP " + ipAddress);

        // Проверяем полную инициализацию плагина
        if (!plugin.isFullyInitialized()) {
            player.sendMessage("§cПлагин авторизации еще загружается. Подождите немного...");

            // Отправляем в лобби после небольшой задержки
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getLobbyManager().sendToAuthLobby(player);
                }
            }, 60L); // 3 секунды
            return;
        }

        // Асинхронная проверка сессии
        plugin.getSessionManager().validateSession(username, ipAddress)
                .thenAccept(validSession -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (validSession) {
                            handleValidSession(player);
                        } else {
                            handleInvalidSession(player);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка проверки сессии для игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            plugin.getLobbyManager().sendToAuthLobby(player);
                        }
                    });
                    return null;
                });
    }

    private void handleValidSession(Player player) {
        String username = player.getName();

        // ИСПРАВЛЕНО: Сначала помечаем игрока как авторизованного
        plugin.getSessionManager().markAsAuthenticated(username);

        // Загружаем данные игрока и возвращаем в мир
        plugin.getAuthManager().loadPlayerData(username)
                .thenAccept(data -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (data != null) {
                            plugin.getLobbyManager().returnFromAuthLobby(player);
                            player.sendMessage(plugin.getConfigManager().getMessage("login-session-restored"));
                            plugin.getLogger().info("Сессия игрока " + username + " восстановлена");
                        } else {
                            plugin.getLogger().warning("Не удалось загрузить данные игрока " + username + " при восстановлении сессии");
                            plugin.getLobbyManager().sendToAuthLobby(player);
                        }
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка загрузки данных при восстановлении сессии для игрока " + username + ": " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            plugin.getLobbyManager().sendToAuthLobby(player);
                        }
                    });
                    return null;
                });
    }

    private void handleInvalidSession(Player player) {
        String username = player.getName();

        // Проверяем смену IP
        plugin.getAuthManager().loadPlayerData(username)
                .thenAccept(data -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (data != null && data.getLastIp() != null) {
                            String currentIp = player.getAddress().getAddress().getHostAddress();
                            if (!data.getLastIp().equals(currentIp)) {
                                player.sendMessage(plugin.getConfigManager().getMessage("login-ip-changed"));
                                plugin.getLogger().info("Смена IP для игрока " + username + ": " + data.getLastIp() + " -> " + currentIp);
                            }
                        }

                        // Отправляем в лобби авторизации
                        plugin.getLobbyManager().sendToAuthLobby(player);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Ошибка проверки данных игрока при входе: " + ex.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            plugin.getLobbyManager().sendToAuthLobby(player);
                        }
                    });
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.LOWEST)  // ИСПРАВЛЕНО: Изменен приоритет на LOWEST чтобы сработать раньше всех
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        plugin.getLogger().info("Игрок " + username + " отключается, сохраняем данные...");

        // ИСПРАВЛЕНО: Принудительно сохраняем актуальные данные игрока
        if (plugin.getSessionManager().isAuthenticated(username)) {
            // Обновляем кеш с текущими данными прямо перед сохранением
            plugin.getAuthManager().forceCacheUpdate(player);

            // Сохраняем обновленные данные
            plugin.getAuthManager().savePlayerData(player);

            plugin.getLogger().info("Данные игрока " + username + " сохранены при выходе");
        }

        // Удаляем из кеша через некоторое время (на случай быстрого переподключения)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Проверяем что игрок не переподключился
            if (plugin.getServer().getPlayerExact(username) == null) {
                plugin.getAuthManager().removeCachedData(username);
                plugin.getLogger().info("Кеш игрока " + username + " очищен");
            }
        }, 20L * 30); // 30 секунд
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        boolean isAuthenticated = plugin.getSessionManager().isAuthenticated(player.getName());

        // Разрешенные команды для неавторизованных
        boolean isAllowedForUnauth = allowedCommands.stream().anyMatch(cmd -> command.startsWith(cmd + " ") || command.equals(cmd));

        // Команды только для авторизованных
        boolean isAuthOnlyCommand = authRequiredCommands.stream().anyMatch(cmd -> command.startsWith(cmd + " ") || command.equals(cmd));

        // Если игрок не авторизован
        if (!isAuthenticated) {
            // Разрешаем команды для неавторизованных
            if (isAllowedForUnauth) {
                return;
            }

            // Блокируем команды для авторизованных
            if (isAuthOnlyCommand) {
                event.setCancelled(true);
                player.sendMessage("§cСначала авторизуйтесь!");
                return;
            }

            // Блокируем все остальные команды
            event.setCancelled(true);
            player.sendMessage(plugin.getConfigManager().getMessage("command-blocked"));

            // Напоминаем о командах авторизации
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getDatabaseManager().isPlayerRegistered(player.getName())
                            .thenAccept(registered -> {
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline()) {
                                        if (registered) {
                                            player.sendMessage("§7Используйте: §e/login <пароль>");
                                        } else {
                                            player.sendMessage("§7Используйте: §e/register <пароль> <повтор>");
                                        }
                                    }
                                });
                            });
                }
            }, 10L);
        } else {
            // Игрок авторизован - блокируем команды авторизации
            if (isAllowedForUnauth) {
                event.setCancelled(true);
                player.sendMessage("§cВы уже авторизованы! Используйте /logout для выхода.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            event.setCancelled(true);

            // Синхронно отправляем сообщение
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.getConfigManager().getMessage("chat-blocked"));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            // Разрешаем только поворот головы, но не движение
            if (hasPlayerMoved(event)) {
                event.setTo(event.getFrom());
            }
        } else {
            // ИСПРАВЛЕНО: Обновляем позицию в кеше для авторизованного игрока
            if (hasPlayerMoved(event)) {
                plugin.getAuthManager().updatePlayerPosition(player);
            }
        }
    }

    private boolean hasPlayerMoved(PlayerMoveEvent event) {
        return event.getFrom().getX() != event.getTo().getX() ||
                event.getFrom().getY() != event.getTo().getY() ||
                event.getFrom().getZ() != event.getTo().getZ();
    }

    // Блокировка взаимодействий для неавторизованных игроков
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    // Дополнительные события для улучшенной безопасности
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    // События для отслеживания изменений игрока (авторизованных)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        if (plugin.getSessionManager().isAuthenticated(player.getName())) {
            // Обновляем данные в кеше
            plugin.getAuthManager().handleGameModeChange(player, event.getNewGameMode());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (plugin.getSessionManager().isAuthenticated(player.getName())) {
            // Обновляем позицию в кеше если это не телепортация в лобби
            if (event.getTo() != null &&
                    !event.getTo().getWorld().equals(plugin.getLobbyManager().getAuthWorld())) {
                plugin.getAuthManager().handlePlayerTeleport(player, event.getTo());
            }
        }
    }

    // Обработка потенциальных попыток обхода
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            // В лобби авторизации запрещаем полет
            if (plugin.getLobbyManager().isInAuthWorld(player)) {
                event.setCancelled(true);
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        // Не блокируем приседание, но можно добавить логирование подозрительной активности
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            // Логирование активности неавторизованного игрока (опционально)
        }
    }

    // Предотвращение выхода из лобби авторизации через команды
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleportCommand(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            // Если игрок не авторизован и пытается телепортироваться не в лобби
            if (event.getTo() != null &&
                    !event.getTo().getWorld().equals(plugin.getLobbyManager().getAuthWorld())) {

                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("auth-required"));
            }
        }
    }
}