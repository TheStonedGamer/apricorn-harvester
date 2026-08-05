package com.brianthemint.apricornharvester;

import baritone.api.BaritoneAPI;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the bot off the tops of the bushes.
 *
 * <p>Every goal the jobs set is on a farm path, but the pathfinder is free to route between those
 * goals however it likes - and leaf blocks are solid, so strolling over a canopy is a perfectly
 * good path as far as Baritone is concerned. Putting the apricorn leaves in Baritone's
 * {@code blocksToAvoid} makes the pathfinder refuse to route through or onto them, so the bot keeps
 * to the paths between the bushes.
 *
 * <p>The harvester lifts this for one errand only: fetching a drop that has landed on a canopy,
 * where climbing up is the only way to reach it. Even then nothing is broken or placed - the bot
 * either finds a legitimate way up (a slope, a wall, a staircase the farm happens to have) or gives
 * the drop up.
 */
public final class CanopyAvoidance {

    private CanopyAvoidance() {
    }

    /**
     * Adds the apricorn leaves to Baritone's avoid list.
     *
     * @param added collects what this call actually added, so only that is removed later
     */
    public static void avoid(List<Block> added) {
        List<Block> avoidList = BaritoneAPI.getSettings().blocksToAvoid.value;
        for (ApricornType type : ApricornType.values()) {
            Block leaves = type.leavesBlock();
            if (leaves != null && !avoidList.contains(leaves)) {
                avoidList.add(leaves);
                added.add(leaves);
            }
        }
    }

    /** Takes back exactly what {@link #avoid} added, leaving anyone else's entries alone. */
    public static void release(List<Block> added) {
        if (added.isEmpty()) {
            return;
        }
        BaritoneAPI.getSettings().blocksToAvoid.value.removeAll(added);
        added.clear();
    }

    /** A fresh list for a job to track what it added. */
    public static List<Block> tracker() {
        return new ArrayList<>();
    }
}
