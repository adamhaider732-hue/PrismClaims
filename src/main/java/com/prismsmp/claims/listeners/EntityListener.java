package com.prismsmp.claims.listeners;

import com.prismsmp.claims.managers.ClaimManager;
import com.prismsmp.claims.managers.ProtectionManager;
import com.prismsmp.claims.storage.DatabaseManager;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.logging.Logger;

public class EntityListener implements Listener {

    private final ClaimManager claimManager;
    private final ProtectionManager protectionManager;
    private final DatabaseManager db;
    private final Logger logger;

    public EntityListener(ClaimManager claimManager, ProtectionManager protectionManager,
                           DatabaseManager db, Logger logger) {
        this.claimManager = claimManager;
        this.protectionManager = protectionManager;
        this.db = db;
        this.logger = logger;
    }

    /**
     * Protect villagers and tamed pets inside claims from player damage.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();

        // Only protect villagers and tamed animals
        if (!isProtectedEntity(victim)) return;

        // Check if entity is inside a claim
        Location loc = victim.getLocation();
        var claim = claimManager.getClaimAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        if (claim == null) return;

        // Get the attacker (handle projectiles too)
        Player attacker = getPlayerAttacker(event.getDamager());
        if (attacker == null) {
            // Non-player damage - protect tamed pets from all sources in claims
            if (victim instanceof Tameable tameable && tameable.isTamed()) {
                event.setCancelled(true);
            }
            return;
        }

        // Staff bypass
        if (attacker.hasPermission("prismclaims.bypass")) return;

        // Only claim owner and permitted players can damage protected entities
        if (!claim.isAuthorized(attacker.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendMessage(net.kyori.adventure.text.Component.text(
                    "This entity is protected by a claim.", net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * Protect villagers and tamed pets from environmental damage inside claims.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (!isProtectedEntity(victim)) return;

        // Skip if this is an entity-vs-entity event (handled above)
        if (event instanceof EntityDamageByEntityEvent) return;

        Location loc = victim.getLocation();
        var claim = claimManager.getClaimAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        if (claim == null) return;

        EntityDamageEvent.DamageCause cause = event.getCause();

        // Block environmental damage that could be used for griefing
        switch (cause) {
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION, FIRE, FIRE_TICK, LAVA,
                 LIGHTNING, SUFFOCATION, CRAMMING -> event.setCancelled(true);
            default -> { /* allow other damage types like fall damage */ }
        }
    }

    /**
     * Prevent endermen from picking up protected blocks inside claims.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEndermanPickup(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;

        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (protectionManager.isBlockProtectedFromEnvironment(world, x, y, z)) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevent wither from destroying protected blocks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWitherBlockDamage(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Wither)) return;

        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (protectionManager.isBlockProtectedFromEnvironment(world, x, y, z)) {
            event.setCancelled(true);
        }
    }

    // =========== HELPERS ===========

    private boolean isProtectedEntity(Entity entity) {
        if (entity instanceof Villager) return true;
        if (entity instanceof AbstractVillager) return true;
        if (entity instanceof Tameable tameable) return tameable.isTamed();
        if (entity instanceof IronGolem) return true;
        if (entity instanceof SnowGolem) return true;
        return false;
    }

    private Player getPlayerAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player player) return player;
        }
        if (damager instanceof TNTPrimed tnt) {
            if (tnt.getSource() instanceof Player player) return player;
        }
        return null;
    }
}
