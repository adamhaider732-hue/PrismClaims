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
import java.util.UUID;

public class ClaimsCommand implements CommandExecutor, TabCompleter {

    private final ClaimManager claimManager;

    public ClaimsCommand(ClaimManager claimManager) {
        this.claimManager = claimManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        UUID targetUUID;
        String targetName;

        // /claims <player> - staff only
        if (args.length >= 1) {
            if (!player.hasPermission("prismclaims.admin")) {
                player.sendMessage(Component.text("You don't have permission to view other players' claims.", NamedTextColor.RED));
                return true;
            }

            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                player.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            targetUUID = target.getUniqueId();
            targetName = target.getName() != null ? target.getName() : args[0];
        } else {
            targetUUID = player.getUniqueId();
            targetName = player.getName();
        }

        List<Claim> claims = claimManager.getPlayerClaims(targetUUID);
        int maxClaims = targetUUID.equals(player.getUniqueId())
                ? claimManager.getMaxClaims(player)
                : 0;

        player.sendMessage(Component.empty());

        if (targetUUID.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Your Claims ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("(" + claims.size() + "/" + maxClaims + ")",
                            NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
        } else {
            player.sendMessage(Component.text(targetName + "'s Claims ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("(" + claims.size() + ")",
                            NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)));
        }

        if (claims.isEmpty()) {
            player.sendMessage(Component.text("  No claims found.", NamedTextColor.GRAY));
        } else {
            for (Claim claim : claims) {
                player.sendMessage(Component.text("  " + claim.getName(), NamedTextColor.YELLOW)
                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(claim.getWorld(), NamedTextColor.AQUA))
                        .append(Component.text(" [" + claim.getMinX() + ", " + claim.getMinZ()
                                + " to " + claim.getMaxX() + ", " + claim.getMaxZ() + "]", NamedTextColor.GRAY))
                        .append(Component.text(" (" + claim.getWidth() + "x" + claim.getLength() + ")", NamedTextColor.DARK_GRAY)));

                if (!claim.getPermittedPlayers().isEmpty()) {
                    StringBuilder perms = new StringBuilder("    Builders: ");
                    boolean first = true;
                    for (UUID uuid : claim.getPermittedPlayers()) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        if (!first) perms.append(", ");
                        perms.append(op.getName() != null ? op.getName() : uuid.toString().substring(0, 8));
                        first = false;
                    }
                    player.sendMessage(Component.text(perms.toString(), NamedTextColor.GRAY));
                }
            }
        }

        player.sendMessage(Component.empty());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("prismclaims.admin")) {
            String partial = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}
