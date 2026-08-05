package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.selection.ISelection;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #farm} command: survey farms, choose which one the jobs work on, and look at what a
 * survey found.
 */
public class FarmCommand implements ICommand {

    private final IBaritone baritone;
    private final FarmMapper mapper;

    public FarmCommand(IBaritone baritone, FarmMapper mapper) {
        this.baritone = baritone;
        this.mapper = mapper;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            list();
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list();
            case "stop" -> mapper.stop();
            case "status" -> logDirect("Mapper: " + mapper.status());
            case "map" -> {
                if (!args.hasAny()) {
                    logDirect("Usage: #farm map <name> - surveys the current selection.");
                    return;
                }
                String name = args.getString();
                BlockPos[] sel = selection();
                if (sel == null) {
                    return;
                }
                mapper.start(name, sel[0], sel[1]);
            }
            case "remap" -> {
                FarmMap farm = FarmSelection.current();
                if (farm == null) {
                    logDirect("No farm selected. #farm select <name> first.");
                    return;
                }
                mapper.start(farm.name, farm.min, farm.max);
            }
            case "select", "use" -> {
                if (!args.hasAny()) {
                    logDirect("Usage: #farm select <name>");
                    return;
                }
                String name = args.getString();
                if (!FarmSelection.select(name)) {
                    logDirect("No farm called '" + name + "'. #farm list shows them.");
                    return;
                }
                FarmSelection.applyToBaritone(baritone);
                logDirect("Selected " + FarmSelection.current().summary());
                logDirect("The selection now covers the whole farm.");
            }
            case "info" -> {
                FarmMap farm = args.hasAny() ? FarmMap.load(args.getString()) : FarmSelection.current();
                if (farm == null) {
                    logDirect("No such farm (or none selected).");
                    return;
                }
                logDirect(farm.summary());
                logDirect("  bounds " + farm.min.getX() + "," + farm.min.getZ()
                        + " -> " + farm.max.getX() + "," + farm.max.getZ());
                logDirect("  " + (farm.isMapped() ? "mapped" : "not mapped yet"));
            }
            case "delete" -> {
                if (!args.hasAny()) {
                    logDirect("Usage: #farm delete <name>");
                    return;
                }
                String name = args.getString();
                logDirect(FarmMap.delete(name) ? "Deleted farm '" + name + "'."
                        : "No farm called '" + name + "'.");
            }
            default -> logDirect("Unknown argument '" + sub
                    + "'. Usage: #farm [list|map <name>|remap|select <name>|info|delete|stop|status]");
        }
    }

    private void list() {
        List<String> names = FarmMap.names();
        if (names.isEmpty()) {
            logDirect("No farms yet. Select an area with #sel pos1/pos2, then #farm map <name>.");
            return;
        }
        logDirect("Farms:");
        for (String name : names) {
            FarmMap farm = FarmMap.load(name);
            String marker = name.equalsIgnoreCase(FarmSelection.currentName()) ? " *" : "";
            logDirect("  " + (farm == null ? name : farm.summary()) + marker);
        }
        logDirect("* = selected. #farm select <name> to switch.");
    }

    private BlockPos[] selection() {
        ISelection sel = baritone.getSelectionManager().getOnlySelection();
        if (sel == null) {
            sel = baritone.getSelectionManager().getLastSelection();
        }
        if (sel == null) {
            logDirect("No selection. Mark the farm with #sel pos1 and #sel pos2 first.");
            return null;
        }
        return new BlockPos[] { sel.min(), sel.max() };
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("list", "map", "remap", "select", "info", "delete", "stop", "status")
                    .filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2)) {
            String first = args.peekString();
            if (first.equalsIgnoreCase("select") || first.equalsIgnoreCase("use")
                    || first.equalsIgnoreCase("info") || first.equalsIgnoreCase("delete")) {
                TabCompleteHelper helper = new TabCompleteHelper();
                for (String name : FarmMap.names()) {
                    helper.append(name);
                }
                return helper.filterPrefix(args.peekString(1)).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Survey a farm, then pick which farm the jobs work on";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The farm command surveys a field and remembers it.",
                "",
                "Minecraft only gives the client blocks in loaded chunks, so a farm bigger",
                "than your render distance cannot be planned from where you stand - the far",
                "half reads as empty air. #farm map walks a lawnmower route across the",
                "selection so every part loads once, recording the paths, the trees, the",
                "colours and the containers, and saves the result.",
                "",
                "Once a farm is selected, #apricorn plans over the whole field, including the",
                "parts that are not loaded at the moment.",
                "",
                "Usage:",
                "> #farm                   list the farms you have surveyed",
                "> #farm map myfarm        survey the current selection and call it 'myfarm'",
                "> #farm remap             survey the selected farm again",
                "> #farm select myfarm     work on that farm (also sets the selection)",
                "> #farm info [name]       what a survey found",
                "> #farm delete myfarm     forget a farm",
                "> #farm stop | status     stop a survey, or see how far it has got",
                "",
                "Maps live in .minecraft/baritone/apricorn-farms/."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("farm", "farms");
    }
}
