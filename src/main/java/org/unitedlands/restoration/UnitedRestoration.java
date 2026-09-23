package org.unitedlands.restoration;

import java.sql.SQLException;
import org.bukkit.plugin.java.JavaPlugin;
import org.unitedlands.restoration.classes.ChunkCloneManager;
import org.unitedlands.restoration.classes.ChunkSnapshotManager;
import org.unitedlands.restoration.integrations.GeopolIntegration;
import org.unitedlands.restoration.integrations.TownyIntegration;
import org.unitedlands.restoration.listeners.ServerEventListener;
import org.unitedlands.utils.United;

public class UnitedRestoration extends JavaPlugin {

    private static UnitedRestoration instance;

    private ChunkCloneManager chunkCloneManager;
    private ChunkSnapshotManager chunkSnapshotManager;

    private GeopolIntegration geopolIntegration;
    private boolean usingGeopolIntegration = false;

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig();

        try {
            chunkSnapshotManager = new ChunkSnapshotManager(this);
        } catch (SQLException e) {
            United.logger().error("Could not open snapshot database! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
        }

        chunkCloneManager = new ChunkCloneManager();

        loadIntegrations();

        getServer().getPluginManager().registerEvents(new ServerEventListener(), this);


        United.logger().info("UnitedRestoration initialized.");
    }

    private void loadIntegrations() {
        var towny = getServer().getPluginManager().getPlugin("Towny");
        if (towny != null && towny.isEnabled()) {
            geopolIntegration = new TownyIntegration();
            usingGeopolIntegration = true;
            United.logger().info("Found Towny, enabling integration...");
        }
    }

    @Override
    public void onDisable() {
        if (chunkSnapshotManager != null)
            chunkSnapshotManager.close();
    }

    public ChunkSnapshotManager getChunkSnapshotManager() {
        return chunkSnapshotManager;
    }

    public ChunkCloneManager getChunkCloneManager() {
        return chunkCloneManager;
    }

    public static UnitedRestoration instance() {
        return instance;
    }

    public GeopolIntegration getGeopolIntegration() {
        return geopolIntegration;
    }

    public boolean isUsingGeopolIntegration() {
        return usingGeopolIntegration;
    }

}
