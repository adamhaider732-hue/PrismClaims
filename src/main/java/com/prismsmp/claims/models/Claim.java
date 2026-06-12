package com.prismsmp.claims.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Claim {

    private final int id;
    private final String name;
    private final UUID owner;
    private final String world;
    private final int minX, minZ, maxX, maxZ;
    private final long createdAt;
    private final Set<UUID> permittedPlayers;

    public Claim(int id, String name, UUID owner, String world,
                 int x1, int z1, int x2, int z2, long createdAt) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxZ = Math.max(z1, z2);
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
    public long getCreatedAt() { return createdAt; }
    public Set<UUID> getPermittedPlayers() { return permittedPlayers; }

    public boolean contains(String worldName, int x, int z) {
        return this.world.equals(worldName)
                && x >= minX && x <= maxX
                && z >= minZ && z <= maxZ;
    }

    public boolean isAuthorized(UUID player) {
        return owner.equals(player) || permittedPlayers.contains(player);
    }

    public int getWidth() { return maxX - minX + 1; }
    public int getLength() { return maxZ - minZ + 1; }

    public void addPermission(UUID player) { permittedPlayers.add(player); }
    public void removePermission(UUID player) { permittedPlayers.remove(player); }
}
