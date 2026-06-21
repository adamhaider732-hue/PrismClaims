package com.prismsmp.claims.commands;

import com.prismsmp.claims.PrismClaims;
import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ClaimCommand implements CommandExecutor, TabCompleter {

    private final PrismClaims plugin;
    private final ClaimManager claimManager;
    private final DatabaseManager db;

    public ClaimCommand(PrismClaims plugin, ClaimManager claimManager, DatabaseManager db) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.db = db;
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

        // /claim large - OP only, gives netherite large claim shovel
        if (args.length >= 1 && args[0].equalsIgnoreCase("large")) {
            if (!player.isOp()) {
                player.sendMessage(Component.text("Only OPs can use large claims.", NamedTextColor.RED));
                return true;
            }
            claimManager.setLargeMode(player, true);
            claimManager.clearSelection(player);
            removeClaimShovel(player);
            player.getInventory().addItem(createLargeClaimShovel());
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("Large Claim Shovel given!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
            player.sendMessage(Component.text("Max size: 500x500 | Height: Y 45 to build limit", NamedTextColor.GRAY));
            player.sendMessage(Component.text("Right-click two blocks to select corners, then /claim confirm <name>", NamedTextColor.GRAY));
            player.sendMessage(Component.empty());
            return true;
        }

        // /claim giveshovel <player> - OP only, gives target player a large claim shovel
        if (args.length >= 2 && args[0].equalsIgnoreCase("giveshovel")) {
            if (!player.isOp()) {
                player.sendMessage(Component.text("Only OPs can use this command.", NamedTextColor.RED));
                return true;
            }
            Player target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(Component.text("Player not found or not online.", NamedTextColor.RED));
                return true;
            }
            claimManager.setLargeMode(target, true);
            claimManager.clearSelection(target);
            removeClaimShovel(target);
            target.getInventory().addItem(createLargeClaimShovel());
            target.sendMessage(Component.empty());
            target.sendMessage(Component.text("You received a Large Claim Shovel!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
            target.sendMessage(Component.text("Max size: 500x500 | Height: Y 45 to build limit", NamedTextColor.GRAY));
            target.sendMessage(Component.text("Right-click two blocks to select corners, then /claim confirm <name>", NamedTextColor.GRAY));
            target.sendMessage(Component.empty());
            player.sendMessage(Component.text("Gave large claim shovel to " + target.getName() + ".", NamedTextColor.GREEN));
            return true;
        }

        // /claim confirm <name> - create the claim
        if (args.length >= 2 && args[0].equalsIgnoreCase("confirm")) {
            return handleConfirm(player, args[1]);
        }

        // /claim - give regular claim shovel
        if (args.length == 0) {
            claimManager.setLargeMode(player, false);
            claimManager.clearSelection(player);
            removeClaimShovel(player);
            player.getInventory().addItem(createClaimShovel());
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("Claim Shovel given!", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("Right-click two blocks to select corners, then /claim confirm <name>", NamedTextColor.GRAY));
            player.sendMessage(Component.empty());
            return true;
        }

        // Unknown subcommand
        player.sendMessage(Component.text("Usage: /claim, /claim confirm <name>", NamedTextColor.GRAY));
        return true;
    }

    private boolean handleConfirm(Player player, String name) {
        // Validate name
        if (name.length() > 20) {
            player.sendMessage(Component.text("Claim name must be 20 characters or less.", NamedTextColor.RED));
            return true;
        }

        if (!name.matches("[a-zA-Z0-9_-]+")) {
            player.sendMessage(Component.text("Claim name can only contain letters, numbers, dashes, and underscores.", NamedTextColor.RED));
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

        // Check size - use large limits if player is in large mode
        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;
        int maxSize = claimManager.isLargeMode(player) ? ClaimManager.LARGE_MAX_SIZE : ClaimManager.MAX_SIZE;
        if (width > maxSize || length > maxSize) {
            player.sendMessage(Component.text("Selection too large! Max size is " +
                    maxSize + "x" + maxSize +
                    ". Yours is " + width + "x" + length + ".", NamedTextColor.RED));
            return true;
        }

        // Check for overlapping claims
        Claim overlap = claimManager.getOverlappingClaim(world, minX, minZ, maxX, maxZ);
        if (overlap != null) {
            player.sendMessage(Component.text("Your selection overlaps with an existing claim!", NamedTextColor.RED));
            return true;
        }

        // Check that area contains player-placed blocks
        try {
            boolean hasPlacedBlocks = db.hasPlacedBlocksInArea(world, minX, minZ, maxX, maxZ);
            if (!hasPlacedBlocks) {
                player.sendMessage(Component.text("You need to have placed blocks in this area to claim it.", NamedTextColor.RED));
                player.sendMessage(Component.text("Build something first, then try again.", NamedTextColor.GRAY));
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
                String modeLabel = claimManager.isLargeMode(player) ? " (Large)" : "";
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("Claim Created!" + modeLabel, NamedTextColor.GREEN, TextDecoration.BOLD));
                player.sendMessage(Component.text("Name: ", NamedTextColor.GRAY)
                        .append(Component.text(name, NamedTextColor.GOLD)));
                player.sendMessage(Component.text("Size: ", NamedTextColor.GRAY)
                        .append(Component.text(width + "x" + length, NamedTextColor.YELLOW)));
                player.sendMessage(Component.text("Corner 1: ", NamedTextColor.GRAY)
                        .append(Component.text(minX + ", " + minZ, NamedTextColor.YELLOW)));
                player.sendMessage(Component.text("Corner 2: ", NamedTextColor.GRAY)
                        .append(Component.text(maxX + ", " + maxZ, NamedTextColor.YELLOW)));
                if (claimManager.isLargeMode(player)) {
                    player.sendMessage(Component.text("Height: ", NamedTextColor.GRAY)
                            .append(Component.text("Y " + ClaimManager.LARGE_MIN_Y + " to build limit", NamedTextColor.YELLOW)));
                }
                player.sendMessage(Component.empty());

                // Remove claim shovel and clear large mode
                removeClaimShovel(player);
                claimManager.setLargeMode(player, false);
            } else {
                player.sendMessage(Component.text("Failed to create claim. Please try again.", NamedTextColor.RED));
            }
        } catch (SQLException e) {
            player.sendMessage(Component.text("An error occurred. Please try again.", NamedTextColor.RED));
            plugin.getLogger().severe("Error creating claim: " + e.getMessage());
        }
        return true;
    }

    // =========== CLAIM SHOVELS ===========

    public static ItemStack createClaimShovel() {
        ItemStack shovel = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.displayName(Component.text("Claim Shovel", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click two blocks to select", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("the corners of your claim area.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Max size: 80x80", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setUnbreakable(true);
        shovel.setItemMeta(meta);
        return shovel;
    }

    public static ItemStack createLargeClaimShovel() {
        ItemStack shovel = new ItemStack(Material.NETHERITE_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.displayName(Component.text("Large Claim Shovel", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click two blocks to select", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("the corners of your large claim area.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Max size: 500x500", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Height: Y 45 to build limit", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("OP Only", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setUnbreakable(true);
        shovel.setItemMeta(meta);
        return shovel;
    }

    public static boolean isClaimShovel(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return false;
        if (item.getType() != Material.GOLDEN_SHOVEL && item.getType() != Material.NETHERITE_SHOVEL) return false;
        Component name = item.getItemMeta().displayName();
        if (name == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        return plain.equals("Claim Shovel") || plain.equals("Large Claim Shovel");
    }

    public static boolean isLargeClaimShovel(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return false;
        if (item.getType() != Material.NETHERITE_SHOVEL) return false;
        Component name = item.getItemMeta().displayName();
        if (name == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        return plain.equals("Large Claim Shovel");
    }

    private void removeClaimShovel(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isClaimShovel(item)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    // =========== TAB COMPLETION ===========

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>(List.of("confirm"));
            if (player.isOp()) {
                completions.add("large");
                completions.add("giveshovel");
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("confirm")) {
                return List.of("<name>");
            }
            if (args[0].equalsIgnoreCase("giveshovel") && player.isOp()) {
                // Return online player names
                return null;
            }
        }

        return List.of();
    }
}
