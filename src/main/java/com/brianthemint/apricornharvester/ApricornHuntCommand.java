package com.brianthemint.apricornharvester;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #hunt} command: find wild apricorn trees, in particular the colours you do not have yet.
 */
public class ApricornHuntCommand implements ICommand {

    private static final int DEFAULT_HOPS = 10;

    private final ApricornHunter hunter;

    public ApricornHuntCommand(ApricornHunter hunter) {
        this.hunter = hunter;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            hunter.start(EnumSet.noneOf(ApricornType.class), DEFAULT_HOPS);
            return;
        }
        String first = args.getString().toLowerCase(Locale.ROOT);
        switch (first) {
            case "stop", "cancel" -> hunter.stop();
            case "status" -> logDirect("Hunt: " + hunter.status());
            case "scan", "here" -> ApricornHunter.report();
            case "missing" -> {
                EnumSet<ApricornType> missing = ApricornHunter.missingColours();
                if (missing.isEmpty()) {
                    logDirect("You have every apricorn colour.");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (ApricornType type : missing) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(ApricornPlanting.displayName(type));
                }
                logDirect("Missing colours: " + sb);
            }
            case "hops" -> {
                if (!args.hasAny()) {
                    logDirect("Usage: #hunt hops <n> - starts a hunt with that many hops.");
                    return;
                }
                try {
                    hunter.start(EnumSet.noneOf(ApricornType.class), Integer.parseInt(args.getString()));
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of hops.");
                }
            }
            default -> {
                // Treat anything else as a colour list: "#hunt red,blue".
                EnumSet<ApricornType> colours = EnumSet.noneOf(ApricornType.class);
                for (String part : first.split("[,\\s]+")) {
                    ApricornType type = ApricornPlanting.parse(part);
                    if (type == null) {
                        logDirect("Unknown apricorn colour '" + part + "'.");
                        return;
                    }
                    colours.add(type);
                }
                int hops = DEFAULT_HOPS;
                if (args.hasAny()) {
                    try {
                        hops = Integer.parseInt(args.getString());
                    } catch (NumberFormatException ignored) {
                        // Not a number: keep the default rather than refusing the hunt.
                    }
                }
                hunter.start(colours, hops);
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            TabCompleteHelper helper = new TabCompleteHelper()
                    .append("stop", "status", "scan", "missing", "hops");
            for (ApricornType type : ApricornPlanting.types()) {
                helper.append(type.name().toLowerCase(Locale.ROOT));
            }
            return helper.filterPrefix(args.peekString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Find wild apricorn trees, especially colours you do not have";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The hunt command looks for wild apricorn trees. Wild trees are the only",
                "source of a colour you have never grown, so by default it hunts exactly the",
                "colours you have none of in your inventory.",
                "",
                "It scans the loaded world around you; if nothing wanted is there it runs the",
                "hunt travel command (#loc hunt cmd, /rtp by default), waits for the chunks,",
                "and scans again, up to the hop limit. When it finds one it paths to it.",
                "",
                "Usage:",
                "> #hunt              hunt every colour you are missing (10 hops)",
                "> #hunt red,blue     hunt those colours",
                "> #hunt red 25       hunt red, up to 25 hops",
                "> #hunt scan         list the apricorn trees around you right now",
                "> #hunt missing      which colours you have none of",
                "> #hunt stop         stop hunting",
                "",
                "Hopping uses whatever command you set with #loc hunt cmd; without one it",
                "only scans where you stand."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("hunt", "apricornhunt");
    }
}
