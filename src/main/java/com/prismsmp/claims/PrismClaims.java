package com.prismsmp.claims;

import com.prismsmp.claims.commands.*;
import com.prismsmp.claims.listeners.*;
import com.prismsmp.claims.managers.*;
import com.prismsmp.claims.storage.DatabaseManager;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class PrismClaims extends JavaPlugin {

    private DatabaseManager databaseManager;
    private ClaimManager claimManager;
    private ProtectionManager protectionManager;

    @Override
    public void onEnable() {
        // Initialize database
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        databaseManager = new DatabaseManager(getDataFolder(), getLogger());
        try {
            databaseManager.initialize();
            getLogger().info("Database initialized.");
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize managers
        claimManager = new ClaimManager(databaseManager, getLogger());
        protectionManager = new ProtectionManager(claimManager, databaseManager, getLogger());

        // Load claims from database
        claimManager.loadClaims();

        // Register commands
        ClaimCommand claimCommand = new ClaimCommand(this, claimManager, databaseManager);
        getCommand("claim").setExecutor(claimCommand);
        getCommand("claim").setTabCompleter(claimCommand);

        DelClaimCommand delClaimCommand = new DelClaimCommand(claimManager);
        getCommand("delclaim").setExecutor(delClaimCommand);
        getCommand("delclaim").setTabCompleter(delClaimCommand);

        ClaimsCommand claimsCommand = new ClaimsCommand(claimManager);
        getCommand("claims").setExecutor(claimsCommand);
        getCommand("claims").setTabCompleter(claimsCommand);

        PermissionCommand permissionCommand = new PermissionCommand(claimManager);
        getCommand("claimperm").setExecutor(permissionCommand);
        getCommand("claimperm").setTabCompleter(permissionCommand);

        AdminCommand adminCommand = new AdminCommand(claimManager);
        getCommand("claimadmin").setExecutor(adminCommand);
        getCommand("claimadmin").setTabCompleter(adminCommand);

        // Register listeners
        getServer().getPluginManager().registerEvents(
                new BlockListener(this, claimManager, protectionManager, databaseManager), this);
        getServer().getPluginManager().registerEvents(
                new EntityListener(claimManager, protectionManager, databaseManager, getLogger()), this);

        getLogger().info("PrismClaims v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Tracking player-placed blocks server-wide.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
            getLogger().info("Database connection closed.");
        }
        getLogger().info("PrismClaims disabled.");
    }

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public ClaimManager getClaimManager() { return claimManager; }
    public ProtectionManager getProtectionManager() { return protectionManager; }
}
