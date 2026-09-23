package org.unitedlands.restoration.commands.handlers.clone;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.commands.CmdRestoration;

@UnitedSubCommand (
    parent = CmdRestoration.class,
    name = "clone",
    description = "Restoration clone commands",
    usage = "/restoration clone <command> <arguments>"
)
public class CmdRestorationClone implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) {

    }

}
