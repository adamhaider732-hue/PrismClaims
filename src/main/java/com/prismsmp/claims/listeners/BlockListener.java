package com.prismsmp.claims.listeners;

import com.prismsmp.claims.commands.ClaimCommand;
import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.managers.ProtectionManager;
import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

public class BlockListener implements Listener {

    private final JavaPlugin plugin;
    private final ClaimManager claimManager;
    private final ProtectionManager protectionManager;
    private final DatabaseManager db;
    private final Logger logger;

    private static final Set<String> DISABLED_TRACK_WORLDS = Set.of("spawnworld");

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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
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

        if (claimManager.isWorldDisabled(world)) {
            player.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return;
        }

        if (!claimManager.hasFirstCorner(player)) {
            // Setting first corner
            claimManager.setFirstCorner(player, x, z);

            // Auto-enable large mode if using the large shovel
            if (ClaimCommand.isLargeClaimShovel(event.getItem())) {
                claimManager.setLargeMode(player, true);
            }

            player.sendMessage(Component.text("Corner 1 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Right-click another block for corner 2.", NamedTextColor.GRAY));
        } else if (!claimManager.hasSelection(player)) {
            // Setting second corner
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
            // Already have both corners - reset and start over
            claimManager.clearSelection(player);
            claimManager.setFirstCorner(player, x, z);

            if (ClaimCommand.isLargeClaimShovel(event.getItem())) {
                claimManager.setLargeMode(player, true);
            }

            player.sendMessage(Component.text("Selection reset. Corner 1 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Right-click another block for corner 2.", NamedTextColor.GRAY));
        }
    }

    // =========== BLOCK TRACKING (ASYNC) ===========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        if (DISABLED_TRACK_WORLDS.contains(world)) return;

        int x = block.getX(), y = block.getY(), z = block.getZ();
        UUID uuid = event.getPlayer().getUniqueId();

        // Check placement protection
        if (!protectionManager.canPlaceBlock(event.getPlayer(), block.getLocation())) {
            Claim claim = claimManager.getClaimAt(world, x, y, z);
            event.setCancelled(true);
            if (claim != null) {
                event.getPlayer().sendMessage(Component.text("You can't build in ", NamedTextColor.RED)
                        .append(Component.text(claim.getName(), NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.RED)));
            }
            return;
        }

        // Track the placed block async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.trackBlock(world, x, y, z, uuid);
            } catch (SQLException e) {
                logger.warning("Failed to track placed block: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        Player player = event.getPlayer();

        // Check break protection
        if (!protectionManager.canBreakBlock(player, block.getLocation())) {
            Claim claim = claimManager.getClaimAt(world, block.getX(), block.getY(), block.getZ());
            event.setCancelled(true);
            if (claim != null) {
                player.sendMessage(Component.text("This block is protected by ", NamedTextColor.RED)
                        .append(Component.text(claim.getName(), NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.RED)));
            }
            return;
        }

        if (DISABLED_TRACK_WORLDS.contains(world)) return;

        int x = block.getX(), y = block.getY(), z = block.getZ();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.untrackBlock(world, x, y, z);
            } catch (SQLException e) {
                logger.warning("Failed to untrack broken block: " + e.getMessage());
            }
        });
    }

    // =========== BUCKET PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (!protectionManager.canPlaceBlock(player, block.getLocation())) {
            event.setCancelled(true);
            Claim claim = claimManager.getClaimAt(block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            if (claim != null) {
                player.sendMessage(Component.text("You can't place blocks in ", NamedTextColor.RED)
                        .append(Component.text(claim.getName(), NamedTextColor.GOLD))
                        .append(Component.text(".", NamedTextColor.RED)));
            }
        }
    }

    // =========== EXPLOSION PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosionBlocks(event.blockList());
    }

    private void filterExplosionBlocks(List<Block> blocks) {
        blocks.removeIf(block -> {
            String world = block.getWorld().getName();
            return protectionManager.isBlockProtectedFromEnvironment(
                    world, block.getX(), block.getY(), block.getZ());
        });
    }

    // =========== PISTON PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePistonMove(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePistonMove(event.getBlocks(), event.getDirection());
    }

    private void handlePistonMove(List<Block> blocks, BlockFace direction) {
        // Track block movements async
        List<int[]> movements = new ArrayList<>();
        String world = null;

        for (Block block : blocks) {
            if (world == null) world = block.getWorld().getName();
            int fromX = block.getX(), fromY = block.getY(), fromZ = block.getZ();
            int toX = fromX + direction.getModX();
            int toY = fromY + direction.getModY();
            int toZ = fromZ + direction.getModZ();
            movements.add(new int[]{fromX, fromY, fromZ, toX, toY, toZ});
        }

        if (world == null || movements.isEmpty()) return;

        String finalWorld = world;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (int[] m : movements) {
                try {
                    db.moveBlock(finalWorld, m[0], m[1], m[2], m[3], m[4], m[5]);
                } catch (SQLException e) {
                    logger.warning("Failed to track piston move: " + e.getMessage());
                }
            }
        });
    }

    // =========== STRUCTURE GROWTH PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getPlayer() == null) return;

        Player player = event.getPlayer();
        if (player.hasPermission("prismclaims.bypass")) return;

        String world = event.getLocation().getWorld().getName();
        UUID playerUuid = player.getUniqueId();

        // Remove blocks that would grow into a claim the player isn't authorized in
        List<BlockState> toRemove = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            Claim claim = claimManager.getClaimAt(world, state.getX(), state.getZ());
            if (claim != null && !claim.isAuthorized(playerUuid)) {
                toRemove.add(state);
            }
        }
        event.getBlocks().removeAll(toRemove);

        // Track grown blocks async
        List<int[]> positions = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            positions.add(new int[]{state.getX(), state.getY(), state.getZ()});
        }
        if (!positions.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.trackBlocksBatch(world, positions, playerUuid);
                } catch (SQLException e) {
                    logger.warning("Failed to track structure growth: " + e.getMessage());
                }
            });
        }
    }

    // =========== ENTITY BLOCK CHANGE PROTECTION ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();

        if (protectionManager.isBlockProtectedFromEnvironment(world, x, y, z)) {
            event.setCancelled(true);
            return;
        }

        // Track block movement for falling blocks (sand/gravel)
        if (event.getEntity() instanceof org.bukkit.entity.FallingBlock) {
            Location to = event.getEntity().getLocation();
            int toX = to.getBlockX(), toY = to.getBlockY(), toZ = to.getBlockZ();
            String toWorld = to.getWorld().getName();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    db.moveBlock(world, x, y, z, toX, toY, toZ);
                } catch (SQLException e) {
                    logger.warning("Failed to track falling block: " + e.getMessage());
                }
            });
        }
    }
}
