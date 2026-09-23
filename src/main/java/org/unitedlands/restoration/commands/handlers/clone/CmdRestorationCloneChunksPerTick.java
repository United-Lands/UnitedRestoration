package org.unitedlands.restoration.commands.handlers.clone;

import org.bukkit.command.CommandSender;
import org.unitedlands.annotations.UnitedSubCommand;
import org.unitedlands.registrars.command.UnitedCommandExecutor;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.utils.United;

@UnitedSubCommand(
    parent = CmdRestorationClone.class, 
    name = "chunkspertick", 
    aliases = { "cpt" }, 
    description = "Sets chunks per tick for the cloning process", 
    usage = "/restoration clone chunkspertick <number>"
)
public class CmdRestorationCloneChunksPerTick implements UnitedCommandExecutor {

    @Override
    public void handleCommand(CommandSender sender, String[] args) {

        if (args.length != 1) {
            sendUsage(sender);
            return;
        }

        var cpt = 1;
        try {
            cpt = Integer.parseInt(args[0]);
        } catch (Exception ex) {
            United.messenger().sendRaw(sender, "<red>Invalid number format.</red>");
            return;
        }

        UnitedRestoration.instance().getConfig().set("chunk-copy.chunks-per-tick", cpt);
        UnitedRestoration.instance().saveConfig();
        UnitedRestoration.instance().reloadConfig();

        United.messenger().sendRaw(sender, "<gray>Set chunks per tick for cloning to " + cpt + ". Stop and resume an ongoing task to use the new value.</gray>");
    }

}
