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

    // Selection state: player UUID -> [pos1, pos2]
    private final Map<UUID, int[]> firstCorner = new HashMap<>();
    private final Map<UUID, int[]> secondCorner = new HashMap<>();
    private final Map<UUID, String> selectionWorld = new HashMap<>();

    // Max claim size
    public static final int MAX_SIZE = 80;

    // Disallowed worlds
    private static final Set<String> DISABLED_WORLDS = Set.of("spawnworld", "world_the_end");

    public ClaimManager(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
        this.claims = new CopyOnWriteArrayList<>();
    }

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

    // =========== SELECTION ===========

    public void setFirstCorner(Player player, int x, int z) {
        firstCorner.put(player.getUniqueId(), new int[]{x, z});
        selectionWorld.put(player.getUniqueId(), player.getWorld().getName());
    }

    public void setSecondCorner(Player player, int x, int z) {
        secondCorner.put(player.getUniqueId(), new int[]{x, z});
    }

    public boolean hasSelection(Player player) {
        UUID uuid = player.getUniqueId();
        return firstCorner.containsKey(uuid) && secondCorner.containsKey(uuid);
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
        UUID uuid = player.getUniqueId();
        firstCorner.remove(uuid);
        secondCorner.remove(uuid);
        selectionWorld.remove(uuid);
    }

    // =========== CLAIM CREATION ===========

    public boolean isWorldDisabled(String world) {
        return DISABLED_WORLDS.contains(world.toLowerCase());
    }

    public int getMaxClaims(Player player) {
        // Check from highest to lowest
        for (int i = 20; i >= 1; i--) {
            if (player.hasPermission("prismclaims.limit." + i)) {
                return i;
            }
        }
        return 2; // fallback default
    }

    public int getPlayerClaimCount(UUID player) {
        return (int) claims.stream().filter(c -> c.getOwner().equals(player)).count();
    }

    public List<Claim> getPlayerClaims(UUID player) {
        return claims.stream().filter(c -> c.getOwner().equals(player)).toList();
    }

    public Claim getClaimByName(UUID owner, String name) {
        return claims.stream()
                .filter(c -> c.getOwner().equals(owner) && c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public Claim getClaimById(int id) {
        return claims.stream().filter(c -> c.getId() == id).findFirst().orElse(null);
    }

    /**
     * Find the claim that contains the given position.
     */
    public Claim getClaimAt(String world, int x, int z) {
        for (Claim claim : claims) {
            if (claim.contains(world, x, z)) {
                return claim;
            }
        }
        return null;
    }

    /**
     * Check if a proposed claim area overlaps with any existing claim.
     */
    public Claim getOverlappingClaim(String world, int minX, int minZ, int maxX, int maxZ) {
        for (Claim claim : claims) {
            if (!claim.getWorld().equals(world)) continue;
            if (maxX < claim.getMinX() || minX > claim.getMaxX()) continue;
            if (maxZ < claim.getMinZ() || minZ > claim.getMaxZ()) continue;
            return claim; // overlap found
        }
        return null;
    }

    /**
     * Create a new claim after all validation has passed.
     */
    public Claim createClaim(String name, Player player) throws SQLException {
        int[] pos1 = firstCorner.get(player.getUniqueId());
        int[] pos2 = secondCorner.get(player.getUniqueId());
        String world = selectionWorld.get(player.getUniqueId());

        int minX = Math.min(pos1[0], pos2[0]);
        int minZ = Math.min(pos1[1], pos2[1]);
        int maxX = Math.max(pos1[0], pos2[0]);
        int maxZ = Math.max(pos1[1], pos2[1]);

        Claim claim = db.createClaim(name, player.getUniqueId(), world, minX, minZ, maxX, maxZ);
        if (claim != null) {
            claims.add(claim);
            clearSelection(player);
        }
        return claim;
    }

    /**
     * Delete a claim and remove its block tracking data.
     */
    public boolean deleteClaim(Claim claim) {
        try {
            db.deleteClaim(claim.getId());
            db.deleteBlocksInArea(claim.getWorld(), claim.getMinX(), claim.getMinZ(),
                    claim.getMaxX(), claim.getMaxZ());
            claims.remove(claim);
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to delete claim: " + e.getMessage());
            return false;
        }
    }

    // =========== PERMISSIONS ===========

    public boolean addPermission(Claim claim, UUID player) {
        try {
            db.addPermission(claim.getId(), player);
            claim.addPermission(player);
            return true;
        } catch (SQLException e) {
            logger.severe("Failed to add permission: " + e.getMessage());
            return false;
        }
    }

    public boolean removePermission(Claim claim, UUID player) {
        try {
            db.removePermission(claim.getId(), player);
            claim.removePermission(player);
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
