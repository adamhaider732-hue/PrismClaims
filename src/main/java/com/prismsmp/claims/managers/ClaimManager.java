package com.prismsmp.claims.managers;

import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class ClaimManager {

    private final DatabaseManager db;
    private final Logger logger;
    private final List<Claim> claims;

    // Selection state: player UUID -> [x, z]
    private final Map<UUID, int[]> firstCorner = new HashMap<>();
    private final Map<UUID, int[]> secondCorner = new HashMap<>();
    private final Map<UUID, String> selectionWorld = new HashMap<>();

    // Large claim mode tracking (OP only)
    private final Set<UUID> largeModePlayers = new HashSet<>();

    // Max claim size (normal)
    public static final int MAX_SIZE = 80;

    // Large claim constants (OP only)
    public static final int LARGE_MAX_SIZE = 500;
    public static final int LARGE_MIN_Y = 45;

    // Disallowed worlds
    private static final Set<String> DISABLED_WORLDS = Set.of("spawnworld", "world_the_end");

    public ClaimManager(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
        this.claims = new CopyOnWriteArrayList<>();
    }

    // =========== LARGE MODE ===========

    public void setLargeMode(Player player, boolean large) {
        if (large) {
            largeModePlayers.add(player.getUniqueId());
        } else {
            largeModePlayers.remove(player.getUniqueId());
        }
    }

    public boolean isLargeMode(Player player) {
        return largeModePlayers.contains(player.getUniqueId());
    }

    // =========== CLAIM LOADING ===========

    public void loadClaims() {
        try {
            List<Claim> loaded = db.loadAllClaims();
            claims.clear();
            claims.addAll(loaded);
            logger.info("Loaded " + claims.size() + " claims.");
        } catch (SQLException e) {
            logger.severe("Failed to load claims: " + e.getMessage());
        }
    }

    // =========== SELECTION STATE ===========

    public void setFirstCorner(Player player, int x, int z) {
        firstCorner.put(player.getUniqueId(), new int[]{x, z});
        secondCorner.remove(player.getUniqueId());
        selectionWorld.put(player.getUniqueId(), player.getWorld().getName());
    }

    public void setSecondCorner(Player player, int x, int z) {
        secondCorner.put(player.getUniqueId(), new int[]{x, z});
    }

    public boolean hasSelection(Player player) {
        return firstCorner.containsKey(player.getUniqueId()) &&
               secondCorner.containsKey(player.getUniqueId());
    }

    public int[] getFirstCorner(Player player) {
        return firstCorner.get(player.getUniqueId());
    }

    public int[] getSecondCorner(Player player) {
        return secondCorner.get(player.getUniqueId());
    }

    public String getSelectionWorld(Player player) {
        return selectionWorld.get(player.getUniqueId());
    }

    public boolean hasFirstCorner(Player player) {
        return firstCorner.containsKey(player.getUniqueId());
    }

    public void clearSelection(Player player) {
        firstCorner.remove(player.getUniqueId());
        secondCorner.remove(player.getUniqueId());
        selectionWorld.remove(player.getUniqueId());
    }

    // =========== WORLD CHECKS ===========

    public boolean isWorldDisabled(String world) {
        return DISABLED_WORLDS.contains(world.toLowerCase());
    }

    // =========== CLAIM LIMITS ===========

    public int getMaxClaims(Player player) {
        for (int i = 20; i >= 1; i--) {
            if (player.hasPermission("prismclaims.limit." + i)) {
                return i;
            }
        }
        return 2;
    }

    public int getPlayerClaimCount(UUID uuid) {
        return (int) claims.stream().filter(c -> c.getOwner().equals(uuid)).count();
    }

    // =========== CLAIM LOOKUPS ===========

    public List<Claim> getPlayerClaims(UUID uuid) {
        return claims.stream().filter(c -> c.getOwner().equals(uuid)).toList();
    }

    public Claim getClaimByName(UUID owner, String name) {
        return claims.stream()
                .filter(c -> c.getOwner().equals(owner) && c.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public Claim getClaimById(int id) {
        return claims.stream().filter(c -> c.getId() == id).findFirst().orElse(null);
    }

    // 2D claim lookup (backward compat - used by AdminCommand, EntityListener)
    public Claim getClaimAt(String world, int x, int z) {
        for (Claim claim : claims) {
            if (claim.contains(world, x, z)) {
                return claim;
            }
        }
        return null;
    }

    // 3D claim lookup (checks Y range - used by ProtectionManager)
    public Claim getClaimAt(String world, int x, int y, int z) {
        for (Claim claim : claims) {
            if (claim.contains(world, x, y, z)) {
                return claim;
            }
        }
        return null;
    }

    public Claim getOverlappingClaim(String world, int minX, int minZ, int maxX, int maxZ) {
        for (Claim claim : claims) {
            if (!claim.getWorld().equals(world)) continue;
            if (claim.getMinX() <= maxX && claim.getMaxX() >= minX &&
                claim.getMinZ() <= maxZ && claim.getMaxZ() >= minZ) {
                return claim;
            }
        }
        return null;
    }

    // =========== CLAIM CREATION ===========

    public Claim createClaim(String name, Player player) throws SQLException {
        int[] pos1 = getFirstCorner(player);
        int[] pos2 = getSecondCorner(player);
        String world = getSelectionWorld(player);

        int minX = Math.min(pos1[0], pos2[0]);
        int minZ = Math.min(pos1[1], pos2[1]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int maxZ = Math.max(pos1[1], pos2[1]);

        // Determine Y range based on large mode
        int minY, maxY;
        if (isLargeMode(player)) {
            minY = LARGE_MIN_Y;
            maxY = player.getWorld().getMaxHeight();
        } else {
            minY = player.getWorld().getMinHeight();
            maxY = player.getWorld().getMaxHeight();
        }

        Claim claim = db.createClaim(name, player.getUniqueId(), world,
                minX, minZ, maxX, maxZ, minY, maxY);

        if (claim != null) {
            claims.add(claim);
            clearSelection(player);
        }
        return claim;
    }

    // =========== CLAIM DELETION ===========

    public boolean deleteClaim(Claim claim) {
        try {
            db.deleteClaim(claim.getId());
            claims.remove(claim);
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to delete claim: " + e.getMessage());
            return false;
        }
    }

    // =========== PERMISSION MANAGEMENT ===========

    public boolean addPermission(Claim claim, UUID player) {
        try {
            claim.addPermission(player);
            db.addPermission(claim.getId(), player);
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to add permission: " + e.getMessage());
            return false;
        }
    }

    public boolean removePermission(Claim claim, UUID player) {
        try {
            claim.removePermission(player);
            db.removePermission(claim.getId(), player);
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to remove permission: " + e.getMessage());
            return false;
        }
    }

    public List<Claim> getAllClaims() {
        return Collections.unmodifiableList(claims);
    }
}
