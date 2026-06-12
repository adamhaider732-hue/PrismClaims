package com.prismsmp.claims.commands;

import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.models.Claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class PermissionCommand implements CommandExecutor, TabCompleter {

    private final ClaimManager claimManager;

    public PermissionCommand(ClaimManager claimManager) {
        this.claimManager = claimManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /claimperm <add|remove> <player> <claim name>", NamedTextColor.RED));
            return true;
        }

        String action = args[0].toLowerCase();
        String targetName = args[1];

        // Build claim name from remaining args
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) nameBuilder.append(" ");
            nameBuilder.append(args[i]);
        }
        String claimName = nameBuilder.toString();

        if (!action.equals("add") && !action.equals("remove")) {
            player.sendMessage(Component.text("Usage: /claimperm <add|remove> <player> <claim name>", NamedTextColor.RED));
            return true;
        }

        // Find claim
        Claim claim = claimManager.getClaimByName(player.getUniqueId(), claimName);
        if (claim == null) {
            player.sendMessage(Component.text("You don't have a claim named \"" + claimName + "\".", NamedTextColor.RED));
            return true;
        }

        // Find target player
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(Component.text("Player \"" + targetName + "\" not found.", NamedTextColor.RED));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can't modify your own permissions on your claim.", NamedTextColor.RED));
            return true;
        }

        if (action.equals("add")) {
            if (claim.getPermittedPlayers().contains(target.getUniqueId())) {
                player.sendMessage(Component.text(targetName + " already has build permission on \"" + claimName + "\".", NamedTextColor.YELLOW));
                return true;
            }

            if (claimManager.addPermission(claim, target.getUniqueId())) {
                player.sendMessage(Component.text("Added ", NamedTextColor.GREEN)
                        .append(Component.text(targetName, NamedTextColor.YELLOW))
                        .append(Component.text(" as a builder on ", NamedTextColor.GREEN))
                        .append(Component.text("\"" + claimName + "\"", NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.GREEN)));

                // Notify target if online
                if (target.isOnline()) {
                    ((Player) target).sendMessage(Component.text("You've been given build permission on ", NamedTextColor.GREEN)
                            .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                            .append(Component.text("'s claim \"" + claimName + "\".", NamedTextColor.GREEN)));
                }
            } else {
                player.sendMessage(Component.text("Failed to add permission. Check console for errors.", NamedTextColor.RED));
            }
        } else {
            if (!claim.getPermittedPlayers().contains(target.getUniqueId())) {
                player.sendMessage(Component.text(targetName + " doesn't have build permission on \"" + claimName + "\".", NamedTextColor.YELLOW));
                return true;
            }

            if (claimManager.removePermission(claim, target.getUniqueId())) {
                player.sendMessage(Component.text("Removed ", NamedTextColor.GREEN)
                        .append(Component.text(targetName, NamedTextColor.YELLOW))
                        .append(Component.text(" from ", NamedTextColor.GREEN))
                        .append(Component.text("\"" + claimName + "\"", NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.GREEN)));
            } else {
                player.sendMessage(Component.text("Failed to remove permission. Check console for errors.", NamedTextColor.RED));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player player)) return completions;

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String s : List.of("add", "remove")) {
                if (s.startsWith(partial)) completions.add(s);
            }
        } else if (args.length == 2) {
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
        } else if (args.length == 3) {
            String partial = args[2].toLowerCase();
            for (Claim claim : claimManager.getPlayerClaims(player.getUniqueId())) {
                if (claim.getName().toLowerCase().startsWith(partial)) {
                    completions.add(claim.getName());
                }
            }
        }
        return completions;
    }
}
