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

    public DatabaseManager(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    public void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        createTables();
        migrateYColumns();
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
                    min_y INTEGER NOT NULL DEFAULT -64,
                    max_y INTEGER NOT NULL DEFAULT 320,
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

            // Block tracking table
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

            // Index for faster lookups
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_blocks_location ON placed_blocks(world, x, y, z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_blocks_area ON placed_blocks(world, x, z)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_claims_world ON claims(world)");
        }
    }

    // Migration: add min_y and max_y columns if they don't exist (for existing databases)
    private void migrateYColumns() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(claims)");
            boolean hasMinY = false;
            boolean hasMaxY = false;
            while (rs.next()) {
                String colName = rs.getString("name");
                if ("min_y".equals(colName)) hasMinY = true;
                if ("max_y".equals(colName)) hasMaxY = true;
            }
            if (!hasMinY) {
                stmt.execute("ALTER TABLE claims ADD COLUMN min_y INTEGER NOT NULL DEFAULT -64");
                logger.info("Migrated claims table: added min_y column");
            }
            if (!hasMaxY) {
                stmt.execute("ALTER TABLE claims ADD COLUMN max_y INTEGER NOT NULL DEFAULT 320");
                logger.info("Migrated claims table: added max_y column");
            }
        } catch (SQLException e) {
            logger.warning("Y column migration check: " + e.getMessage());
        }
    }

    // =========== CLAIM OPERATIONS ===========

    public Claim createClaim(String name, UUID owner, String world,
                             int minX, int minZ, int maxX, int maxZ) throws SQLException {
        return createClaim(name, owner, world, minX, minZ, maxX, maxZ, -64, 320);
    }

    public Claim createClaim(String name, UUID owner, String world,
                             int minX, int minZ, int maxX, int maxZ,
                             int minY, int maxY) throws SQLException {
        String sql = "INSERT INTO claims (name, owner_uuid, world, min_x, min_z, max_x, max_z, min_y, max_y, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, owner.toString());
            ps.setString(3, world);
            ps.setInt(4, minX);
            ps.setInt(5, minZ);
            ps.setInt(6, maxX);
            ps.setInt(7, maxZ);
            ps.setInt(8, minY);
            ps.setInt(9, maxY);
            ps.setLong(10, now);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                return new Claim(id, name, owner, world, minX, minZ, maxX, maxZ, minY, maxY, now);
            }
        }
        return null;
    }

    public void deleteClaim(int id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claim_permissions WHERE claim_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public List<Claim> loadAllClaims() throws SQLException {
        List<Claim> claims = new ArrayList<>();
        Map<Integer, Claim> claimMap = new HashMap<>();

        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM claims");
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                String world = rs.getString("world");
                int minX = rs.getInt("min_x");
                int minZ = rs.getInt("min_z");
                int maxX = rs.getInt("max_x");
                int maxZ = rs.getInt("max_z");
                int minY = rs.getInt("min_y");
                int maxY = rs.getInt("max_y");
                long createdAt = rs.getLong("created_at");

                Claim claim = new Claim(id, name, owner, world, minX, minZ, maxX, maxZ, minY, maxY, createdAt);
                claims.add(claim);
                claimMap.put(id, claim);
            }
        }

        // Load permissions
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM claim_permissions");
            while (rs.next()) {
                int claimId = rs.getInt("claim_id");
                UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                Claim claim = claimMap.get(claimId);
                if (claim != null) {
                    claim.addPermission(playerUuid);
                }
            }
        }

        return claims;
    }

    // =========== PERMISSION OPERATIONS ===========

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

    // =========== BLOCK TRACKING ===========

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
        // Get the placer of the original block
        UUID placer = getBlockPlacer(world, fromX, fromY, fromZ);
        if (placer != null) {
            untrackBlock(world, fromX, fromY, fromZ);
            trackBlock(world, toX, toY, toZ, placer);
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

    public boolean hasNearbyAuthorizedBlock(String world, int x, int y, int z,
                                            Set<UUID> authorizedPlayers) throws SQLException {
        // Check within 16 blocks horizontally and 16 up / 5 down
        String sql = """
            SELECT 1 FROM placed_blocks
            WHERE world = ? AND player_uuid IN (%s)
            AND x BETWEEN ? AND ?
            AND z BETWEEN ? AND ?
            AND y BETWEEN ? AND ?
            LIMIT 1
        """;

        if (authorizedPlayers.isEmpty()) return false;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < authorizedPlayers.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("?");
        }

        String formattedSql = String.format(sql, placeholders);
        try (PreparedStatement ps = connection.prepareStatement(formattedSql)) {
            int idx = 1;
            ps.setString(idx++, world);
            for (UUID uuid : authorizedPlayers) {
                ps.setString(idx++, uuid.toString());
            }
            ps.setInt(idx++, x - 16);
            ps.setInt(idx++, x + 16);
            ps.setInt(idx++, z - 16);
            ps.setInt(idx++, z + 16);
            ps.setInt(idx++, y - 5);
            ps.setInt(idx++, y + 16);
            return ps.executeQuery().next();
        }
    }

    public boolean hasPlayerBlockNear(String world, int x, int z, UUID player) throws SQLException {
        String sql = """
            SELECT 1 FROM placed_blocks
            WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND player_uuid = ?
            LIMIT 1
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x - 16);
            ps.setInt(3, x + 16);
            ps.setInt(4, z - 16);
            ps.setInt(5, z + 16);
            ps.setString(6, player.toString());
            return ps.executeQuery().next();
        }
    }

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

    public void trackBlocksBatch(String world, List<int[]> positions, UUID player) throws SQLException {
        String sql = "INSERT OR REPLACE INTO placed_blocks (world, x, y, z, player_uuid) VALUES (?, ?, ?, ?, ?)";
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
