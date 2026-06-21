package com.prismsmp.claims.managers;

import com.prismsmp.claims.models.Claim;
import com.prismsmp.claims.storage.DatabaseManager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public class ProtectionManager {

    private final ClaimManager claimManager;
    private final DatabaseManager db;
    private final Logger logger;

    public ProtectionManager(ClaimManager claimManager, DatabaseManager db, Logger logger) {
        this.claimManager = claimManager;
        this.db = db;
        this.logger = logger;
    }

    // =========== PROTECTION RESULT ===========

    public static class ProtectionResult {
        private final boolean blocked;
        private final Claim claim;
        private final String reason;

        public ProtectionResult(boolean blocked, Claim claim, String reason) {
            this.blocked = blocked;
            this.claim = claim;
            this.reason = reason;
        }

        public boolean isBlocked() { return blocked; }
        public Claim getClaim() { return claim; }
        public String getReason() { return reason; }

        public static ProtectionResult allowed() {
            return new ProtectionResult(false, null, null);
        }

        public static ProtectionResult denied(Claim claim, String reason) {
            return new ProtectionResult(true, claim, reason);
        }
    }

    // =========== PROTECTION CHECKS ===========

    public ProtectionResult checkProtection(Location loc) {
        return checkProtection(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public ProtectionResult checkProtection(String world, int x, int y, int z) {
        // Use 3D lookup so large claims with Y restrictions are respected
        Claim claim = claimManager.getClaimAt(world, x, y, z);
        if (claim == null) {
            return ProtectionResult.allowed();
        }

        // Block is in a claim - check if it's a player-placed block
        try {
            if (db.isBlockTracked(world, x, y, z)) {
                return ProtectionResult.denied(claim, "protected");
            }
        } catch (SQLException e) {
            logger.warning("Error checking block tracking: " + e.getMessage());
            // Err on the side of protection
            return ProtectionResult.denied(claim, "error");
        }

        return ProtectionResult.allowed();
    }

    public boolean isInProtectedZone(Claim claim, String world, int x, int y, int z) {
        if (claim == null) return false;
        try {
            // Check if there's a tracked block nearby that belongs to
            // the claim owner or a permitted player
            Set<UUID> authorized = claim.getPermittedPlayers();
            authorized.add(claim.getOwner());
            return db.hasNearbyAuthorizedBlock(world, x, y, z, authorized);
        } catch (SQLException e) {
            logger.warning("Error checking protected zone: " + e.getMessage());
            return true; // err on side of protection
        }
    }

    public boolean canBreakBlock(Player player, Location loc) {
        if (player.hasPermission("prismclaims.bypass")) return true;

        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // Use 3D lookup
        Claim claim = claimManager.getClaimAt(world, x, y, z);
        if (claim == null) return true;

        // Owner and permitted players can break
        if (claim.isAuthorized(player.getUniqueId())) return true;

        // Check if the block is tracked (player-placed)
        try {
            return !db.isBlockTracked(world, x, y, z);
        } catch (SQLException e) {
            logger.warning("Error in canBreakBlock: " + e.getMessage());
            return false; // err on side of protection
        }
    }

    public boolean canPlaceBlock(Player player, Location loc) {
        if (player.hasPermission("prismclaims.bypass")) return true;

        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // Use 3D lookup
        Claim claim = claimManager.getClaimAt(world, x, y, z);
        if (claim == null) return true;

        // Owner and permitted players can place
        return claim.isAuthorized(player.getUniqueId());
    }

    public boolean isBlockProtectedFromEnvironment(String world, int x, int y, int z) {
        // Use 3D lookup
        Claim claim = claimManager.getClaimAt(world, x, y, z);
        if (claim == null) return false;

        try {
            return db.isBlockTracked(world, x, y, z);
        } catch (SQLException e) {
            logger.warning("Error checking environment protection: " + e.getMessage());
            return true;
        }
    }

    public boolean isEntityProtectedInClaim(Location loc) {
        String world = loc.getWorld().getName();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        // Use 3D lookup
        Claim claim = claimManager.getClaimAt(world, x, y, z);
        return claim != null;
    }
}
