package com.prismsmp.claims.commands;

import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.models.Claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DelClaimCommand implements CommandExecutor, TabCompleter {

    private final ClaimManager claimManager;

    public DelClaimCommand(ClaimManager claimManager) {
        this.claimManager = claimManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /delclaim <name>", NamedTextColor.RED));
            return true;
        }

        String name = String.join(" ", args);

        Claim claim = claimManager.getClaimByName(player.getUniqueId(), name);
        if (claim == null) {
            player.sendMessage(Component.text("You don't have a claim named \"" + name + "\".", NamedTextColor.RED));
            player.sendMessage(Component.text("Use /claims to see your claims.", NamedTextColor.GRAY));
            return true;
        }

        if (claimManager.deleteClaim(claim)) {
            player.sendMessage(Component.text("Claim \"" + name + "\" has been deleted.", NamedTextColor.GREEN));
            player.sendMessage(Component.text("All block protection data in that area has been removed.", NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("Failed to delete claim. Check console for errors.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!(sender instanceof Player player)) return completions;

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (Claim claim : claimManager.getPlayerClaims(player.getUniqueId())) {
                if (claim.getName().toLowerCase().startsWith(partial)) {
                    completions.add(claim.getName());
                }
            }
        }
        return completions;
    }
}
