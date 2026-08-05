package com.brianthemint.apricornharvester;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #bonemeal} command: bone-meals every apricorn sapling in the current Baritone selection
 * until it grows into a tree.
 */
public class ApricornBonemealCommand implements ICommand {

    private final ApricornBonemealProcess process;

    public ApricornBonemealCommand(ApricornBonemealProcess process) {
        this.process = process;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            process.start();
            return;
        }
        String sub = args.getString();
        switch (sub.toLowerCase()) {
            case "start":
            case "go":
                process.start();
                return;
            case "stop":
            case "cancel":
                process.stop();
                return;
            case "pause":
                process.pause();
                return;
            case "resume":
                process.resume();
                return;
            default:
                logDirect("Unknown argument '" + sub + "'. Usage: #bonemeal [start|stop|pause|resume]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("start", "stop", "pause", "resume")
                    .filterPrefix(args.peekString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Bone-meal every apricorn sapling in the current selection until it grows";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The bonemeal command walks the current selection, visits every apricorn",
                "sapling from the path beside it and applies bone meal until the sapling",
                "has grown into a tree (up to 32 applications per sapling).",
                "",
                "Bone meal is taken from the hotbar, or swapped in from the main inventory.",
                "The run ends as soon as you are out of bone meal, and no block is ever",
                "broken - the bot only right-clicks.",
                "",
                "Usage:",
                "> #bonemeal              bone-meal all saplings in the current selection",
                "> #bonemeal stop         stop",
                "> #bonemeal pause        pause in place",
                "> #bonemeal resume       continue a paused run",
                "",
                "Make a selection first with #sel pos1 and #sel pos2. Pair it with #plant:",
                "plant the field, then run #bonemeal to grow everything at once."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("bonemeal", "bonemealapricorns");
    }
}
