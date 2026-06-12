package com.prismsmp.claims.commands;

import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ClaimCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ClaimManager claimManager;
    private final DatabaseManager db;
    public static NamespacedKey CLAIM_SHOVEL_KEY;

    public ClaimCommand(JavaPlugin plugin, ClaimManager claimManager, DatabaseManager db) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.db = db;
        CLAIM_SHOVEL_KEY = new NamespacedKey(plugin, "claim_shovel");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("prismclaims.claim")) {
            player.sendMessage(Component.text("You don't have permission to use claims.", NamedTextColor.RED));
            return true;
        }

        // /claim confirm <name>
        if (args.length >= 2 && args[0].equalsIgnoreCase("confirm")) {
            return handleConfirm(player, args);
        }

        // /claim (no args) - give shovel
        if (args.length == 0) {
            return handleGiveShovel(player);
        }

        player.sendMessage(Component.text("Usage: /claim - get claim shovel", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("       /claim confirm <name> - create claim after selecting corners", NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleGiveShovel(Player player) {
        // Check if already has a claim shovel
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isClaimShovel(item)) {
                player.sendMessage(Component.text("You already have a claim shovel!", NamedTextColor.YELLOW));
                return true;
            }
        }

        // Check world restrictions
        if (claimManager.isWorldDisabled(player.getWorld().getName())) {
            player.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return true;
        }

        ItemStack shovel = createClaimShovel();
        player.getInventory().addItem(shovel);

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Claim Shovel", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("Right-click two corners to select your claim area.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Then use ", NamedTextColor.GRAY)
                .append(Component.text("/claim confirm <name>", NamedTextColor.YELLOW))
                .append(Component.text(" to create your claim.", NamedTextColor.GRAY)));
        player.sendMessage(Component.empty());
        return true;
    }

    private boolean handleConfirm(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /claim confirm <name>", NamedTextColor.RED));
            return true;
        }

        // Build name from remaining args
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) nameBuilder.append(" ");
            nameBuilder.append(args[i]);
        }
        String name = nameBuilder.toString();

        // Validate name length
        if (name.length() > 32) {
            player.sendMessage(Component.text("Claim name must be 32 characters or less.", NamedTextColor.RED));
            return true;
        }

        // Check if player has a complete selection
        if (!claimManager.hasSelection(player)) {
            player.sendMessage(Component.text("You need to select two corners first with the claim shovel!", NamedTextColor.RED));
            return true;
        }

        String world = claimManager.getSelectionWorld(player);

        // Check world restrictions
        if (claimManager.isWorldDisabled(world)) {
            player.sendMessage(Component.text("Claims are not allowed in that world.", NamedTextColor.RED));
            claimManager.clearSelection(player);
            return true;
        }

        // Check claim limit
        int currentClaims = claimManager.getPlayerClaimCount(player.getUniqueId());
        int maxClaims = claimManager.getMaxClaims(player);
        if (currentClaims >= maxClaims) {
            player.sendMessage(Component.text("You've reached your claim limit (" + maxClaims + ").", NamedTextColor.RED));
            return true;
        }

        // Check for duplicate name
        if (claimManager.getClaimByName(player.getUniqueId(), name) != null) {
            player.sendMessage(Component.text("You already have a claim named \"" + name + "\".", NamedTextColor.RED));
            return true;
        }

        int[] pos1 = claimManager.getFirstCorner(player);
        int[] pos2 = claimManager.getSecondCorner(player);

        int minX = Math.min(pos1[0], pos2[0]);
        int minZ = Math.min(pos1[1], pos2[1]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int maxZ = Math.max(pos1[1], pos2[1]);

        // Check size
        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;
        if (width > ClaimManager.MAX_SIZE || length > ClaimManager.MAX_SIZE) {
            player.sendMessage(Component.text("Selection too large! Max size is " +
                    ClaimManager.MAX_SIZE + "x" + ClaimManager.MAX_SIZE +
                    ". Yours is " + width + "x" + length + ".", NamedTextColor.RED));
            return true;
        }

        // Check for overlapping claims
        Claim overlap = claimManager.getOverlappingClaim(world, minX, minZ, maxX, maxZ);
        if (overlap != null) {
            player.sendMessage(Component.text("Your selection overlaps with an existing claim!", NamedTextColor.RED));
            return true;
        }

        // Check for nearby player-placed block (must have built within 16 blocks)
        try {
            boolean hasBlock = false;
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            int[][] checkPoints = {
                    {minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ},
                    {centerX, centerZ}
            };

            for (int x = minX - 16; x <= maxX + 16 && !hasBlock; x += 8) {
                for (int z = minZ - 16; z <= maxZ + 16 && !hasBlock; z += 8) {
                    if (db.hasPlayerBlockNear(world, x, z, player.getUniqueId())) {
                        hasBlock = true;
                    }
                }
            }
            if (!hasBlock) {
                for (int[] point : checkPoints) {
                    if (db.hasPlayerBlockNear(world, point[0], point[1], player.getUniqueId())) {
                        hasBlock = true;
                        break;
                    }
                }
            }

            if (!hasBlock) {
                player.sendMessage(Component.text("You must have placed at least one block within 16 blocks of the claim area!", NamedTextColor.RED));
                player.sendMessage(Component.text("Build something first, then claim around it.", NamedTextColor.GRAY));
                return true;
            }
        } catch (SQLException e) {
            player.sendMessage(Component.text("An error occurred. Please try again.", NamedTextColor.RED));
            plugin.getLogger().severe("Error checking placed blocks: " + e.getMessage());
            return true;
        }

        // Create the claim
        try {
            Claim claim = claimManager.createClaim(name, player);
            if (claim != null) {
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("Claim Created!", NamedTextColor.GREEN, TextDecoration.BOLD));
                player.sendMessage(Component.text("Name: ", NamedTextColor.GRAY)
                        .append(Component.text(name, NamedTextColor.GOLD)));
                player.sendMessage(Component.text("Size: ", NamedTextColor.GRAY)
                        .append(Component.text(width + "x" + length, NamedTextColor.YELLOW)));
                player.sendMessage(Component.text("Corner 1: ", NamedTextColor.GRAY)
                        .append(Component.text(minX + ", " + minZ, NamedTextColor.YELLOW)));
                player.sendMessage(Component.text("Corner 2: ", NamedTextColor.GRAY)
                        .append(Component.text(maxX + ", " + maxZ, NamedTextColor.YELLOW)));
                player.sendMessage(Component.empty());

                // Remove claim shovel
                removeClaimShovel(player);
            } else {
                player.sendMessage(Component.text("Failed to create claim. Please try again.", NamedTextColor.RED));
            }
        } catch (SQLException e) {
            player.sendMessage(Component.text("An error occurred. Please try again.", NamedTextColor.RED));
            plugin.getLogger().severe("Error creating claim: " + e.getMessage());
        }
        return true;
    }

    public static ItemStack createClaimShovel() {
        ItemStack shovel = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.displayName(Component.text("Claim Shovel", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click two blocks to select", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("the corners of your claim area.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CLAIM_SHOVEL_KEY, PersistentDataType.BYTE, (byte) 1);
        shovel.setItemMeta(meta);
        return shovel;
    }

    public static boolean isClaimShovel(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_SHOVEL) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(CLAIM_SHOVEL_KEY, PersistentDataType.BYTE);
    }

    private void removeClaimShovel(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isClaimShovel(item)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if ("confirm".startsWith(args[0].toLowerCase())) {
                completions.add("confirm");
            }
        }
        return completions;
    }
}
