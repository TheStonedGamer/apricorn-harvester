package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.selection.ISelection;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #save <name>} — saves the current Baritone selection ({@code #sel pos1}/{@code #sel pos2}).
 */
public final class SelectionSaveCommand implements ICommand {

    private final IBaritone baritone;

    public SelectionSaveCommand(IBaritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        args.requireMax(1);
        if (!args.hasAny()) {
            logDirect("Usage: #save <name>");
            return;
        }
        String name = args.getString();
        if (!SelectionStorage.isValidName(name)) {
            logDirect("Invalid name '" + name + "'. Use 1-32 letters, numbers, underscores, or dashes.");
            return;
        }

        ISelection selection = baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            selection = baritone.getSelectionManager().getLastSelection();
        }
        if (selection == null) {
            logDirect("No selection to save. Make one first with #sel pos1 and #sel pos2.");
            return;
        }

        Path gameDirectory = baritone.getPlayerContext().minecraft().gameDirectory.toPath();
        try {
            SelectionStorage.save(gameDirectory, name, selection.pos1(), selection.pos2());
            logDirect("Saved selection '" + name + "' (" + selection.pos1() + " -> " + selection.pos2() + ").");
        } catch (Exception e) {
            logDirect("Failed to save selection '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.empty();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Save the current Baritone selection under a name";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Saves the current Baritone selection corners to disk so you can restore them later.",
                "",
                "Usage:",
                "> #save farm1",
                "",
                "Make a selection first with #sel pos1 and #sel pos2. Files are stored in",
                ".minecraft/baritone/selections/<name>.sel"
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("save");
    }
}
