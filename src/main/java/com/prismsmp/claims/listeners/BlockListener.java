package com.prismsmp.claims.listeners;

import com.prismsmp.claims.commands.ClaimCommand;
import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.managers.ProtectionManager;
import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class BlockListener implements Listener {

    private final JavaPlugin plugin;
    private final ClaimManager claimManager;
    private final ProtectionManager protectionManager;
    private final DatabaseManager db;
    private final Logger logger;

    // Disabled worlds for block tracking
    private static final java.util.Set<String> DISABLED_TRACK_WORLDS = java.util.Set.of("spawnworld");

    public BlockListener(JavaPlugin plugin, ClaimManager claimManager,
                          ProtectionManager protectionManager, DatabaseManager db) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.protectionManager = protectionManager;
        this.db = db;
        this.logger = plugin.getLogger();
    }

    // =========== CLAIM SHOVEL SELECTION ===========

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getItem() == null) return;
        if (!ClaimCommand.isClaimShovel(event.getItem())) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) return;

        String world = block.getWorld().getName();
        int x = block.getX();
        int z = block.getZ();

        // Check disabled worlds
        if (claimManager.isWorldDisabled(world)) {
            player.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return;
        }

        if (!claimManager.hasFirstCorner(player)) {
            // Setting first corner
            claimManager.setFirstCorner(player, x, z);

            // If they're using the large claim shovel, enable large mode
            if (ClaimCommand.isLargeClaimShovel(event.getItem())) {
                claimManager.setLargeMode(player, true);
            }

            player.sendMessage(Component.text("Corner 1 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Right-click another block for corner 2.", NamedTextColor.GRAY));
        } else if (!claimManager.hasSelection(player)) {
            // Setting second corner
            // Check same world
            if (!world.equals(claimManager.getSelectionWorld(player))) {
                player.sendMessage(Component.text("Both corners must be in the same world!", NamedTextColor.RED));
                claimManager.clearSelection(player);
                return;
            }

            claimManager.setSecondCorner(player, x, z);

            int[] pos1 = claimManager.getFirstCorner(player);
            int minX = Math.min(pos1[0], x);
            int minZ = Math.min(pos1[1], z);
            int maxX = Math.max(pos1[0], x);
            int maxZ = Math.max(pos1[1], z);
            int width = maxX - minX + 1;
            int length = maxZ - minZ + 1;

            player.sendMessage(Component.text("Corner 2 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Selection: ", NamedTextColor.GRAY)
                    .append(Component.text(width + "x" + length, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Use ", NamedTextColor.GRAY)
                    .append(Component.text("/claim confirm <name>", NamedTextColor.GOLD))
                    .append(Component.text(" to create your claim.", NamedTextColor.GRAY)));
        } else {
            // Already have both corners, reset and start over
            claimManager.clearSelection(player);
            claimManager.setFirstCorner(player, x, z);

            // If they're using the large claim shovel, enable large mode
            if (ClaimCommand.isLargeClaimShovel(event.getItem())) {
                claimManager.setLargeMode(player, true);
            }

            player.sendMessage(Component.text("Selection reset. Corner 1 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Right-click another block for corner 2.", NamedTextColor.GRAY));
        }
    }

    // =========== BLOCK TRACKING ===========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        String world = event.getBlock().getWorld().getName();
        if (DISABLED_TRACK_WORLDS.contains(world)) return;

        Block block = event.getBlock();
        try {
            db.trackBlock(world, block.getX(), block.getY(), block.getZ(), event.getPlayer().getUniqueId());
        } catch (SQLException e) {
            logger.warning("Failed to track placed block: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        String world = event.getBlock().getWorld().getName();
        if (DISABLED_TRACK_WORLDS.contains(world)) return;

        Block block = event.getBlock();
        try {
            db.untrackBlock(world, block.getX(), block.getY(), block.getZ());
        } catch (SQLException e) {
            logger.warning("Failed to untrack broken block: " + e.getMessage());
        }
    }

    // =========== CLAIM PROTECTION (BLOCK BREAK) ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreakProtection(BlockBreakEvent event) {
        Player player = event.getPlayer();

        // Bypass permission
        if (player.hasPermission("prismclaims.bypass")) return;

        Block block = event.getBlock();
        String world = block.getWorld().getName();

        Claim claim = claimManager.getClaimAt(world, block.getX(), block.getY(), block.getZ());
        if (claim == null) return;

        // Check if player is the owner or has permission
        if (claim.getOwner().equals(player.getUniqueId())) return;
        if (claim.hasPermission(player.getUniqueId())) return;

        // Check if the block is player-placed (only protect placed blocks)
        try {
            boolean isPlaced = protectionManager.isProtectedBlock(world, block.getX(), block.getY(), block.getZ());
            if (isPlaced) {
                event.setCancelled(true);
                player.sendMessage(Component.text("This block is protected by ", NamedTextColor.RED)
                        .append(Component.text(claim.getName(), NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.RED)));
            }
        } catch (SQLException e) {
            // Err on the side of protection
            event.setCancelled(true);
            logger.warning("Error checking block protection: " + e.getMessage());
        }
    }

    // =========== CLAIM PROTECTION (BLOCK PLACE) ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlaceProtection(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        // Bypass permission
        if (player.hasPermission("prismclaims.bypass")) return;

        Block block = event.getBlock();
        String world = block.getWorld().getName();

        Claim claim = claimManager.getClaimAt(world, block.getX(), block.getY(), block.getZ());
        if (claim == null) return;

        // Check if player is the owner or has permission
        if (claim.getOwner().equals(player.getUniqueId())) return;
        if (claim.hasPermission(player.getUniqueId())) return;

        // Block placing in someone else's claim
        event.setCancelled(true);
        player.sendMessage(Component.text("You can't build in ", NamedTextColor.RED)
                .append(Component.text(claim.getName(), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.RED)));
    }

    // =========== TREE GROWTH PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getPlayer() == null) return;

        Player player = event.getPlayer();
        if (player.hasPermission("prismclaims.bypass")) return;

        String world = event.getLocation().getWorld().getName();

        // Remove any blocks that would grow into a protected claim
        Iterator<org.bukkit.block.BlockState> iterator = event.getBlocks().iterator();
        while (iterator.hasNext()) {
            org.bukkit.block.BlockState state = iterator.next();
            Claim claim = claimManager.getClaimAt(world, state.getX(), state.getY(), state.getZ());
            if (claim != null && !claim.getOwner().equals(player.getUniqueId()) &&
                !claim.hasPermission(player.getUniqueId())) {
                iterator.remove();
            }
        }
    }
}
