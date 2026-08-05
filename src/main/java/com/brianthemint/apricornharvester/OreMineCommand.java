package com.brianthemint.apricornharvester;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #ore} command: mine one ore on its own, without a whole Poke Ball run. Uses the mining
 * task's travel command to get out and the crafting base's to come back.
 */
public class OreMineCommand implements ICommand {

    private static final int DEFAULT_AMOUNT = 32;

    private final OreMineController controller;

    public OreMineCommand(OreMineController controller) {
        this.controller = controller;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("Usage: #ore <" + names() + "> [amount] | #ore stop | #ore status");
            return;
        }
        String first = args.getString().toLowerCase(Locale.ROOT);
        if (first.equals("stop") || first.equals("cancel")) {
            controller.stop();
            return;
        }
        if (first.equals("status")) {
            logDirect("Ore mining: " + controller.status());
            return;
        }
        if (first.equals("list")) {
            logDirect("Ores: " + names());
            return;
        }
        OreMineController.Ore ore = OreMineController.Ore.parse(first);
        if (ore == null) {
            logDirect("Unknown ore '" + first + "'. Ores: " + names());
            return;
        }
        int amount = DEFAULT_AMOUNT;
        if (args.hasAny()) {
            try {
                amount = Integer.parseInt(args.getString());
            } catch (NumberFormatException e) {
                logDirect("Expected an amount.");
                return;
            }
        }
        controller.start(ore, amount);
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (OreMineController.Ore ore : OreMineController.Ore.available()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ore.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            TabCompleteHelper helper = new TabCompleteHelper().append("stop", "status", "list");
            for (OreMineController.Ore ore : OreMineController.Ore.available()) {
                helper.append(ore.name().toLowerCase(Locale.ROOT));
            }
            return helper.filterPrefix(args.peekString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mine one ore (platinum, silver, ...) on its own";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The ore command mines a single ore without running the whole Poke Ball",
                "pipeline: it runs the mining task's travel command, hands the job to",
                "Baritone's miner until the requested amount is in your inventory, then",
                "runs the crafting base's command to bring you home.",
                "",
                "Usage:",
                "> #ore platinum          mine 32 platinum ore",
                "> #ore platinum 64       mine 64",
                "> #ore silver | bauxite | iron | gold | coal | diamond | stone",
                "> #ore stop              stop mining",
                "> #ore status            what it is doing",
                "> #ore list              the ores it knows about",
                "",
                "Travel commands come from #loc mine cmd and #loc craft cmd. With no mine",
                "command set it simply mines where you stand."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("ore", "oremine");
    }
}
