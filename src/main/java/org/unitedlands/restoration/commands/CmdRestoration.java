package org.unitedlands.restoration.commands;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;

@UnitedCommand (
    name            = "unitedrestoration",
    aliases         = { "restoration" },
    description     = "Main UnitedRestoration command",
    usage           = "/unitedrestoration <command> [arguments]"
    // permission      = "united.restoration.admin"
)
public class CmdRestoration implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) { }

}
