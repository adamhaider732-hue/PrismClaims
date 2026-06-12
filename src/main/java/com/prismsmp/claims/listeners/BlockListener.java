package com.prismsmp.claims.listeners;

import com.prismsmp.claims.commands.ClaimCommand;
import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.managers.ProtectionManager;
import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
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

        // Check disabled worlds
        if (claimManager.isWorldDisabled(world)) {
            player.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return;
        }

        if (!claimManager.hasFirstCorner(player)) {
            // Setting first corner
            claimManager.setFirstCorner(player, x, z);
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

            if (width > ClaimManager.MAX_SIZE || length > ClaimManager.MAX_SIZE) {
                player.sendMessage(Component.text("Selection too large! Max: " + ClaimManager.MAX_SIZE
                        + "x" + ClaimManager.MAX_SIZE + ". Yours: " + width + "x" + length, NamedTextColor.RED));
                claimManager.clearSelection(player);
                return;
            }

            player.sendMessage(Component.text("Corner 2 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Selection: " + width + "x" + length, NamedTextColor.GRAY));
            player.sendMessage(Component.text("Use ", NamedTextColor.GRAY)
                    .append(Component.text("/claim confirm <name>", NamedTextColor.YELLOW))
                    .append(Component.text(" to create your claim.", NamedTextColor.GRAY)));
        } else {
            // Reset and start new selection
            claimManager.clearSelection(player);
            claimManager.setFirstCorner(player, x, z);
            player.sendMessage(Component.text("Selection reset. Corner 1 set: ", NamedTextColor.GREEN)
                    .append(Component.text(x + ", " + z, NamedTextColor.YELLOW)));
        }
    }

    // =========== BLOCK PLACEMENT ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();
        String world = loc.getWorld().getName();

        // Skip tracking in disabled worlds
        if (DISABLED_TRACK_WORLDS.contains(world.toLowerCase())) return;

        // Protection check: can this player place here?
        if (!protectionManager.canPlaceBlock(player, loc)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You can't place blocks in this claim.", NamedTextColor.RED));
            return;
        }

        // Track the placed block (async to reduce main thread load)
        UUID uuid = player.getUniqueId();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.trackBlock(world, x, y, z, uuid);
            } catch (SQLException e) {
                logger.warning("Failed to track block placement: " + e.getMessage());
            }
        });
    }

    // =========== BLOCK BREAKING ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();
        String world = loc.getWorld().getName();

        // Protection check
        if (!protectionManager.canBreakBlock(player, loc)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("This block is protected by a claim.", NamedTextColor.RED));
            return;
        }

        // Untrack the block if it was tracked
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.untrackBlock(world, x, y, z);
            } catch (SQLException e) {
                logger.warning("Failed to untrack block: " + e.getMessage());
            }
        });
    }

    // =========== BUCKET PLACEMENT ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();

        // Check claim protection - treat bucket placement like block placement
        Claim claim = claimManager.getClaimAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        if (claim != null && !claim.isAuthorized(player.getUniqueId())
                && !player.hasPermission("prismclaims.bypass")) {

            var result = protectionManager.checkProtection(loc);
            if (result.inProtectedZone()) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You can't place liquids in this protected area.", NamedTextColor.RED));
            }
        }
    }

    // =========== EXPLOSIONS ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        filterExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosionBlocks(event.blockList());
    }

    /**
     * Remove protected blocks from explosion block lists.
     * The explosion still happens, but protected blocks are not destroyed.
     */
    private void filterExplosionBlocks(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            String world = block.getWorld().getName();
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();

            if (protectionManager.isBlockProtectedFromEnvironment(world, x, y, z)) {
                it.remove();
            }
        }
    }

    // =========== PISTON MOVEMENT ===========

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePistonMove(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePistonMove(event.getBlocks(), event.getDirection());
    }

    private void handlePistonMove(List<Block> blocks, org.bukkit.block.BlockFace direction) {
        String world = null;
        List<int[]> moves = new ArrayList<>();

        for (Block block : blocks) {
            world = block.getWorld().getName();
            int fromX = block.getX();
            int fromY = block.getY();
            int fromZ = block.getZ();
            int toX = fromX + direction.getModX();
            int toY = fromY + direction.getModY();
            int toZ = fromZ + direction.getModZ();
            moves.add(new int[]{fromX, fromY, fromZ, toX, toY, toZ});
        }

        if (world == null || moves.isEmpty()) return;

        final String w = world;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (int[] move : moves) {
                try {
                    db.moveBlock(w, move[0], move[1], move[2], move[3], move[4], move[5]);
                } catch (SQLException e) {
                    logger.warning("Failed to track piston block move: " + e.getMessage());
                }
            }
        });
    }

    // =========== TREE / STRUCTURE GROWTH ===========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Player player = event.getPlayer();
        if (player == null) return; // Only track player-planted growths

        String world = event.getLocation().getWorld().getName();
        if (DISABLED_TRACK_WORLDS.contains(world.toLowerCase())) return;

        UUID uuid = player.getUniqueId();
        List<int[]> positions = new ArrayList<>();

        for (BlockState state : event.getBlocks()) {
            Location loc = state.getLocation();
            positions.add(new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});
        }

        if (positions.isEmpty()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.trackBlocksBatch(world, positions, uuid);
            } catch (SQLException e) {
                logger.warning("Failed to track structure growth: " + e.getMessage());
            }
        });
    }

    // =========== FALLING BLOCKS (sand, gravel) ===========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.FallingBlock fallingBlock)) return;

        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (event.getTo().isAir()) {
            // Block started falling - handled when it lands
        } else {
            // Block landed - transfer tracking from origin
            Location origin = fallingBlock.getOrigin();
            if (origin == null) return;

            String originWorld = origin.getWorld().getName();
            int ox = origin.getBlockX();
            int oy = origin.getBlockY();
            int oz = origin.getBlockZ();

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    UUID placer = db.getBlockPlacer(originWorld, ox, oy, oz);
                    if (placer != null) {
                        db.untrackBlock(originWorld, ox, oy, oz);
                        db.trackBlock(world, x, y, z, placer);
                    }
                } catch (SQLException e) {
                    logger.warning("Failed to track falling block: " + e.getMessage());
                }
            });
        }
    }
}
