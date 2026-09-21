package org.unitedlands.restoration.commands.handlers;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.unitedlands.classes.BaseCommandHandler;
import org.unitedlands.interfaces.IMessageProvider;
import org.unitedlands.restoration.UnitedRestoration;

public class CloneChunks extends BaseCommandHandler<UnitedRestoration> {

    public CloneChunks(UnitedRestoration plugin, IMessageProvider messageProvider) {
        super(plugin, messageProvider);
    }

    @Override
    public List<String> handleTab(CommandSender sender, String[] args) {
        return null;
    }

    @Override
    public void handleCommand(CommandSender sender, String[] args) {

        // ---- Resolve target chunk ----
        World fromWorld;
        World toWorld;
        int chunkX1 = 0;
        int chunkZ1 = 0;
        int chunkX2 = 0;
        int chunkZ2 = 0;

        if (args.length < 2) {
            sender.sendMessage("Usage: /restorechunks <world1> <world2> [x1 z1 x2 z2] [--noBiomes]");
            return;
        } else {
            // Explicit coordinates — works from console or by players.
            fromWorld = Bukkit.getWorld(args[0]);
            toWorld = Bukkit.getWorld(args[1]);
            if (fromWorld == null) {
                sender.sendMessage("§cUnknown world: " + args[0]);
                return;
            }
            if (toWorld == null) {
                sender.sendMessage("§cUnknown world: " + args[1]);
                return;
            }

            if (args.length == 6) {
                try {
                    chunkX1 = Integer.parseInt(args[2]);
                    chunkZ1 = Integer.parseInt(args[3]);
                    chunkX2 = Integer.parseInt(args[4]);
                    chunkZ2 = Integer.parseInt(args[5]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cChunk coordinates must be integers.");
                    return;
                }
            } else {
                if (sender instanceof Player player) {
                    chunkX1 = player.getLocation().getChunk().getX();
                    chunkX2 = player.getLocation().getChunk().getX();
                    chunkZ1 = player.getLocation().getChunk().getZ();
                    chunkZ2 = player.getLocation().getChunk().getZ();
                } else {
                    sender.sendMessage("§cConsole must provide chunk coordinates.");
                    return;
                }
            }

            if (chunkX1 > chunkX2 || chunkZ1 > chunkZ2) {
                sender.sendMessage("§cInvalid area.");
                return;
            }

        }

        var total = (Math.abs(chunkX1 - chunkX2) + 1) * (Math.abs(chunkZ1 - chunkZ2) + 1);
        int chunksPerTick = Math.max(1, plugin.getConfig().getInt("chunk-copy.chunks-per-tick", 1));
        plugin.getLogger().info("Initiating copy at " + chunksPerTick + " cpt...");

        List<int[]> chunkQueue = new ArrayList<>();
        for (int x = chunkX1; x <= chunkX2; x++) {
            for (int z = chunkZ1; z <= chunkZ2; z++) {
                chunkQueue.add(new int[] { x, z });
            }
        }

        var copyBiomes = true;
        if (args[args.length - 1].equals("--noBiomes"))
            copyBiomes = false;

        scheduleChunkBatch(fromWorld, toWorld, chunkQueue, 0, total, chunksPerTick, copyBiomes);
        return;
    }

    private void scheduleChunkBatch(World fromWorld,
            World toWorld,
            List<int[]> chunkQueue,
            int startIndex,
            int total,
            int chunksPerTick,
            boolean biomes) {
        int endIndex = Math.min(startIndex + chunksPerTick, chunkQueue.size());

        for (int i = startIndex; i < endIndex; i++) {
            int[] chunk = chunkQueue.get(i);
            int chunkX = chunk[0];
            int chunkZ = chunk[1];
            int processed = i + 1;

            if (processed % 100 == 0)
                plugin.getLogger().info("Processing chunk " + chunkX + ", " + chunkZ + " (" + processed + " of " + total
                        + ", " + ((double) processed / (double) total) + "%)" + (biomes ? " (with biomes)" : "(without biomes)"));
            copyChunk(fromWorld, toWorld, chunkX, chunkZ, biomes);
        }

        if (endIndex < chunkQueue.size()) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> scheduleChunkBatch(fromWorld, toWorld, chunkQueue, endIndex, total, chunksPerTick, biomes), 1L);
        } else {
            plugin.getLogger().info("Finished copying " + total + " chunks.");
        }
    }

    private void copyChunk(World fromWorld, World toWorld, int chunkX, int chunkZ, boolean biomes) {
        var fromChunk = fromWorld.getChunkAt(chunkX, chunkZ);
        fromChunk.load();

        var minheight = fromWorld.getMinHeight();
        var maxHeight = fromWorld.getMaxHeight();

        var toChunk = toWorld.getChunkAt(chunkX, chunkZ);
        toChunk.load();

        for (int block_x = 0; block_x < 16; block_x++) {
            for (int block_z = 0; block_z < 16; block_z++) {
                for (int block_y = 0; block_y < maxHeight; block_y++) {
                    int worldY = minheight + block_y;

                    var fromBlock = fromChunk.getBlock(block_x, worldY, block_z);
                    var toBlock = toChunk.getBlock(block_x, worldY, block_z);

                    toBlock.setBlockData(fromBlock.getBlockData(), false);

                    if (biomes)
                        toBlock.setBiome(fromBlock.getBiome());
                }
            }
        }

        toWorld.refreshChunk(chunkX, chunkZ);
    }

}
