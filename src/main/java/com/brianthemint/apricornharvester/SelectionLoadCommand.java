package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #load <name>} — restores a previously saved Baritone selection.
 */
public final class SelectionLoadCommand implements ICommand {

    private final IBaritone baritone;

    public SelectionLoadCommand(IBaritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        args.requireMax(1);
        if (!args.hasAny()) {
            logDirect("Usage: #load <name>");
            return;
        }
        String name = args.getString();
        if (!SelectionStorage.isValidName(name)) {
            logDirect("Invalid name '" + name + "'. Use 1-32 letters, numbers, underscores, or dashes.");
            return;
        }

        Path gameDirectory = baritone.getPlayerContext().minecraft().gameDirectory.toPath();
        try {
            var saved = SelectionStorage.load(gameDirectory, name);
            if (saved.isEmpty()) {
                logDirect("No saved selection named '" + name + "'. Use #save <name> first.");
                return;
            }
            var selection = saved.get();
            baritone.getSelectionManager().removeAllSelections();
            baritone.getSelectionManager().addSelection(selection.pos1(), selection.pos2());
            logDirect("Loaded selection '" + name + "' (" + selection.pos1() + " -> " + selection.pos2() + ").");
        } catch (Exception e) {
            logDirect("Failed to load selection '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            try {
                Path gameDirectory = baritone.getPlayerContext().minecraft().gameDirectory.toPath();
                return new TabCompleteHelper()
                        .append(SelectionStorage.listNames(gameDirectory).stream())
                        .filterPrefix(args.peekString())
                        .stream();
            } catch (Exception ignored) {
                return Stream.empty();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Load a previously saved Baritone selection";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Restores a selection saved with #save. Any current selections are replaced.",
                "",
                "Usage:",
                "> #load farm1",
                "",
                "After loading, run #apricorn to harvest in the restored selection."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("load");
    }
}
