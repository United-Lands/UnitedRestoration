package org.unitedlands.restoration.commands.handlers.clone;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.classes.ChunkCloneManager;
import org.unitedlands.utils.United;

@UnitedSubCommand(
    parent = CmdRestorationClone.class,
    name = "stop", 
    aliases = {}, 
    description = "Stops the chunk cloning process", 
    usage = "/restoration clone stop"
)
public class CmdRestorationCloneStop implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) {
        ChunkCloneManager.instance().stopCloning();
        United.messenger().sendRaw(sender, "<gray>Stopping chunk clone task.</gray>");
    }
    
}
