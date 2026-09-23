// package org.unitedlands.restoration.commands.handlers;

// import java.util.List;

// import org.bukkit.Bukkit;
// import org.bukkit.Chunk;
// import org.bukkit.World;
// import org.bukkit.command.CommandSender;
// import org.bukkit.entity.Player;
// import org.unitedlands.classes.BaseCommandHandler;
// import org.unitedlands.interfaces.IMessageProvider;
// import org.unitedlands.restoration.UnitedRestoration;
// import org.unitedlands.restoration.classes.ChunkSnapshotManager;

// public class RestoreChunk extends BaseCommandHandler<UnitedRestoration> {

//     private ChunkSnapshotManager snapshotManager;

//     public RestoreChunk(UnitedRestoration plugin, IMessageProvider messageProvider) {
//         super(plugin, messageProvider);

//         snapshotManager = plugin.getChunkSnapshotManager();
//     }

//     @Override
//     public List<String> handleTab(CommandSender sender, String[] args) {
//         return null;
//     }

//     @Override
//     public void handleCommand(CommandSender sender, String[] args) {

//         // ---- Resolve target chunk ----
//         final World world;
//         final int chunkX;
//         final int chunkZ;

//         if (args.length == 0) {
//             // No arguments — sender must be a player; use their current chunk.
//             if (!(sender instanceof Player player)) {
//                 sender.sendMessage("§cConsole must provide: /restorechunk <world> <x> <z>");
//                 return;
//             }
//             world = player.getWorld();
//             chunkX = player.getLocation().getBlockX() >> 4;
//             chunkZ = player.getLocation().getBlockZ() >> 4;

//         } else if (args.length == 3) {
//             // Explicit coordinates — works from console or by players.
//             world = Bukkit.getWorld(args[0]);
//             if (world == null) {
//                 sender.sendMessage("§cUnknown world: " + args[0]);
//                 return;
//             }
//             try {
//                 chunkX = Integer.parseInt(args[1]);
//                 chunkZ = Integer.parseInt(args[2]);
//             } catch (NumberFormatException e) {
//                 sender.sendMessage("§cChunk coordinates must be integers.");
//                 return;
//             }

//         } else {
//             sender.sendMessage("§7Usage: /urst restorechunk  or  /urst restorechunk <world> <x> <z>");
//             return;
//         }

//         // ---- Guard: snapshot must exist before we attempt anything ----
//         if (!snapshotManager.hasSnapshot(world, chunkX, chunkZ)) {
//             sender.sendMessage("§cNo snapshot found for chunk " + chunkX + "," + chunkZ
//                     + " in world '" + world.getName() + "'.");
//             return;
//         }

//         // ---- Warn players standing in the target chunk ----
//         Chunk targetChunk = world.getChunkAt(chunkX, chunkZ);
//         for (Player occupant : world.getPlayers()) {
//             if (occupant.getLocation().getChunk().equals(targetChunk)) {
//                 occupant.sendMessage("§cWarning: the chunk you are standing in is being restored!");
//             }
//         }

//         // ---- Kick off the restore ----
//         sender.sendMessage("§aRestoring chunk " + chunkX + "," + chunkZ
//                 + " in world '" + world.getName() + "'…");

//         snapshotManager.restoreSnapshot(world, chunkX, chunkZ)
//                 .thenAccept(success -> {
//                     // thenAccept runs on whichever thread completes the future.
//                     // Use the scheduler to deliver feedback on the main thread so
//                     // we can safely interact with Bukkit's player API if needed.
//                     Bukkit.getScheduler().runTask(plugin, () -> {
//                         if (success) {
//                             sender.sendMessage("§aChunk " + chunkX + "," + chunkZ + " restored successfully.");
//                         } else {
//                             sender.sendMessage("§cRestore failed for chunk " + chunkX + "," + chunkZ
//                                     + ". Check the console for details.");
//                         }
//                     });
//                 });

//         return;
//     }

// }
