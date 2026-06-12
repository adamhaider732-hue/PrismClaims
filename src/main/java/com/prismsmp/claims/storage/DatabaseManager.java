package com.prismsmp.claims.storage;

import com.prismsmp.claims.models.Claim;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class DatabaseManager {

    private Connection connection;
    private final File dbFile;
    private final Logger logger;

    public DatabaseManager(File dataFolder, Logger logger) {
        this.dbFile = new File(dataFolder, "prismclaims.db");
        this.logger = logger;
    }

    public void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Enable WAL mode for better concurrent performance
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA cache_size=10000");
        }

        createTables();
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Claims table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    world TEXT NOT NULL,
                    min_x INTEGER NOT NULL,
                    min_z INTEGER NOT NULL,
                    max_x INTEGER NOT NULL,
                    max_z INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """);

            // Claim permissions table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS claim_permissions (
                    claim_id INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    PRIMARY KEY (claim_id, player_uuid),
                    FOREIGN KEY (claim_id) REFERENCES claims(id) ON DELETE CASCADE
                )
            """);

            // Placed blocks table - tracks all player-placed blocks server-wide
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS placed_blocks (
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    PRIMARY KEY (world, x, y, z)
                )
            """);

            // Index for fast spatial queries on placed blocks
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_placed_blocks_spatial
                ON placed_blocks (world, x, z, y)
            """);

            // Index for finding claims by owner
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_claims_owner
                ON claims (owner_uuid)
            """);

            // Index for spatial claim lookups
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_claims_spatial
                ON claims (world, min_x, max_x, min_z, max_z)
            """);
        }
    }

    // =========== CLAIM OPERATIONS ===========

    public Claim createClaim(String name, UUID owner, String world,
                              int minX, int minZ, int maxX, int maxZ) throws SQLException {
        String sql = "INSERT INTO claims (name, owner_uuid, world, min_x, min_z, max_x, max_z, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        long now = System.currentTimeMillis();

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, owner.toString());
            ps.setString(3, world);
            ps.setInt(4, minX);
            ps.setInt(5, minZ);
            ps.setInt(6, maxX);
            ps.setInt(7, maxZ);
            ps.setLong(8, now);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                return new Claim(id, name, owner, world, minX, minZ, maxX, maxZ, now);
            }
        }
        return null;
    }

    public void deleteClaim(int claimId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE id = ?")) {
            ps.setInt(1, claimId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claim_permissions WHERE claim_id = ?")) {
            ps.setInt(1, claimId);
            ps.executeUpdate();
        }
    }

    public List<Claim> loadAllClaims() throws SQLException {
        List<Claim> claims = new ArrayList<>();
        String sql = "SELECT id, name, owner_uuid, world, min_x, min_z, max_x, max_z, created_at FROM claims";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Claim claim = new Claim(
                        rs.getInt("id"),
                        rs.getString("name"),
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("world"),
                        rs.getInt("min_x"),
                        rs.getInt("min_z"),
                        rs.getInt("max_x"),
                        rs.getInt("max_z"),
                        rs.getLong("created_at")
                );
                claims.add(claim);
            }
        }

        // Load permissions for each claim
        String permSql = "SELECT claim_id, player_uuid FROM claim_permissions";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(permSql)) {
            while (rs.next()) {
                int claimId = rs.getInt("claim_id");
                UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                claims.stream()
                        .filter(c -> c.getId() == claimId)
                        .findFirst()
                        .ifPresent(c -> c.addPermission(playerUuid));
            }
        }

        return claims;
    }

    public void addPermission(int claimId, UUID player) throws SQLException {
        String sql = "INSERT OR IGNORE INTO claim_permissions (claim_id, player_uuid) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, claimId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    public void removePermission(int claimId, UUID player) throws SQLException {
        String sql = "DELETE FROM claim_permissions WHERE claim_id = ? AND player_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, claimId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }

    // =========== BLOCK TRACKING OPERATIONS ===========

    public void trackBlock(String world, int x, int y, int z, UUID player) throws SQLException {
        String sql = "INSERT OR REPLACE INTO placed_blocks (world, x, y, z, player_uuid) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.setString(5, player.toString());
            ps.executeUpdate();
        }
    }

    public void untrackBlock(String world, int x, int y, int z) throws SQLException {
        String sql = "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        }
    }

    public void moveBlock(String world, int fromX, int fromY, int fromZ,
                           int toX, int toY, int toZ) throws SQLException {
        // Get the player who placed the original block
        String selectSql = "SELECT player_uuid FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
        String deleteSql = "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
        String insertSql = "INSERT OR REPLACE INTO placed_blocks (world, x, y, z, player_uuid) VALUES (?, ?, ?, ?, ?)";

        String playerUuid = null;
        try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
            ps.setString(1, world);
            ps.setInt(2, fromX);
            ps.setInt(3, fromY);
            ps.setInt(4, fromZ);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                playerUuid = rs.getString("player_uuid");
            }
        }

        if (playerUuid == null) return;

        try (PreparedStatement ps = connection.prepareStatement(deleteSql)) {
            ps.setString(1, world);
            ps.setInt(2, fromX);
            ps.setInt(3, fromY);
            ps.setInt(4, fromZ);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setString(1, world);
            ps.setInt(2, toX);
            ps.setInt(3, toY);
            ps.setInt(4, toZ);
            ps.setString(5, playerUuid);
            ps.executeUpdate();
        }
    }

    public boolean isBlockTracked(String world, int x, int y, int z) throws SQLException {
        String sql = "SELECT 1 FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeQuery().next();
        }
    }

    public UUID getBlockPlacer(String world, int x, int y, int z) throws SQLException {
        String sql = "SELECT player_uuid FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("player_uuid"));
            }
        }
        return null;
    }

    /**
     * Check if any authorized player-placed block exists near the given position.
     * This determines if a position is within a "protected zone" inside a claim.
     *
     * Protection radius: 16 blocks horizontal, 16 up, 5 down from each placed block.
     * From target position P, we need a placed block B where:
     *   |P.x - B.x| <= 16, |P.z - B.z| <= 16,
     *   B.y >= P.y - 16 (block can be up to 16 below P, meaning P is 16 above B)
     *   B.y <= P.y + 5 (block can be up to 5 above P, meaning P is 5 below B)
     */
    public boolean hasNearbyAuthorizedBlock(String world, int px, int py, int pz,
                                             Set<UUID> authorizedPlayers) throws SQLException {
        String sql = """
            SELECT 1 FROM placed_blocks
            WHERE world = ?
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            AND y BETWEEN ? AND ?
            AND player_uuid IN (%s)
            LIMIT 1
        """;

        // Build IN clause for authorized players
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < authorizedPlayers.size(); i++) {
            if (i > 0) inClause.append(",");
            inClause.append("?");
        }
        sql = String.format(sql, inClause.toString());

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, world);
            ps.setInt(idx++, px - 16);
            ps.setInt(idx++, px + 16);
            ps.setInt(idx++, pz - 16);
            ps.setInt(idx++, pz + 16);
            ps.setInt(idx++, py - 16); // B can be up to 16 below P
            ps.setInt(idx++, py + 5);  // B can be up to 5 above P

            for (UUID uuid : authorizedPlayers) {
                ps.setString(idx++, uuid.toString());
            }

            return ps.executeQuery().next();
        }
    }

    /**
     * Check if any player-placed block exists within 16 blocks of a position.
     * Used for the claim creation requirement.
     */
    public boolean hasPlayerBlockNear(String world, int x, int z, UUID player) throws SQLException {
        String sql = """
            SELECT 1 FROM placed_blocks
            WHERE world = ? AND player_uuid = ?
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            LIMIT 1
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setString(2, player.toString());
            ps.setInt(3, x - 16);
            ps.setInt(4, x + 16);
            ps.setInt(5, z - 16);
            ps.setInt(6, z + 16);
            return ps.executeQuery().next();
        }
    }

    /**
     * Delete all placed block records within a claim's boundaries.
     * Called when a claim is deleted with /delclaim.
     */
    public void deleteBlocksInArea(String world, int minX, int minZ, int maxX, int maxZ) throws SQLException {
        String sql = "DELETE FROM placed_blocks WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, minX);
            ps.setInt(3, maxX);
            ps.setInt(4, minZ);
            ps.setInt(5, maxZ);
            ps.executeUpdate();
        }
    }

    /**
     * Batch track multiple blocks (used for tree growth, etc.)
     */
    public void trackBlocksBatch(String world, List<int[]> positions, UUID player) throws SQLException {
        String sql = "INSERT OR REPLACE INTO placed_blocks (world, x, y, z, player_uuid) VALUES (?, ?, ?, ?, ?)";
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int[] pos : positions) {
                ps.setString(1, world);
                ps.setInt(2, pos[0]);
                ps.setInt(3, pos[1]);
                ps.setInt(4, pos[2]);
                ps.setString(5, player.toString());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("Error closing database: " + e.getMessage());
        }
    }

    public Connection getConnection() {
        return connection;
    }
}
