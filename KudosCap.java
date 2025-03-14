package com.decre.kudoscap;

import com.gmail.nossr50.api.ExperienceAPI;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class KudosCap extends JavaPlugin implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private FileConfiguration config;
    private boolean mcMMOEnabled = false;
    private final Map<UUID, Boolean> playerReady = new HashMap<>();
    private final Map<UUID, Long> lastBlockedTime = new HashMap<>();
    private boolean verboseLogging;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        mcMMOEnabled = getServer().getPluginManager().getPlugin("McMMO") != null;
        if (!mcMMOEnabled) {
            getLogger().severe("McMMO not found! KudosCap requires McMMO to function. Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KudosCap has been enabled!");
        if (getServer().getPluginManager().getPlugin("MineXFarmRegen") != null) {
            getLogger().info("Detected MineXFarmRegen. Adjusting block break handling.");
        }

        for (Player player : getServer().getOnlinePlayers()) {
            checkPlayerReady(player);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("KudosCap has been disabled!");
        playerReady.clear();
        lastBlockedTime.clear();
    }

    private void loadConfig() {
        config = getConfig();
        verboseLogging = config.getBoolean("verbose-logging", false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("kudoscap")) {
            if (args.length == 0) {
                sender.sendMessage("§eUsage: /kudoscap [reload|bypass]");
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("kudoscap.reload")) {
                    sender.sendMessage("§cYou don't have permission to reload KudosCap!");
                    return true;
                }
                reloadConfig();
                loadConfig();
                sender.sendMessage("§aKudosCap config reloaded!");
                getLogger().info("Config reloaded by " + sender.getName());
                return true;
            }

            if (args[0].equalsIgnoreCase("bypass")) {
                sender.sendMessage("§eThis command is informational only. Use LuckPerms to toggle bypass with 'kudoscap.bypass' permission.");
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    boolean hasBypass = player.hasPermission("kudoscap.bypass");
                    sender.sendMessage("§eYour bypass status: " + (hasBypass ? "§aEnabled" : "§cDisabled"));
                    getLogger().info(player.getName() + " checked bypass status: " + (hasBypass ? "enabled" : "disabled"));
                }
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        getLogger().info("Player " + player.getName() + " joined, checking McMMO readiness...");
        checkPlayerReady(player);
    }

    @SuppressWarnings("deprecation") // getLevel is deprecated in McMMO 2.2.031
    private void checkPlayerReady(Player player) {
        UUID playerId = player.getUniqueId();
        playerReady.put(playerId, false);
        new BukkitRunnable() {
            int attempts = 0;
            @Override
            public void run() {
                attempts++;
                try {
                    int level = ExperienceAPI.getLevel(player, "Mining");
                    playerReady.put(playerId, true);
                    getLogger().info("Player " + player.getName() + " is ready with mining level " + level);
                    cancel();
                } catch (Exception e) {
                    if (attempts % 5 == 0) {
                        getLogger().warning("McMMO data not ready for " + player.getName() + " (attempt " + attempts + ")");
                    }
                    if (!player.isOnline() || attempts >= 100) {
                        getLogger().warning("Stopped checking readiness for " + player.getName() + " - " + (player.isOnline() ? "timeout" : "disconnected"));
                        cancel();
                    }
                }
            }
        }.runTaskTimer(this, 0L, 4L); // Every 4 ticks (~0.2s)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!mcMMOEnabled) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Block block = event.getBlock();
        Material blockType = block.getType();
        if (verboseLogging) {
            getLogger().info("Block break attempt by " + player.getName() + " at " + block.getLocation() + " (" + blockType + ")");
        }

        String prefix = config.getString("messages.prefix", "<gold><bold>« <yellow><bold>Kudos <gold><bold>» ");
        Component earlyMsg = MINI_MESSAGE.deserialize(prefix + "<red>Please wait, McMMO data is still loading!");

        if (!playerReady.getOrDefault(playerId, false)) {
            getLogger().info("Blocking early break for " + player.getName() + " at " + block.getLocation() + " - McMMO data not ready");
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            player.sendMessage(earlyMsg);
            lastBlockedTime.put(playerId, System.currentTimeMillis());
            cleanupDrops(block);
            return;
        }

        int miningLevel;
        try {
            @SuppressWarnings("deprecation")
            int level = ExperienceAPI.getLevel(player, "Mining");
            miningLevel = level;
        } catch (Exception e) {
            getLogger().warning("Failed to get McMMO mining level for " + player.getName() + ": " + e.getMessage());
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            return;
        }

        int diamondLevel = config.getInt("restrictions.diamond-level", 35);
        int debrisLevel = config.getInt("restrictions.debris-level", 50);
        int spawnerLevel = config.getInt("restrictions.spawner-level", 100);
        Component diamondMsg = MINI_MESSAGE.deserialize(prefix + config.getString("messages.diamond-restricted", "<red>You need Mining level <diamond-level> to mine diamonds!").replace("<diamond-level>", String.valueOf(diamondLevel)));
        Component debrisMsg = MINI_MESSAGE.deserialize(prefix + config.getString("messages.debris-restricted", "<red>You need Mining level <debris-level> to mine ancient debris!").replace("<debris-level>", String.valueOf(debrisLevel)));
        Component spawnerMsg = MINI_MESSAGE.deserialize(prefix + config.getString("messages.spawner-restricted", "<red>You need Mining level <spawner-level> and Silk Touch to mine spawners!").replace("<spawner-level>", String.valueOf(spawnerLevel)));

        boolean hasBypass = player.hasPermission("kudoscap.bypass");
        if (verboseLogging) {
            getLogger().info("Checking bypass for " + player.getName() + ": " + (hasBypass ? "allowed" : "denied"));
        }
        if (hasBypass) {
            return;
        }

        boolean restricted = false;
        if ((blockType == Material.DIAMOND_ORE || blockType == Material.DEEPSLATE_DIAMOND_ORE) && miningLevel < diamondLevel) {
            getLogger().info("Cancelling diamond ore break for " + player.getName() + " at " + block.getLocation() + "; Level: " + miningLevel);
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            lastBlockedTime.put(playerId, System.currentTimeMillis());
            player.sendMessage(diamondMsg);
            restricted = true;
        } else if (blockType == Material.ANCIENT_DEBRIS && miningLevel < debrisLevel) {
            getLogger().info("Cancelling ancient debris break for " + player.getName() + " at " + block.getLocation() + "; Level: " + miningLevel);
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
            lastBlockedTime.put(playerId, System.currentTimeMillis());
            player.sendMessage(debrisMsg);
            restricted = true;
        } else if (blockType == Material.SPAWNER) {
            boolean hasSilkTouch = player.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH);
            if (miningLevel < spawnerLevel || !hasSilkTouch) {
                getLogger().info("Cancelling spawner break for " + player.getName() + " at " + block.getLocation() + "; Level: " + miningLevel + ", Silk Touch: " + hasSilkTouch);
                event.setCancelled(true);
                event.setDropItems(false);
                event.setExpToDrop(0);
                lastBlockedTime.put(playerId, System.currentTimeMillis());
                player.sendMessage(spawnerMsg);
                restricted = true;
            }
        }

        if (restricted) {
            cleanupDrops(block);
        }
    }

    private void cleanupDrops(Block block) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Item item : block.getWorld().getNearbyEntities(block.getLocation(), 2, 2, 2, entity -> entity instanceof Item).stream()
                        .filter(entity -> entity instanceof Item)
                        .map(entity -> (Item) entity)
                        .toList()) {
                    Material itemType = item.getItemStack().getType();
                    if (itemType == Material.DIAMOND || itemType == Material.ANCIENT_DEBRIS || itemType == Material.SPAWNER) {
                        getLogger().log(Level.INFO, "Removed dropped " + itemType + " at " + item.getLocation());
                        item.remove();
                    }
                }
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        if (!mcMMOEnabled) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Long lastTime = lastBlockedTime.get(playerId);

        if (event.getAmount() > 0) {
            if (verboseLogging) {
                getLogger().info("Detected vanilla XP gain of " + event.getAmount() + " for " + player.getName() + " at " + player.getLocation());
            }
            if (lastTime != null && System.currentTimeMillis() - lastTime < 5000) {
                getLogger().info("Blocking " + event.getAmount() + " vanilla XP for " + player.getName() + " - recent restricted break");
                event.setAmount(0);
                int currentXp = player.getTotalExperience();
                if (currentXp > 0) {
                    player.setTotalExperience(0);
                    player.setLevel(0);
                    player.setExp(0);
                    player.giveExp(currentXp - event.getAmount());
                    getLogger().info("Forcefully deducted " + event.getAmount() + " XP from " + player.getName() + " to counter external gain");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMcMMOExpGain(McMMOPlayerXpGainEvent event) {
        if (!mcMMOEnabled || event.getSkill() != PrimarySkillType.MINING) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Long lastTime = lastBlockedTime.get(playerId);

        if (event.getRawXpGained() > 0) {
            if (verboseLogging) {
                getLogger().info("Detected McMMO XP gain of " + event.getRawXpGained() + " for " + player.getName() + " at " + player.getLocation());
            }
            if (lastTime != null && System.currentTimeMillis() - lastTime < 5000) {
                getLogger().info("Blocking " + event.getRawXpGained() + " McMMO XP for " + player.getName() + " - recent restricted break");
                event.setRawXpGained(0);
                event.setCancelled(true);
            }
        }
    }
}