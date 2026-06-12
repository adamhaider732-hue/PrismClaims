package com.prismsmp.claims.commands;

import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.models.Claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class AdminCommand implements CommandExecutor, TabCompleter {

    private final ClaimManager claimManager;

    public AdminCommand(ClaimManager claimManager) {
        this.claimManager = claimManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("prismclaims.admin")) {
            player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "delete" -> handleDelete(player, args);
            case "info" -> handleInfo(player);
            case "list" -> handleList(player, args);
            default -> sendUsage(player);
        }
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("Claim Admin Commands:", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("  /claimadmin delete <player> <claim>", NamedTextColor.YELLOW)
                .append(Component.text(" - Delete a player's claim", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  /claimadmin info", NamedTextColor.YELLOW)
                .append(Component.text(" - Show claim info at your location", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  /claimadmin list [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - List all claims or a player's claims", NamedTextColor.GRAY)));
    }

    private void handleDelete(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /claimadmin delete <player> <claim name>", NamedTextColor.RED));
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return;
        }

        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) nameBuilder.append(" ");
            nameBuilder.append(args[i]);
        }
        String claimName = nameBuilder.toString();

        Claim claim = claimManager.getClaimByName(target.getUniqueId(), claimName);
        if (claim == null) {
            player.sendMessage(Component.text("Claim \"" + claimName + "\" not found for " + args[1] + ".", NamedTextColor.RED));
            return;
        }

        if (claimManager.deleteClaim(claim)) {
            player.sendMessage(Component.text("Deleted claim \"" + claimName + "\" owned by " + args[1] + ".", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Failed to delete claim.", NamedTextColor.RED));
        }
    }

    private void handleInfo(Player player) {
        String world = player.getWorld().getName();
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();

        Claim claim = claimManager.getClaimAt(world, x, z);
        if (claim == null) {
            player.sendMessage(Component.text("You are not standing in any claim.", NamedTextColor.YELLOW));
            return;
        }

        OfflinePlayer owner = Bukkit.getOfflinePlayer(claim.getOwner());

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Claim Info", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("  Name: ", NamedTextColor.GRAY)
                .append(Component.text(claim.getName(), NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("  Owner: ", NamedTextColor.GRAY)
                .append(Component.text(owner.getName() != null ? owner.getName() : claim.getOwner().toString(),
                        NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("  World: ", NamedTextColor.GRAY)
                .append(Component.text(claim.getWorld(), NamedTextColor.AQUA)));
        player.sendMessage(Component.text("  Size: ", NamedTextColor.GRAY)
                .append(Component.text(claim.getWidth() + "x" + claim.getLength(), NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("  Corners: ", NamedTextColor.GRAY)
                .append(Component.text(claim.getMinX() + ", " + claim.getMinZ() + " to "
                        + claim.getMaxX() + ", " + claim.getMaxZ(), NamedTextColor.YELLOW)));

        if (!claim.getPermittedPlayers().isEmpty()) {
            StringBuilder perms = new StringBuilder("  Builders: ");
            boolean first = true;
            for (var uuid : claim.getPermittedPlayers()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (!first) perms.append(", ");
                perms.append(op.getName() != null ? op.getName() : uuid.toString().substring(0, 8));
                first = false;
            }
            player.sendMessage(Component.text(perms.toString(), NamedTextColor.GRAY));
        }
        player.sendMessage(Component.empty());
    }

    private void handleList(Player player, String[] args) {
        if (args.length >= 2) {
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            List<Claim> claims = claimManager.getPlayerClaims(target.getUniqueId());

            player.sendMessage(Component.empty());
            player.sendMessage(Component.text(args[1] + "'s Claims (" + claims.size() + ")",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
            for (Claim claim : claims) {
                player.sendMessage(Component.text("  " + claim.getName(), NamedTextColor.YELLOW)
                        .append(Component.text(" - " + claim.getWorld() + " ["
                                + claim.getMinX() + ", " + claim.getMinZ() + " to "
                                + claim.getMaxX() + ", " + claim.getMaxZ() + "]", NamedTextColor.GRAY)));
            }
            player.sendMessage(Component.empty());
        } else {
            List<Claim> allClaims = claimManager.getAllClaims();
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("All Claims (" + allClaims.size() + ")",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
            for (Claim claim : allClaims) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(claim.getOwner());
                player.sendMessage(Component.text("  " + claim.getName(), NamedTextColor.YELLOW)
                        .append(Component.text(" by ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(owner.getName() != null ? owner.getName() : "Unknown", NamedTextColor.AQUA))
                        .append(Component.text(" - " + claim.getWorld() + " ["
                                + claim.getMinX() + ", " + claim.getMinZ() + " to "
                                + claim.getMaxX() + ", " + claim.getMaxZ() + "]", NamedTextColor.GRAY)));
            }
            player.sendMessage(Component.empty());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("prismclaims.admin")) return completions;

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String s : List.of("delete", "info", "list")) {
                if (s.startsWith(partial)) completions.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("list"))) {
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
