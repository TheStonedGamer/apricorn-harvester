package com.brianthemint.apricornharvester.schedule;

import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import com.brianthemint.apricornharvester.AddonContext;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code #schedule} command: the chat side of the schedule builder. The Schedule tab of the addon
 * window does the same with switches and arrows.
 */
public class ScheduleCommand implements ICommand {

    private final AddonContext context;

    public ScheduleCommand(AddonContext context) {
        this.context = context;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            list();
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "run" -> context.schedule().start();
            case "stop", "cancel" -> context.schedule().stop();
            case "status" -> logDirect("Schedule: " + context.schedule().status());
            case "list" -> list();
            case "repeat" -> {
                if (!args.hasAny()) {
                    logDirect("Repeat = " + Schedule.isRepeat() + ".");
                    return;
                }
                Schedule.setRepeat(Boolean.parseBoolean(args.getString()));
                logDirect("Repeat = " + Schedule.isRepeat() + ".");
            }
            case "on", "off" -> {
                if (!args.hasAny()) {
                    logDirect("Usage: #schedule " + sub + " <module>");
                    return;
                }
                ScheduleStep step = ScheduleStep.parse(args.getString());
                if (step == null) {
                    logDirect("Unknown module. " + moduleNames());
                    return;
                }
                Schedule.setEnabled(step, sub.equals("on"));
                logDirect(step.label() + " = " + (sub.equals("on") ? "on" : "off"));
            }
            default -> logDirect("Unknown argument '" + sub
                    + "'. Usage: #schedule [start|stop|status|list|on <module>|off <module>|repeat <true|false>]");
        }
    }

    private void list() {
        logDirect("Schedule (in order, * = on):");
        for (ScheduleStep step : Schedule.order()) {
            logDirect("  " + (Schedule.isEnabled(step) ? "*" : " ") + " " + step.key()
                    + " - " + step.label());
        }
        logDirect("Repeat = " + Schedule.isRepeat() + ". Order is set on the Schedule tab.");
    }

    private static String moduleNames() {
        StringBuilder sb = new StringBuilder("Modules: ");
        for (ScheduleStep step : ScheduleStep.values()) {
            if (sb.length() > 9) {
                sb.append(", ");
            }
            sb.append(step.key());
        }
        return sb.toString();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "stop", "status", "list", "on", "off", "repeat")
                    .filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2)) {
            String first = args.peekString();
            if (first.equalsIgnoreCase("on") || first.equalsIgnoreCase("off")) {
                TabCompleteHelper helper = new TabCompleteHelper();
                for (ScheduleStep step : ScheduleStep.values()) {
                    helper.append(step.key());
                }
                return helper.filterPrefix(args.peekString(1)).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run a sequence of modules: tools, harvest, deposit, craft, and so on";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The schedule is the list of jobs a run works through, in order. Switch on",
                "the ones you want and it does them one after another, travelling to each",
                "job's task area first and honouring warp cooldowns on the way.",
                "",
                "Modules: tools, harvest, bonemeal, plant, mine, hunt, pokeball, deposit.",
                "",
                "Usage:",
                "> #schedule                 show the schedule",
                "> #schedule start | stop    run it, or stop it",
                "> #schedule on harvest      switch a module on",
                "> #schedule off mine        switch one off",
                "> #schedule repeat true     start again from the top when it finishes",
                "",
                "The Schedule tab of the window has the same list with switches and arrows",
                "to reorder it, plus the ore and amount the mining module fetches."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("schedule", "routine");
    }
}
