package org.unitedlands.restoration.integrations;

import org.bukkit.Chunk;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.WorldCoord;

public class TownyIntegration implements GeopolIntegration {

    @Override
    public boolean hasSettlementInChunk(Chunk chunk) {
        var worldCoord = new WorldCoord(chunk.getWorld(), chunk.getX(), chunk.getZ());
        return !TownyAPI.getInstance().isWilderness(worldCoord);
    }

}
