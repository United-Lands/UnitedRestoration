package org.unitedlands.restoration;

import java.sql.SQLException;
import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;
import org.unitedlands.restoration.classes.ChunkSnapshotManager;
import org.unitedlands.restoration.commands.AdminCommands;
import org.unitedlands.restoration.utils.MessageProvider;

public class UnitedRestoration extends JavaPlugin {

    private static UnitedRestoration instance;

    private MessageProvider messageProvider;

    private ChunkSnapshotManager chunkSnapshotManager;

    @Override
    public void onEnable() {

        instance = this;
        
        saveDefaultConfig();

        messageProvider = new MessageProvider(getConfig());try {
            chunkSnapshotManager = new ChunkSnapshotManager(this);
        } catch (SQLException e) {
            getLogger().severe("Could not open snapshot database! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
        }
        
        var adminCommands = new AdminCommands(this, messageProvider);
        Objects.requireNonNull(getCommand("unitedrestoration")).setExecutor(adminCommands);
        Objects.requireNonNull(getCommand("unitedrestoration")).setTabCompleter(adminCommands);

        getLogger().info("UnitedRestoration initialized.");
    }

    @Override
    public void onDisable() {
        if (chunkSnapshotManager != null) chunkSnapshotManager.close();
    }

    public ChunkSnapshotManager getChunkSnapshotManager() {
        return chunkSnapshotManager;
    }

    public static UnitedRestoration getInstance() {
        return instance;
    }

}
