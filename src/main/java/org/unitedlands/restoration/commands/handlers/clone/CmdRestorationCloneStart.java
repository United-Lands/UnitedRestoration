package org.unitedlands.restoration.commands.handlers.clone;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.classes.ChunkCloneManager;
import org.unitedlands.utils.United;

@UnitedSubCommand(
    parent = CmdRestorationClone.class,
    name = "start", 
    aliases = {}, 
    description = "Starts a chunk cloning process", 
    usage = "/restoration clone start <source_world> <arget_world> [x1 z1 x2 z2] [--noBiomes|--onlyBiomes]",
    catchAll = true
)
public class CmdRestorationCloneStart implements UnitedCommandExecutor {

    @Override
    public List<String> handleTab(CommandSender sender, String[] args) {
        return switch (args.length) {
        case 1, 2 -> Bukkit.getWorlds().stream().map(World::getName).toList();
        default -> null;
        };
    }

    @Override
    public void handleCommand(CommandSender sender, String[] args) {

        if (args.length < 6) {
            sendUsage(sender);
            return;
        }

        // ---- Resolve target chunk ----
        World sourceWorld;
        World targetWorld;
        int x1 = 0;
        int z1 = 0;
        int x2 = 0;
        int z2 = 0;

        sourceWorld = Bukkit.getWorld(args[0]);
        targetWorld = Bukkit.getWorld(args[1]);
        if (sourceWorld == null) {
            United.messenger().sendRaw(sender, "<red>Unknown world: " + args[0] + "</red>");
            return;
        }
        if (targetWorld == null) {
            United.messenger().sendRaw(sender, "<red>Unknown world: " + args[1] + "</red>");
            return;
        }

        try {
            x1 = Integer.parseInt(args[2]);
            z1 = Integer.parseInt(args[3]);
            x2 = Integer.parseInt(args[4]);
            z2 = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            United.messenger().sendRaw(sender, "<red>Chunk coordinates must be integers.</red>");
            return;
        }

        if (x1 > x2 || z1 > z2) {
            United.messenger().sendRaw(sender, "<red>Invalid area.</red>");
            return;
        }

        var copyBiomes = true;
        var onlyBiomes = false;

        if (args[args.length - 1].equals("--noBiomes"))
            copyBiomes = false;
        if (args[args.length - 1].equals("--onlyBiomes"))
            onlyBiomes = true;

        United.messenger().sendRaw(sender, "<gray>Starting chunk clone task. Watch the console for further details.</gray>");

        ChunkCloneManager.instance().startCloning(sourceWorld, targetWorld, x1, z1, x2, z2, 0, copyBiomes, onlyBiomes);
    }

    

}
