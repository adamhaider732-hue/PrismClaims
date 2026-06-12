package com.prismsmp.claims.managers;

import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles all protection checks for the claim system.
 *
 * Protection logic:
 * - A position is "protected" if it is inside a claim AND within the protection
 *   radius of any authorized player-placed block in that claim.
 * - Protection radius: 16 blocks horizontal, 16 blocks up, 5 blocks down from
 *   each player-placed block.
 * - Only blocks placed by the claim owner or permitted players count for
 *   protection radius calculations.
 */
public class ProtectionManager {

    private final ClaimManager claimManager;
    private final DatabaseManager db;
    private final Logger logger;

    public ProtectionManager(ClaimManager claimManager, DatabaseManager db, Logger logger) {
        this.claimManager = claimManager;
        this.db = db;
        this.logger = logger;
    }

    /**
     * Result of a protection check.
     */
    public record ProtectionResult(boolean insideClaim, boolean inProtectedZone, Claim claim) {
        public static ProtectionResult OUTSIDE = new ProtectionResult(false, false, null);
    }

    /**
     * Full protection check for a position.
     */
    public ProtectionResult checkProtection(Location location) {
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        return checkProtection(world, x, y, z);
    }

    public ProtectionResult checkProtection(String world, int x, int y, int z) {
        // Check if position is in a claim
        Claim claim = claimManager.getClaimAt(world, x, z);
        if (claim == null) {
            return ProtectionResult.OUTSIDE;
        }

        // Check if position is in a protected zone (near authorized placed blocks)
        boolean isProtected = isInProtectedZone(claim, world, x, y, z);
        return new ProtectionResult(true, isProtected, claim);
    }

    /**
     * Check if a position is within the protection radius of any authorized
     * player-placed block inside the claim.
     */
    private boolean isInProtectedZone(Claim claim, String world, int x, int y, int z) {
        try {
            // Collect all authorized UUIDs for this claim
            Set<UUID> authorized = new HashSet<>();
            authorized.add(claim.getOwner());
            authorized.addAll(claim.getPermittedPlayers());

            return db.hasNearbyAuthorizedBlock(world, x, y, z, authorized);
        } catch (SQLException e) {
            logger.warning("Error checking protection zone: " + e.getMessage());
            // Fail safe: if we can't check, treat as protected
            return true;
        }
    }

    /**
     * Check if a player is allowed to break a block at a location.
     * Returns true if the break is ALLOWED.
     */
    public boolean canBreakBlock(Player player, Location location) {
        // Staff bypass
        if (player.hasPermission("prismclaims.bypass")) return true;

        ProtectionResult result = checkProtection(location);

        // Not in a claim - always allowed
        if (!result.insideClaim()) return true;

        // In a claim but not in a protected zone - allowed
        if (!result.inProtectedZone()) return true;

        // In a protected zone - only authorized players can break
        return result.claim().isAuthorized(player.getUniqueId());
    }

    /**
     * Check if a player is allowed to place a block at a location.
     * Block placement is restricted inside the ENTIRE claim, not just protected zones.
     * This prevents griefing through random block placement.
     * Returns true if the place is ALLOWED.
     */
    public boolean canPlaceBlock(Player player, Location location) {
        // Staff bypass
        if (player.hasPermission("prismclaims.bypass")) return true;

        ProtectionResult result = checkProtection(location);

        // Not in a claim - always allowed
        if (!result.insideClaim()) return true;

        // Inside a claim - only authorized players can place
        return result.claim().isAuthorized(player.getUniqueId());
    }

    /**
     * Check if a block at this location is player-placed and should be protected
     * from explosions, endermen, wither, etc.
     */
    public boolean isBlockProtectedFromEnvironment(String world, int x, int y, int z) {
        ProtectionResult result = checkProtection(world, x, y, z);
        if (!result.insideClaim()) return false;
        if (!result.inProtectedZone()) return false;

        // Check if this specific block is player-placed
        try {
            return db.isBlockTracked(world, x, y, z);
        } catch (SQLException e) {
            logger.warning("Error checking block tracking: " + e.getMessage());
            return true; // fail safe
        }
    }

    /**
     * Check if an entity at this location is inside a claim and should be protected.
     */
    public boolean isEntityProtectedInClaim(Location location) {
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int z = location.getBlockZ();

        return claimManager.getClaimAt(world, x, z) != null;
    }
}
