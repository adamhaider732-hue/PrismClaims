package com.prismsmp.claims.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Claim {

    private final int id;
    private final String name;
    private final UUID owner;
    private final String world;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int minY;
    private final int maxY;
    private final long createdAt;
    private final Set<UUID> permittedPlayers;

    // Original constructor (backward compat - full height)
    public Claim(int id, String name, UUID owner, String world,
                 int minX, int minZ, int maxX, int maxZ, long createdAt) {
        this(id, name, owner, world, minX, minZ, maxX, maxZ, -64, 320, createdAt);
    }

    // New constructor with Y range
    public Claim(int id, String name, UUID owner, String world,
                 int minX, int minZ, int maxX, int maxZ,
                 int minY, int maxY, long createdAt) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.minY = minY;
        this.maxY = maxY;
        this.createdAt = createdAt;
        this.permittedPlayers = new HashSet<>();
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public UUID getOwner() { return owner; }
    public String getWorld() { return world; }
    public int getMinX() { return minX; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxZ() { return maxZ; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public long getCreatedAt() { return createdAt; }
    public Set<UUID> getPermittedPlayers() { return permittedPlayers; }

    // Original 2D contains (backward compat - ignores Y)
    public boolean contains(String world, int x, int z) {
        return this.world.equals(world) &&
               x >= minX && x <= maxX &&
               z >= minZ && z <= maxZ;
    }

    // New 3D contains (checks Y range)
    public boolean contains(String world, int x, int y, int z) {
        return this.world.equals(world) &&
               x >= minX && x <= maxX &&
               z >= minZ && z <= maxZ &&
               y >= minY && y <= maxY;
    }

    public boolean isAuthorized(UUID player) {
        return owner.equals(player) || permittedPlayers.contains(player);
    }

    public int getWidth() { return maxX - minX + 1; }
    public int getLength() { return maxZ - minZ + 1; }

    public void addPermission(UUID player) {
        permittedPlayers.add(player);
    }

    public void removePermission(UUID player) {
        permittedPlayers.remove(player);
    }
}
