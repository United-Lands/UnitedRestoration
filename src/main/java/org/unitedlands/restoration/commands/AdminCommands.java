package org.unitedlands.restoration.commands;

import org.unitedlands.classes.BaseCommandExecutor;
import org.unitedlands.interfaces.IMessageProvider;
import org.unitedlands.restoration.UnitedRestoration;
import org.unitedlands.restoration.commands.handlers.CloneChunks;
import org.unitedlands.restoration.commands.handlers.RestoreChunk;
import org.unitedlands.restoration.commands.handlers.SaveChunks;

public class AdminCommands extends BaseCommandExecutor<UnitedRestoration>  {

    public AdminCommands(UnitedRestoration plugin, IMessageProvider messageProvider) {
        super(plugin, messageProvider);
    }

    @Override
    protected void registerHandlers() {
        handlers.put("savechunks", new SaveChunks(plugin, messageProvider));
        handlers.put("restorechunk", new RestoreChunk(plugin, messageProvider));
        handlers.put("clonechunks", new CloneChunks(plugin, messageProvider));
    }

}
