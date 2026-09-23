package org.unitedlands.restoration.classes;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.utils.United;

public class ChunkCloneManager {

    private static ChunkCloneManager instance;

    public static ChunkCloneManager instance() {
        return instance;
    }

    public ChunkCloneManager() {
        instance = this;
    }

    private boolean running = false;

    public void startCloning(World sourceWorld, World targetWorld, int x1, int z1, int x2, int z2, int startIndex, boolean copyBiomes, boolean onlyBiomes) {

        var total = (Math.abs(x1 - x2) + 1) * (Math.abs(z1 - z2) + 1);
        int chunksPerTick = Math.max(1, UnitedRestoration.instance().getConfig().getInt("chunk-copy.chunks-per-tick", 1));

        United.logger().info("Initiating copy at " + chunksPerTick + " cpt...");

        List<int[]> chunkQueue = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                chunkQueue.add(new int[] { x, z });
            }
        }

        saveTask(sourceWorld, targetWorld, x1, z1, x2, z2, startIndex, copyBiomes, onlyBiomes);

        running = true;
        scheduleChunkBatch(sourceWorld, targetWorld, chunkQueue, startIndex, total, chunksPerTick, copyBiomes, onlyBiomes);
    }

    public void stopCloning() {
        running = false;

        var config = UnitedRestoration.instance().getConfig();
        config.set("current-task.running", false);
        UnitedRestoration.instance().saveConfig();
    }

    public void resumeCloning() {

        UnitedRestoration.instance().reloadConfig();
        var config = UnitedRestoration.instance().getConfig();

        var sourceWorld = Bukkit.getWorld(config.getString("current-task.source-world"));
        var targetWorld = Bukkit.getWorld(config.getString("current-task.target-world"));
        var x1 = config.getInt("current-task.x1");
        var z1 = config.getInt("current-task.z1");
        var x2 = config.getInt("current-task.x2");
        var z2 = config.getInt("current-task.z2");
        var startIndex = config.getInt("current-task.current-index");
        boolean copyBiomes = config.getBoolean("current-task.copy-biomes");
        boolean onlyBiomes = config.getBoolean("current-task.only-biomes");

        startCloning(sourceWorld, targetWorld, x1, z1, x2, z2, startIndex, copyBiomes, onlyBiomes);
    }

    private void saveTask(World sourceWorld, World targetWorld, int x1, int z1, int x2, int z2, int startIndex, boolean copyBiomes, boolean onlyBiomes) {
        var config = UnitedRestoration.instance().getConfig();
        config.set("current-task.running", true);
        config.set("current-task.source-world", sourceWorld.getName());
        config.set("current-task.target-world", targetWorld.getName());
        config.set("current-task.x1", x1);
        config.set("current-task.z1", z1);
        config.set("current-task.x2", x2);
        config.set("current-task.z2", z2);
        config.set("current-task.current-index", startIndex);
        config.set("current-task.copy-biomes", copyBiomes);
        config.set("current-task.only-biomes", onlyBiomes);
        UnitedRestoration.instance().saveConfig();
    }

    private void updateTask(int currentIdex) {
        var config = UnitedRestoration.instance().getConfig();
        config.set("current-task.current-index", currentIdex);
        UnitedRestoration.instance().saveConfig();
    }

    public void scheduleChunkBatch(World fromWorld, World toWorld, List<int[]> chunkQueue, int startIndex, int total, int chunksPerTick, boolean copyBiomes,
            boolean onlyBiomes) {

        if (!running)
            return;

        int endIndex = Math.min(startIndex + chunksPerTick, chunkQueue.size());

        for (int i = startIndex; i < endIndex; i++) {
            int[] chunk = chunkQueue.get(i);
            int chunkX = chunk[0];
            int chunkZ = chunk[1];
            int processed = i + 1;

            if (processed % 100 == 0) {

                var cpt = UnitedRestoration.instance().getConfig().getInt("chunk-copy.chunks-per-tick", 1);
                var remaining = total - processed;
                var remainingTicks = remaining / cpt;
                var remainingMilliseconds = remainingTicks * 50;

                United.logger()
                        .info("Processing chunk " + chunkX + ", " + chunkZ + " (" + processed + " of " + total + ", "
                                + String.format("%.2f", 100 * ((double) processed / (double) total)) + "%)"
                                + (copyBiomes ? " (with biomes) " : " (without biomes) " + (onlyBiomes ? " (only biomes) " : "")) + " | ETA: "
                                + United.formatter().formatDuration(remainingMilliseconds) + " for " + cpt + " CPT @ 20 TPS");
                updateTask(i);
            }

            copyChunk(fromWorld, toWorld, chunkX, chunkZ, copyBiomes, onlyBiomes);
        }

        if (endIndex < chunkQueue.size()) {
            Bukkit.getScheduler().runTaskLater(UnitedRestoration.instance(),
                    () -> scheduleChunkBatch(fromWorld, toWorld, chunkQueue, endIndex, total, chunksPerTick, copyBiomes, onlyBiomes), 1L);
        } else {
            stopCloning();
            United.logger().info("Finished copying " + total + " chunks.");
        }
    }

    private void copyChunk(World fromWorld, World toWorld, int chunkX, int chunkZ, boolean copyBiomes, boolean onlyBiomes) {
        var fromChunk = fromWorld.getChunkAt(chunkX, chunkZ);
        fromChunk.load();

        var minheight = fromWorld.getMinHeight();
        var maxHeight = fromWorld.getMaxHeight();

        var toChunk = toWorld.getChunkAt(chunkX, chunkZ);

        boolean copyBlockData = true;
        if (UnitedRestoration.instance().isUsingGeopolIntegration()) {
            if (UnitedRestoration.instance().getGeopolIntegration().hasSettlementInChunk(toChunk)) {
                United.logger().info("Town block found at " + toChunk.toString() + ", skipping block data copy.");
                copyBlockData = false;

                if (!onlyBiomes)
                    return;
            }
        }

        toChunk.load();

        for (int block_x = 0; block_x < 16; block_x++) {
            for (int block_z = 0; block_z < 16; block_z++) {
                for (int block_y = 0; block_y < maxHeight; block_y++) {
                    int worldY = minheight + block_y;

                    var fromBlock = fromChunk.getBlock(block_x, worldY, block_z);
                    var toBlock = toChunk.getBlock(block_x, worldY, block_z);

                    if (onlyBiomes) {
                        toBlock.setBiome(fromBlock.getBiome());
                    } else {
                        if (copyBlockData) {
                            toBlock.setBlockData(fromBlock.getBlockData(), false);
                        }
                        if (copyBiomes) {
                            toBlock.setBiome(fromBlock.getBiome());
                        }
                    }

                }
            }
        }

        toWorld.refreshChunk(chunkX, chunkZ);
    }

}
