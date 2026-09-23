package org.unitedlands.restoration.commands.handlers.clone;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.classes.ChunkCloneManager;
import org.unitedlands.utils.United;

@UnitedSubCommand(
    parent = CmdRestorationClone.class,
    name = "resume", 
    aliases = {}, 
    description = "Resumes the chunk cloning process", 
    usage = "/restoration clone resume"
)
public class CmdRestorationCloneResume implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) {
        ChunkCloneManager.instance().resumeCloning();
        United.messenger().sendRaw(sender, "<gray>Resuming chunk clone task. Watch the console for further details.</gray>");
    }
    
}
