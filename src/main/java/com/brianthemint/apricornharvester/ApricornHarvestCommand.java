package com.brianthemint.apricornharvester;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #apricorn} command: starts or stops the apricorn harvesting process for the
 * current Baritone selection (the same selection made with {@code #sel pos1}/{@code #sel pos2}).
 */
public class ApricornHarvestCommand implements ICommand {

    private final ApricornHarvestProcess process;

    public ApricornHarvestCommand(ApricornHarvestProcess process) {
        this.process = process;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAtMost(1)) {
            // Two+ arguments: key + value ("tops true", "deposit false")
            String key = args.getString();
            String value = args.getString();
            if (args.hasAny()) {
                logDirect("Too many arguments.");
                return;
            }
            if (key.equalsIgnoreCase("tops")) {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")) {
                    process.setHarvestTops(true);
                } else if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
                    process.setHarvestTops(false);
                } else {
                    logDirect("Expected true or false, got '" + value + "'.");
                }
                return;
            }
            if (key.equalsIgnoreCase("deposit")) {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("chest")) {
                    process.setDepositEnabled(true);
                } else if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
                    process.setDepositEnabled(false);
                } else {
                    logDirect("Expected true or false, got '" + value + "'.");
                }
                return;
            }
            if (key.equalsIgnoreCase("chestradius") || key.equalsIgnoreCase("radius")) {
                try {
                    AddonSettings.setChestRadius(Integer.parseInt(value));
                    logDirect("Chest search radius = " + AddonSettings.getChestRadius() + " blocks.");
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of blocks (" + AddonSettings.MIN_CHEST_RADIUS
                            + "-" + AddonSettings.MAX_CHEST_RADIUS + ").");
                }
                return;
            }
            logDirect("Unknown setting '" + key
                    + "'. Usage: #apricorn tops|deposit <true|false> or #apricorn chestradius <blocks>");
            return;
        }
        // Zero or one argument: command or query
        if (args.hasAny()) {
            String arg = args.getString();
            if (arg.equalsIgnoreCase("stop") || arg.equalsIgnoreCase("cancel")) {
                process.stop();
                logDirect("Stopped apricorn harvesting.");
                return;
            }
            if (arg.equalsIgnoreCase("pause")) {
                process.pause();
                return;
            }
            if (arg.equalsIgnoreCase("resume")) {
                process.resume();
                return;
            }
            if (arg.equalsIgnoreCase("start") || arg.equalsIgnoreCase("patrol") || arg.equalsIgnoreCase("go")) {
                process.start();
                return;
            }
            if (arg.equalsIgnoreCase("tops")) {
                logDirect("Apricorn tops = " + process.isHarvestTops()
                        + ". Use #apricorn tops <true|false> to change it.");
                return;
            }
            if (arg.equalsIgnoreCase("deposit")) {
                logDirect("Apricorn deposit = " + process.isDepositEnabled()
                        + ". Use #apricorn deposit <true|false> to change it.");
                return;
            }
            if (arg.equalsIgnoreCase("chestradius") || arg.equalsIgnoreCase("radius")) {
                logDirect("Chest search radius = " + AddonSettings.getChestRadius()
                        + " blocks. Use #apricorn chestradius <blocks> to change it.");
                return;
            }
            logDirect("Unknown argument '" + arg
                    + "'. Usage: #apricorn [start|stop|pause|resume|tops|deposit]");
            return;
        }
        process.start();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "stop", "pause", "resume", "tops", "deposit", "chestradius")
                    .filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2)) {
            String first = args.peekString();
            if (first.equalsIgnoreCase("tops") || first.equalsIgnoreCase("deposit")) {
                return new TabCompleteHelper().append("true", "false")
                        .filterPrefix(args.peekString(1)).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Harvest mature apricorns inside the current Baritone selection";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The apricorn command makes Baritone patrol the farm paths of your",
                "current selection and right-click-harvest every fully grown apricorn",
                "it can reach without breaking a single block and without climbing",
                "onto the trees (the farm's 1-block-wide paths are used instead).",
                "",
                "Usage:",
                "> #apricorn              start harvesting in the current selection",
                "> #apricorn start        same as above",
                "> #apricorn pause        pause the harvest in place",
                "> #apricorn resume       continue a paused harvest",
                "> #apricorn stop         stop harvesting",
                "> #apricorn tops false   skip apricorns the ground patrol cannot reach (default)",
                "> #apricorn tops true    after the patrol, also try high apricorns from the paths",
                "> #apricorn tops         show the current value",
                "> #apricorn deposit true after harvesting, find a nearby chest and deposit apricorns",
                "> #apricorn deposit false do not deposit (apricorns stay in inventory)",
                "> #apricorn deposit      show the current value",
                "> #apricorn chestradius 24  how far to look for a container to deposit into",
                "",
                "With deposit enabled, after the bot picks up all drops it scans the area",
                "around the selection for a chest, barrel, or shulker box with empty slots.",
                "It walks to the nearest one, opens it, shift-clicks every apricorn into the",
                "container, then closes and reports done.",
                "",
                "Middle and top apricorns of a tree usually cannot be reached from a",
                "1-block path at all; with tops false the run ends after the patrol",
                "instead of walking stand to stand trying to reach them.",
                "",
                "Baritone's own #pause / #resume also freeze and continue this harvest",
                "(they pause ALL Baritone actions); #apricorn pause only pauses the",
                "harvest. Make a selection first with #sel pos1 and #sel pos2, covering",
                "the trees you want harvested (ground level to tree top).",
                "Apricorns that cannot be reached from the paths are left standing.",
                "After harvesting, the bot walks over every dropped apricorn lying in",
                "the selection to pick it up (drops inside tree columns are skipped)."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("apricorn", "apricorns");
    }
}
