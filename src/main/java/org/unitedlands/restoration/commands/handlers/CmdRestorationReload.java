package org.unitedlands.restoration.commands.handlers;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.restoration.commands.CmdRestoration;
import org.unitedlands.utils.United;

@UnitedSubCommand (
    parent = CmdRestoration.class,
    name = "reload",
    description = "Reloads the UnitedRestoration config",
    usage = "/restoration reload"
)
public class CmdRestorationReload implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) {
        UnitedRestoration.instance().reloadConfig();
        United.messenger().sendRaw(sender, "<dark_green>Config reloaded.</dark_green>");
    }

}
