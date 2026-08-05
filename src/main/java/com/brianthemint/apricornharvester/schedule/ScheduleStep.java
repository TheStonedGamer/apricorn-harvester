package com.brianthemint.apricornharvester.schedule;

import java.util.Locale;

/**
 * One module a schedule can run. The order in {@link Schedule} is what the runner follows; each
 * step knows how to start itself and how to tell whether it is still going, which is all the
 * runner needs.
 */
public enum ScheduleStep {

    TOOLS("Repair/replace tools", "tools"),
    HARVEST("Harvest apricorns", "harvest"),
    BONEMEAL("Bone-meal saplings", "bonemeal"),
    PLANT("Plant the grid", "plant"),
    MINE("Mine ore", "mine"),
    HUNT("Hunt new colours", "hunt"),
    POKEBALL("Craft Poke Balls", "pokeball"),
    DEPOSIT("Empty into chests", "deposit");

    private final String label;
    private final String key;

    ScheduleStep(String label, String key) {
        this.label = label;
        this.key = key;
    }

    public String label() {
        return label;
    }

    public String key() {
        return key;
    }

    public static ScheduleStep parse(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (ScheduleStep step : values()) {
            if (step.key.equals(n) || step.name().toLowerCase(Locale.ROOT).equals(n)) {
                return step;
            }
        }
        return null;
    }
}
