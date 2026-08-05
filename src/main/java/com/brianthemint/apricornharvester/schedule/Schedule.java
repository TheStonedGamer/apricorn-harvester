package com.brianthemint.apricornharvester.schedule;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The list of modules a schedule run works through, in order.
 *
 * <p>A farm day is the same handful of jobs every time - top up the tools, harvest, empty the
 * chests, craft what the crop is for - so the schedule is simply which of those are switched on and
 * in what order. It is persisted to {@code .minecraft/baritone/apricorn-schedule.properties}.
 */
public final class Schedule {

    private static final String FILE_NAME = "apricorn-schedule.properties";

    /** Every step, in the order the run follows. */
    private static final List<ScheduleStep> ORDER = new ArrayList<>();
    /** Which of them are switched on. */
    private static final Set<ScheduleStep> ENABLED = new LinkedHashSet<>();
    /** Whether the run starts again from the top when it reaches the end. */
    private static boolean repeat;
    /** What the mining module fetches, and how much of it. */
    private static String mineOre = "platinum";
    private static int mineAmount = 32;
    /** How many hops the hunting module may spend looking for a new colour. */
    private static int huntHops = 5;

    private static boolean loaded;

    static {
        ORDER.addAll(List.of(ScheduleStep.values()));
    }

    private Schedule() {
    }

    /** The steps in their configured order. */
    public static List<ScheduleStep> order() {
        ensureLoaded();
        return new ArrayList<>(ORDER);
    }

    /** Only the switched-on steps, in order: what a run will actually do. */
    public static List<ScheduleStep> enabledSteps() {
        ensureLoaded();
        List<ScheduleStep> steps = new ArrayList<>();
        for (ScheduleStep step : ORDER) {
            if (ENABLED.contains(step)) {
                steps.add(step);
            }
        }
        return steps;
    }

    public static boolean isEnabled(ScheduleStep step) {
        ensureLoaded();
        return ENABLED.contains(step);
    }

    public static void setEnabled(ScheduleStep step, boolean enabled) {
        ensureLoaded();
        if (enabled) {
            ENABLED.add(step);
        } else {
            ENABLED.remove(step);
        }
        save();
    }

    /** Moves a step one place earlier or later in the run. */
    public static void move(ScheduleStep step, int direction) {
        ensureLoaded();
        int index = ORDER.indexOf(step);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= ORDER.size()) {
            return;
        }
        ORDER.remove(index);
        ORDER.add(target, step);
        save();
    }

    public static String getMineOre() {
        ensureLoaded();
        return mineOre;
    }

    public static void setMineOre(String ore) {
        ensureLoaded();
        mineOre = ore == null ? "" : ore;
        save();
    }

    public static int getMineAmount() {
        ensureLoaded();
        return mineAmount;
    }

    public static void setMineAmount(int value) {
        ensureLoaded();
        mineAmount = Math.max(1, Math.min(2304, value));
        save();
    }

    public static int getHuntHops() {
        ensureLoaded();
        return huntHops;
    }

    public static void setHuntHops(int value) {
        ensureLoaded();
        huntHops = Math.max(0, Math.min(100, value));
        save();
    }

    public static boolean isRepeat() {
        ensureLoaded();
        return repeat;
    }

    public static void setRepeat(boolean value) {
        ensureLoaded();
        repeat = value;
        save();
    }

    // ---------------------------------------------------------------- persistence

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            List<ScheduleStep> order = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                switch (key) {
                    case "repeat" -> repeat = Boolean.parseBoolean(value);
                    case "mineOre" -> mineOre = value;
                    case "mineAmount" -> mineAmount = parseInt(value, mineAmount);
                    case "huntHops" -> huntHops = parseInt(value, huntHops);
                    default -> {
                        // handled below
                    }
                }
                if (!key.equals("step")) {
                    continue;
                }
                // step=<name>,<enabled>
                String[] parts = value.split(",");
                ScheduleStep step = ScheduleStep.parse(parts[0]);
                if (step == null) {
                    continue;
                }
                order.add(step);
                if (parts.length > 1 && Boolean.parseBoolean(parts[1])) {
                    ENABLED.add(step);
                }
            }
            if (!order.isEmpty()) {
                // Anything added to the enum since the file was written goes on the end.
                for (ScheduleStep step : ScheduleStep.values()) {
                    if (!order.contains(step)) {
                        order.add(step);
                    }
                }
                ORDER.clear();
                ORDER.addAll(order);
            }
        } catch (IOException ignored) {
            // Unreadable file: the defaults stand.
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# Apricorn Harvester schedule: the modules a run works through, in order.");
        lines.add("repeat=" + repeat);
        lines.add("mineOre=" + mineOre);
        lines.add("mineAmount=" + mineAmount);
        lines.add("huntHops=" + huntHops);
        for (ScheduleStep step : ORDER) {
            lines.add("step=" + step.key() + "," + ENABLED.contains(step));
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort; the in-memory schedule still works for this session.
        }
    }
}
