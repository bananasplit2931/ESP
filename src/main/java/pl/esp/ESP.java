package pl.esp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;

public final class ESP extends JavaPlugin implements Listener {

    private static final int MAX_ATTEMPTS = 3;
    private static final int TIMEOUT_SECS = 30;

    private final Set<UUID> authenticated = new HashSet<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();
    private final Set<UUID> settingPin = new HashSet<>();
    private final Map<UUID, String> pendingPin = new HashMap<>();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "pins.yml");
        getDataFolder().mkdirs();

        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException ex) {
                getLogger().log(Level.SEVERE, "Could not create pins.yml", ex);
            }
        }

        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ESP enabled.");
    }

    @Override
    public void onDisable() {
        timeoutTasks.values().forEach(BukkitTask::cancel);
        timeoutTasks.clear();
        Bukkit.getOnlinePlayers().forEach(this::unlock);
        getLogger().info("ESP disabled.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lock(player);
        attempts.put(player.getUniqueId(), 0);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            startTimeout(player);
            sendPinPrompt(player);
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
        unlock(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isLocked(event.getPlayer())) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isLocked(player))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isLocked(player))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!isLocked(event.getPlayer())) return;
        String cmd = event.getMessage().toLowerCase();
        if (!cmd.startsWith("/pin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c[ESP] You must authenticate first. Use /pin <4-digit PIN>.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isLocked(player))
            event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("pin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use /pin.");
                return true;
            }

            if (!isLocked(player)) {
                player.sendMessage("§e[ESP] You are already logged in.");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage("§c[ESP] Usage: /pin <4-digit PIN>");
                return true;
            }

            String pin = args[0];
            if (!pin.matches("\\d{4}")) {
                player.sendMessage("§c[ESP] PIN must be exactly 4 digits (0–9).");
                return true;
            }

            handlePin(player, pin);
            return true;
        }

        if (name.equals("espadmin")) {
            if (!sender.isOp()) {
                sender.sendMessage("§cYou do not have permission to use this command.");
                return true;
            }

            if (args.length < 2 || !args[0].equalsIgnoreCase("reset")) {
                sender.sendMessage("§eUsage: /espadmin reset <player>");
                return true;
            }

            String targetName = args[1];
            Player onlineTarget = Bukkit.getPlayer(targetName);
            UUID targetUuid = null;

            if (onlineTarget != null) {
                targetUuid = onlineTarget.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                var offlineTarget = Bukkit.getOfflinePlayer(targetName);
                if (offlineTarget.hasPlayedBefore()) targetUuid = offlineTarget.getUniqueId();
            }

            if (targetUuid == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            data.set(targetUuid.toString(), null);
            saveData();
            authenticated.remove(targetUuid);
            attempts.remove(targetUuid);
            settingPin.remove(targetUuid);
            pendingPin.remove(targetUuid);
            cancelTimeout(targetUuid);

            if (onlineTarget != null && onlineTarget.isOnline()) {
                lock(onlineTarget);
                attempts.put(targetUuid, 0);
                startTimeout(onlineTarget);
                sendPinPrompt(onlineTarget);
            }

            sender.sendMessage("§a[ESP] PIN for §f" + targetName + " §ahas been reset.");
            return true;
        }

        return false;
    }

    private void sendPinPrompt(Player player) {
        if (!hasPin(player)) {
            settingPin.add(player.getUniqueId());
            player.sendMessage("");
            player.sendMessage("§e§l[ESP] §eWelcome! You don't have a PIN yet.");
            player.sendMessage("§7Type §f/pin <4 digits> §7to set your PIN.");
            player.sendMessage("");
        } else {
            player.sendMessage("");
            player.sendMessage("§e§l[ESP] §ePlease log in.");
            player.sendMessage("§7Type §f/pin <4 digits> §7to authenticate.");
            player.sendMessage("");
        }
    }

    private void handlePin(Player player, String pin) {
        UUID uuid = player.getUniqueId();

        if (!hasPin(player)) {
            if (!pendingPin.containsKey(uuid)) {
                pendingPin.put(uuid, pin);
                player.sendMessage("§e[ESP] PIN received. Type §f/pin " + pin + " §eagain to confirm.");
            } else {
                String first = pendingPin.remove(uuid);
                if (first.equals(pin)) {
                    savePin(player, pin);
                    settingPin.remove(uuid);
                    login(player);
                    player.sendMessage("§a[ESP] ✔ PIN set! You are now logged in.");
                    player.sendMessage("§7Your PIN is: §f" + pin + " §7— keep it safe!");
                } else {
                    player.sendMessage("§c[ESP] ✘ PINs did not match. Please start over.");
                    player.sendMessage("§7Type §f/pin <4 digits> §7to choose your PIN.");
                }
            }
        } else {
            if (checkPin(player, pin)) {
                login(player);
                player.sendMessage("§a[ESP] ✔ Successfully logged in!");
            } else {
                int tries = attempts.getOrDefault(uuid, 0) + 1;
                attempts.put(uuid, tries);
                int remaining = MAX_ATTEMPTS - tries;

                if (tries >= MAX_ATTEMPTS) {
                    cancelTimeout(uuid);
                    unlock(player);
                    player.kickPlayer("§cToo many incorrect PIN attempts.\n§7Please reconnect and try again.");
                } else {
                    player.sendMessage("§c[ESP] ✘ Wrong PIN! Attempts remaining: §f" + remaining);
                }
            }
        }
    }

    private void startTimeout(Player player) {
        UUID uuid = player.getUniqueId();
        cancelTimeout(uuid);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isLocked(player))
                player.sendMessage("§e[ESP] ⚠ " + (TIMEOUT_SECS / 2) + " seconds remaining to enter your PIN.");
        }, (long) (TIMEOUT_SECS / 2) * 20L);

        BukkitTask kickTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            if (isLocked(player)) {
                pendingPin.remove(uuid);
                settingPin.remove(uuid);
                unlock(player);
                player.kickPlayer("§cLogin timed out.\n§7Please reconnect and try again.");
            }
        }, (long) TIMEOUT_SECS * 20L);

        timeoutTasks.put(uuid, kickTask);
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = timeoutTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private boolean isLocked(Player player) {
        return !authenticated.contains(player.getUniqueId());
    }

    private void lock(Player player) {
        player.setWalkSpeed(0f);
        player.setGameMode(GameMode.ADVENTURE);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 10, false, false));
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
    }

    private void unlock(Player player) {
        player.setWalkSpeed(0.2f);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        if (player.getGameMode() == GameMode.ADVENTURE)
            player.setGameMode(GameMode.SURVIVAL);
    }

    private void login(Player player) {
        cancelTimeout(player.getUniqueId());
        attempts.remove(player.getUniqueId());
        pendingPin.remove(player.getUniqueId());
        settingPin.remove(player.getUniqueId());
        authenticated.add(player.getUniqueId());
        unlock(player);
    }

    private void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        cancelTimeout(uuid);
        attempts.remove(uuid);
        pendingPin.remove(uuid);
        settingPin.remove(uuid);
        authenticated.remove(uuid);
    }

    private boolean hasPin(Player player) {
        return data.contains(player.getUniqueId().toString());
    }

    private void savePin(Player player, String pin) {
        data.set(player.getUniqueId().toString(), sha256(pin));
        saveData();
    }

    private boolean checkPin(Player player, String pin) {
        String stored = data.getString(player.getUniqueId().toString());
        return stored != null && stored.equals(sha256(pin));
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            getLogger().log(Level.SEVERE, "SHA-256 not available, storing PIN in plain text!", ex);
            return input;
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save pins.yml", ex);
        }
    }
}
