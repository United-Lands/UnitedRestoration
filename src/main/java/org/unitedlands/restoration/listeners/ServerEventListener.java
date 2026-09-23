package org.unitedlands.restoration.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.restoration.classes.ChunkCloneManager;
import org.unitedlands.utils.United;

public class ServerEventListener implements Listener {

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {

        if (UnitedRestoration.instance().getConfig().getBoolean("current-task.running", false)) {
            United.logger().info("Unfinished clone task found, resuming...");
            ChunkCloneManager.instance().resumeCloning();
        }
    }

}
