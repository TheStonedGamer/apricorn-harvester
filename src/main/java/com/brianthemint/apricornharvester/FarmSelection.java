package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;

/**
 * Which mapped farm the jobs are currently working on.
 *
 * <p>Selecting a farm loads its map, so a run can plan over the whole field even where chunks are
 * not loaded, and sets the Baritone selection to the farm's bounds so the existing commands keep
 * working unchanged. The choice is remembered in {@link TaskLocations} under the harvest task, so
 * it survives a restart.
 */
public final class FarmSelection {

    private static FarmMap current;

    private FarmSelection() {
    }

    /** The selected farm's map, or null when none is selected. */
    public static FarmMap current() {
        return current;
    }

    public static String currentName() {
        return current == null ? "" : current.name;
    }

    /** Loads a farm by name and makes it the current one. Returns false when there is no such map. */
    public static boolean select(String name) {
        FarmMap map = FarmMap.load(name);
        if (map == null) {
            return false;
        }
        current = map;
        TaskLocations.setSelection(TaskLocations.Task.HARVEST, name);
        return true;
    }

    public static void clear() {
        current = null;
    }

    /**
     * Applies the selected farm's bounds to Baritone's selection, so {@code #apricorn} and friends
     * work on the whole farm without a separate {@code #sel} step.
     */
    public static boolean applyToBaritone(IBaritone baritone) {
        if (current == null || baritone == null) {
            return false;
        }
        baritone.getSelectionManager().removeAllSelections();
        baritone.getSelectionManager().addSelection(
                new BetterBlockPos(current.min), new BetterBlockPos(current.max));
        return true;
    }

    /**
     * Re-loads the farm remembered for the harvest task, so a fresh session starts with the same
     * farm selected. Safe to call when nothing is remembered.
     */
    public static void restore() {
        if (current != null) {
            return;
        }
        String name = TaskLocations.getSelection(TaskLocations.Task.HARVEST);
        if (!name.isEmpty()) {
            select(name);
        }
    }
}
