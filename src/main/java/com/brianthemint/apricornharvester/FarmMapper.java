package com.brianthemint.apricornharvester;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.Helper;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a selection once and records what the farm is made of.
 *
 * <p>The client only has block data for chunks it has loaded, so a farm larger than the render
 * distance cannot be planned from where you stand - the far half reads as empty air. The mapper
 * walks a coarse route across the selection so every part of it loads at least once, recording the
 * path stands, apricorn trees, containers and colour counts as it goes, and saves the result as a
 * {@link FarmMap} for later runs to plan from.
 *
 * <p>Like the other long jobs it is a client-tick controller, driving Baritone's custom goal
 * process rather than competing with it.
 */
public final class FarmMapper {

    /** Distance between survey waypoints. One chunk, so every column loads on the way past. */
    private static final int WAYPOINT_STEP = 16;
    /** How close counts as having reached a waypoint. */
    private static final double ARRIVED_DISTANCE_SQ = 36.0;
    /** Ticks a single waypoint may take before it is written off as unreachable. */
    private static final int WAYPOINT_TIMEOUT = 20 * 45;

    private final IBaritone baritone;

    private boolean running;
    private FarmMap map;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int waypointIndex;
    private int ticks;
    private boolean previousAllowBreak;
    /** Leaf blocks this survey added to Baritone's blocksToAvoid, so only those are removed after. */
    private final List<net.minecraft.world.level.block.Block> canopyBlocksAdded = new ArrayList<>();

    public FarmMapper(IBaritone baritone) {
        this.baritone = baritone;
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        if (!running) {
            return "Idle";
        }
        return "Mapping " + map.name + " (" + waypointIndex + "/" + waypoints.size() + ")";
    }

    /** The map being surveyed, or the last one surveyed. */
    public FarmMap current() {
        return map;
    }

    /**
     * Starts a survey of the given selection under a name. An existing map of that name is
     * replaced once the walk finishes.
     */
    public void start(String name, BlockPos min, BlockPos max) {
        if (running) {
            logDirect("Already mapping. Use #farm stop first.");
            return;
        }
        if (Minecraft.getInstance().player == null) {
            return;
        }
        map = new FarmMap(name, min, max);
        waypoints.clear();
        waypointIndex = 0;
        ticks = 0;

        // A lawnmower route over the selection: every column ends up within a chunk of some
        // waypoint, so it loads at least once during the walk. The Y is the selection floor, not
        // its top: a waypoint at tree-top height is an invitation to climb the bushes.
        boolean reverse = false;
        for (int z = min.getZ(); z <= max.getZ() + WAYPOINT_STEP; z += WAYPOINT_STEP) {
            int cz = Math.min(z, max.getZ());
            List<Integer> xs = new ArrayList<>();
            for (int x = min.getX(); x <= max.getX() + WAYPOINT_STEP; x += WAYPOINT_STEP) {
                xs.add(Math.min(x, max.getX()));
            }
            if (reverse) {
                java.util.Collections.reverse(xs);
            }
            for (int cx : xs) {
                waypoints.add(new BlockPos(cx, min.getY(), cz));
            }
            reverse = !reverse;
        }

        // Survey from the paths like every other job: no breaking, and no walking over the bushes.
        previousAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
        BaritoneAPI.getSettings().allowBreak.value = false;
        CanopyAvoidance.avoid(canopyBlocksAdded);

        running = true;
        logDirect("Mapping farm '" + name + "': " + waypoints.size() + " waypoints to walk.");
    }

    public void stop() {
        if (!running) {
            logDirect("Not mapping.");
            return;
        }
        running = false;
        baritone.getCustomGoalProcess().setGoal(null);
        restoreSettings();
        logDirect("Mapping stopped.");
    }

    /** Called every client tick. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!running || mc.player == null || mc.level == null) {
            return;
        }
        // Record whatever is loaded right now, wherever the bot happens to be.
        record();

        if (waypointIndex >= waypoints.size()) {
            finish();
            return;
        }
        BlockPos target = waypoints.get(waypointIndex);
        // Distance is measured on the flat: the waypoint is a column to visit, and whatever height
        // the path happens to be at there is fine.
        double dx = mc.player.getX() - (target.getX() + 0.5);
        double dz = mc.player.getZ() - (target.getZ() + 0.5);
        if (dx * dx + dz * dz <= ARRIVED_DISTANCE_SQ) {
            waypointIndex++;
            ticks = 0;
            baritone.getCustomGoalProcess().setGoal(null);
            return;
        }
        ticks++;
        if (ticks == 1 || ticks % 60 == 0) {
            // GoalXZ, not a 3D goal: asking for a particular height is what sent the survey up
            // onto the canopy instead of along the paths.
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(target.getX(), target.getZ()));
        }
        if (ticks > WAYPOINT_TIMEOUT) {
            logDirect("Could not reach survey point " + target + ", skipping it.");
            waypointIndex++;
            ticks = 0;
        }
    }

    /**
     * Adds everything currently loaded inside the selection to the map. Called every tick, which
     * is cheap because the scan is limited to the chunks around the player.
     */
    private void record() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos origin = mc.player.blockPosition();
        int radius = 48;
        int minX = Math.max(map.min.getX(), origin.getX() - radius);
        int maxX = Math.min(map.max.getX(), origin.getX() + radius);
        int minZ = Math.max(map.min.getZ(), origin.getZ() - radius);
        int maxZ = Math.min(map.max.getZ(), origin.getZ() + radius);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!mc.level.hasChunkAt(new BlockPos(x, map.min.getY(), z))) {
                    continue;
                }
                boolean columnHasTree = false;
                BlockPos highestLeaf = null;
                for (int y = map.min.getY(); y <= map.max.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    ApricornType type = ApricornPlanting.typeOfLeaves(state);
                    if (type != null) {
                        if (map.trees.add(pos)) {
                            map.colours.merge(type, 1, Integer::sum);
                        }
                        columnHasTree = true;
                        highestLeaf = pos;
                    } else if (ApricornBlocks.isContainer(state)) {
                        map.containers.add(pos);
                    }
                }
                if (columnHasTree) {
                    // Where a canopy sweep can stand: on the top leaf, with room for the player.
                    BlockPos feet = highestLeaf.above();
                    BlockPos head = highestLeaf.above(2);
                    if (mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                            && mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty()) {
                        map.canopyStands.add(feet);
                    }
                } else {
                    BlockPos stand = standIn(x, z);
                    if (stand != null) {
                        map.stands.add(stand);
                    }
                }
            }
        }
    }

    /** The same stand test the harvester uses: highest walkable, non-tree surface of a column. */
    private BlockPos standIn(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        for (int y = map.max.getY() + 2; y >= map.min.getY() - 4; y--) {
            BlockPos below = new BlockPos(x, y, z);
            BlockState state = mc.level.getBlockState(below);
            if (ApricornBlocks.isTreeBlock(state) || ApricornPlanting.isSapling(state)) {
                continue;
            }
            if (state.getCollisionShape(mc.level, below).isEmpty()) {
                continue;
            }
            BlockPos feet = new BlockPos(x, y + 1, z);
            BlockPos head = new BlockPos(x, y + 2, z);
            if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                    || !mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty()) {
                continue;
            }
            return feet;
        }
        return null;
    }

    /** Puts back the pathing settings the survey borrowed. */
    private void restoreSettings() {
        BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        CanopyAvoidance.release(canopyBlocksAdded);
    }

    private void finish() {
        running = false;
        baritone.getCustomGoalProcess().setGoal(null);
        restoreSettings();
        map.mappedAt = Minecraft.getInstance().level == null
                ? System.currentTimeMillis() : Minecraft.getInstance().level.getGameTime();
        map.save();
        FarmSelection.select(map.name);
        logDirect("Mapped " + map.summary());
        logDirect("Selected '" + map.name + "'. #apricorn will now plan over the whole farm.");
    }
}
