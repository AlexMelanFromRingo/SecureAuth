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

public class PlayerListener implements Listener {

    private final SecureAuthPlugin plugin;

    public PlayerListener(SecureAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        String ipAddress = player.getAddress().getAddress().getHostAddress();

        // Асинхронная проверка сессии
        plugin.getSessionManager().validateSession(username, ipAddress)
                .thenAccept(validSession -> {

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (validSession) {
                            // Загружаем данные игрока и возвращаем в мир
                            plugin.getAuthManager().loadPlayerData(username)
                                    .thenAccept(data -> {
                                        if (data != null) {
                                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                                plugin.getLobbyManager().returnFromAuthLobby(player);
                                                player.sendMessage("§aСессия восстановлена!");
                                            });
                                        } else {
                                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                                plugin.getLobbyManager().sendToAuthLobby(player);
                                            });
                                        }
                                    });
                        } else {
                            // Отправляем в лобби авторизации
                            plugin.getLobbyManager().sendToAuthLobby(player);
                        }
                    });
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        // Сохраняем данные игрока, если он авторизован
        if (plugin.getSessionManager().isAuthenticated(username)) {
            plugin.getAuthManager().savePlayerData(player);
        }
    }

    // Блокировка команд для неавторизованных игроков
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();

        // Разрешенные команды для неавторизованных
        if (command.startsWith("/login") || command.startsWith("/register") ||
                command.startsWith("/l ") || command.startsWith("/reg ")) {
            return;
        }

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            event.setCancelled(true);
            player.sendMessage("§cДля использования команд необходимо авторизоваться!");
            player.sendMessage("§7Используйте: §e/login <пароль> §7или §e/register <пароль> <повтор>");
        }
    }

    // Блокировка чата для неавторизованных
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            event.setCancelled(true);

            // Синхронно отправляем сообщение
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage("§cДля использования чата необходимо авторизоваться!");
            });
        }
    }

    // Блокировка движения в лобби авторизации
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
            // Разрешаем только поворот головы, но не движение
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {

                event.setTo(event.getFrom());
            }
        }
    }

    // Блокировка взаимодействий
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

    // Блокировка урона в лобби
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    // Блокировка голода в лобби
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            if (!plugin.getSessionManager().isAuthenticated(player.getName())) {
                event.setCancelled(true);
            }
        }
    }

    // Блокировка выбрасывания предметов
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    // Блокировка подбора предметов
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!plugin.getSessionManager().isAuthenticated(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }
}