package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #loc} command: ties each task (harvest, plant, bonemeal, mine, craft) to a saved
 * selection and the server command that gets there, and can send the bot to any of them.
 */
public class TaskLocationCommand implements ICommand {

    private final IBaritone baritone;

    public TaskLocationCommand(IBaritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            list();
            return;
        }
        String first = args.getString().toLowerCase(Locale.ROOT);
        if (first.equals("list")) {
            list();
            return;
        }
        if (first.equals("saved")) {
            List<String> names = TaskLocations.savedSelectionNames();
            if (names.isEmpty()) {
                logDirect("No saved selections. Make one with #sel pos1/pos2 and #save <name>.");
                return;
            }
            logDirect("Saved selections: " + String.join(", ", names));
            return;
        }

        TaskLocations.Task task = TaskLocations.Task.parse(first);
        if (task == null) {
            logDirect("Unknown task '" + first + "'. Tasks: " + taskNames());
            return;
        }
        if (!args.hasAny()) {
            logDirect(describe(task));
            return;
        }
        String action = args.getString().toLowerCase(Locale.ROOT);
        switch (action) {
            case "sel":
            case "selection": {
                if (!args.hasAny()) {
                    TaskLocations.setSelection(task, "");
                    logDirect(task.label() + ": selection cleared.");
                    return;
                }
                String name = args.getString();
                if (!TaskLocations.savedSelectionNames().contains(name)) {
                    logDirect("No saved selection called '" + name + "'. #loc saved lists them.");
                    return;
                }
                TaskLocations.setSelection(task, name);
                logDirect(task.label() + ": selection = " + name);
                return;
            }
            case "cmd":
            case "command": {
                if (!args.hasAny()) {
                    TaskLocations.setCommand(task, "");
                    logDirect(task.label() + ": travel command cleared.");
                    return;
                }
                TaskLocations.setCommand(task, args.rawRest());
                logDirect(task.label() + ": travel command = /" + TaskLocations.getCommand(task));
                return;
            }
            case "go": {
                boolean travelled = TaskLocations.sendTravel(task);
                boolean applied = TaskLocations.applySelection(baritone, task);
                if (!travelled && !applied) {
                    logDirect(task.label() + " has no travel command and no selection set.");
                    return;
                }
                logDirect("Going to " + task.label().toLowerCase(Locale.ROOT)
                        + (travelled ? " (/" + TaskLocations.getCommand(task) + ")" : "")
                        + (applied ? ", selection '" + TaskLocations.getSelection(task) + "' loaded." : "."));
                return;
            }
            case "load": {
                if (TaskLocations.applySelection(baritone, task)) {
                    logDirect("Loaded selection '" + TaskLocations.getSelection(task) + "'.");
                } else {
                    logDirect(task.label() + " has no usable saved selection.");
                }
                return;
            }
            default:
                logDirect("Usage: #loc <task> [sel <name>|cmd <command>|go|load]");
        }
    }

    private void list() {
        logDirect("Task locations:");
        for (TaskLocations.Task task : TaskLocations.Task.values()) {
            logDirect("  " + describe(task));
        }
        logDirect("Set with: #loc <task> sel <saved-selection> / #loc <task> cmd <command>");
    }

    private String describe(TaskLocations.Task task) {
        String selection = TaskLocations.getSelection(task);
        String command = TaskLocations.getCommand(task);
        return task.key() + ": selection=" + (selection.isEmpty() ? "-" : selection)
                + ", command=" + (command.isEmpty() ? "-" : "/" + command);
    }

    private static String taskNames() {
        StringBuilder sb = new StringBuilder();
        for (TaskLocations.Task task : TaskLocations.Task.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(task.key());
        }
        return sb.toString();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            TabCompleteHelper helper = new TabCompleteHelper().append("list", "saved");
            for (TaskLocations.Task task : TaskLocations.Task.values()) {
                helper.append(task.key());
            }
            return helper.filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2)) {
            return new TabCompleteHelper().append("sel", "cmd", "go", "load")
                    .filterPrefix(args.peekString(1)).stream();
        }
        if (args.hasExactly(3) && args.peekString(1).equalsIgnoreCase("sel")) {
            TabCompleteHelper helper = new TabCompleteHelper();
            for (String name : TaskLocations.savedSelectionNames()) {
                helper.append(name);
            }
            return helper.filterPrefix(args.peekString(2)).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set the saved selection and travel command used by each task";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The loc command remembers where each job happens: a saved selection",
                "(#save <name>) and the server command that takes you there.",
                "",
                "Tasks: harvest, plant, bonemeal, mine, craft.",
                "",
                "Usage:",
                "> #loc                       list every task's area and command",
                "> #loc saved                 list the saved selections you can pick from",
                "> #loc harvest sel treefarm  use the saved selection 'treefarm' for harvesting",
                "> #loc harvest cmd home farm command that takes you to the tree farm",
                "> #loc harvest go            run the command and load the selection",
                "> #loc harvest load          only load the selection",
                "> #loc mine cmd rtp          how the Poke Ball factory reaches its mining area",
                "> #loc craft cmd home home2  how it gets back to the furnaces and table",
                "",
                "The Poke Ball factory (#pokeball) uses the mine, craft and harvest entries",
                "automatically: it teleports to the mining area, comes back to the crafting",
                "base, and goes to the tree farm when it needs apricorns.",
                "Settings are saved in .minecraft/baritone/apricorn-tasks.properties."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("loc", "locations");
    }
}
