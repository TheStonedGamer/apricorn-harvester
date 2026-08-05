package com.brianthemint.apricornharvester;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.selection.ISelection;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Baritone process that plants apricorns on a grid inside the current selection.
 *
 * <p>The grid is anchored at the selection's minimum corner and steps by
 * {@link PlantConfig#getSpacing()} blocks on both axes (default 3, i.e. one plant with two blocks
 * of gap around it). Rows run along X and are identified by their Z coordinate; every row plants
 * the colour configured for it in {@link PlantConfig} (set from the GUI or {@code #plant row}).
 *
 * <p>Like the harvester, the bot never breaks blocks: {@code allowBreak} is forced off for the
 * whole run. For every grid cell it walks to a path stand within reach of the target block, puts
 * the right apricorn in its hand (swapping it into the hotbar from the main inventory if needed),
 * right-clicks the soil, and verifies the sapling appeared before moving on.
 */
public class ApricornPlantProcess implements IBaritoneProcess {

    private static final double PRIORITY = IBaritoneProcess.DEFAULT_PRIORITY;
    /** Ticks between right-clicks, so the server can process each placement. */
    private static final int CLICK_COOLDOWN = 5;
    /** Placement attempts per target before it is given up on. */
    private static final int MAX_PLANT_ATTEMPTS = 3;
    /** Ticks to wait for the sapling block update after a click before re-checking. */
    private static final int VERIFY_TICKS = 10;
    private static final int STALL_LIMIT = 160;
    private static final int NO_PROGRESS_LIMIT = 120;
    private static final int GOAL_TIMEOUT_TICKS = 500;
    private static final double PROGRESS_EPSILON = 0.25;
    private static final double ARRIVAL_TOLERANCE = 1.6;
    private static final int UNREACHABLE_GRACE_TICKS = 20;
    private static final double REACH_MARGIN = 0.5;
    /** How far below the selection a column may be searched for a stand. */
    private static final int STAND_SEARCH_DEPTH = 4;
    /** How far outside the selection the player may get before the run is abandoned. */
    private static final double LEASH_HORIZONTAL = 48.0;
    private static final double LEASH_VERTICAL = 24.0;
    private static final long MAX_SELECTION_VOLUME = 2_000_000L;
    private static final int MAX_SELECTION_HEIGHT = 64;

    private final IBaritone baritone;
    private final IPlayerContext ctx;

    /** One grid cell to plant: the soil block; the sapling goes on {@code soil.above()}. */
    private static final class Target {
        final BlockPos soil;
        final ApricornType type;
        int attempts;

        Target(BlockPos soil, ApricornType type) {
            this.soil = soil;
            this.type = type;
        }
    }

    private enum Phase { WALK, EQUIP, CLICK, VERIFY }

    private boolean running;
    private boolean paused;
    private BlockPos selMin;
    private BlockPos selMax;

    private final List<Target> targets = new ArrayList<>();
    private int targetIndex;
    private Phase phase = Phase.WALK;
    private int phaseTicks;
    private int clickCooldown;
    private int planted;
    private int skipped;

    /** Colours the player has run out of; their remaining targets are skipped for this run. */
    private final EnumSet<ApricornType> exhausted = EnumSet.noneOf(ApricornType.class);
    /** Stands that could not be reached, so they are not tried again for later targets. */
    private final Set<BlockPos> skippedStands = new HashSet<>();
    /** Feet position of every walkable, non-tree column of the selection: the farm paths. */
    private final List<BlockPos> pathStands = new ArrayList<>();

    private BlockPos currentGoalStand;
    private long goalSetTick;
    private double bestGoalDistSq;
    private int noProgressTicks;
    private int stallTicks;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    private boolean previousAllowBreak;
    /** Hotbar slot selected before the process started juggling apricorns, restored at the end. */
    private int previousSlot = -1;

    public ApricornPlantProcess(IBaritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    private static void logDebug(String message) {
        Helper.HELPER.logDebug(message);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called from {@code #plant} and from the GUI's Start button. */
    public void start() {
        if (running) {
            logDirect("Already planting. Use #plant stop to cancel first.");
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        ISelection selection = baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            selection = baritone.getSelectionManager().getLastSelection();
        }
        if (selection == null) {
            logDirect("No selection. Select the field first with #sel pos1 and #sel pos2.");
            return;
        }
        this.selMin = selection.min();
        this.selMax = selection.max();
        long volume = (long) (selMax.getX() - selMin.getX() + 1)
                * (long) (selMax.getZ() - selMin.getZ() + 1)
                * (long) (selMax.getY() - selMin.getY() + 1);
        if (volume > MAX_SELECTION_VOLUME || selMax.getY() - selMin.getY() + 1 > MAX_SELECTION_HEIGHT) {
            logDirect("Selection is too large to plant (" + volume + " blocks). Pick a smaller area.");
            return;
        }

        targets.clear();
        targetIndex = 0;
        phase = Phase.WALK;
        phaseTicks = 0;
        clickCooldown = 0;
        planted = 0;
        skipped = 0;
        paused = false;
        exhausted.clear();
        skippedStands.clear();
        pathStands.clear();
        currentGoalStand = null;
        goalSetTick = ctx.world().getGameTime();
        bestGoalDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        stallTicks = 0;
        lastPlayerPos = ctx.player().position();
        previousSlot = ctx.player().getInventory().selected;

        previousAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
        BaritoneAPI.getSettings().allowBreak.value = false;

        for (int x = selMin.getX(); x <= selMax.getX(); x++) {
            for (int z = selMin.getZ(); z <= selMax.getZ(); z++) {
                BlockPos stand = pathStand(x, z);
                if (stand != null) {
                    pathStands.add(stand);
                }
            }
        }

        buildTargets();

        if (targets.isEmpty()) {
            BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
            logDirect("Nothing to plant: no free soil on the " + PlantConfig.getSpacing()
                    + "-block grid with " + PlantConfig.getClearance()
                    + " blocks of clearance inside the selection.");
            return;
        }

        running = true;
        logDirect("Planting started in selection " + selMin + " -> " + selMax + ": "
                + targets.size() + " spots, spacing " + PlantConfig.getSpacing()
                + ", clearance " + PlantConfig.getClearance()
                + ", snap " + PlantConfig.getRowTolerance()
                + ", rows " + PlantConfig.getRowAxis().label() + ".");
    }

    /**
     * Walks the grid boustrophedon (row by row, alternating direction) and records every cell
     * whose column has free, plantable soil inside the selection. Rows run along X or along Z,
     * depending on {@link PlantConfig#getRowAxis()}; the row coordinate is the one on the other
     * axis, which is what carries the row's colour.
     */
    private void buildTargets() {
        boolean eastWest = PlantConfig.getRowAxis() == PlantConfig.RowAxis.EAST_WEST;
        List<Integer> rows = PlantConfig.rowsOf(selMin, selMax);
        List<Integer> cols = PlantConfig.columnsOf(selMin, selMax);
        List<BlockPos> chosen = new ArrayList<>();
        boolean reverse = false;
        for (int rowCoord : rows) {
            ApricornType type = PlantConfig.getRowType(rowCoord);
            for (int i = 0; i < cols.size(); i++) {
                int alongRow = cols.get(reverse ? cols.size() - 1 - i : i);
                int x = eastWest ? alongRow : rowCoord;
                int z = eastWest ? rowCoord : alongRow;
                BlockPos soil = findSpotNear(x, z, chosen);
                if (soil != null) {
                    targets.add(new Target(soil, type));
                    chosen.add(soil);
                }
            }
            reverse = !reverse;
        }
    }

    /**
     * The usable planting spot closest to grid cell (x, z), searched within
     * {@link PlantConfig#getRowTolerance()} blocks of it.
     *
     * <p>A perfectly square field never needs this - the cell itself is used. It matters on real
     * farms, where a row bends round an obstacle, a bed sits a block off, or the soil is patchy:
     * with a strict grid every such cell is skipped and the row comes out full of holes. The
     * nudged spot still has to be plantable soil with the configured clearance, and must keep a
     * sensible gap from the spots already picked so nudging cannot bunch two trees together.
     *
     * @param chosen spots already planned for this run, used for the spacing check
     */
    private BlockPos findSpotNear(int x, int z, List<BlockPos> chosen) {
        int tolerance = PlantConfig.getRowTolerance();
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -tolerance; dx <= tolerance; dx++) {
            for (int dz = -tolerance; dz <= tolerance; dz++) {
                int distance = Math.abs(dx) + Math.abs(dz);
                if (distance >= bestDistance) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                if (cx < selMin.getX() || cx > selMax.getX()
                        || cz < selMin.getZ() || cz > selMax.getZ()) {
                    continue;
                }
                BlockPos soil = plantableSoil(cx, cz);
                if (soil == null || !hasGrowingRoom(soil) || tooCloseToChosen(soil, chosen)) {
                    continue;
                }
                best = soil;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * True when the candidate would sit closer to an already-planned spot than the grid allows.
     * The gap shrinks with the tolerance (a wobbly row is allowed to be a little tighter), but
     * never below one clear block between two plants.
     */
    private boolean tooCloseToChosen(BlockPos candidate, List<BlockPos> chosen) {
        int minGap = Math.max(2, PlantConfig.getSpacing() - PlantConfig.getRowTolerance());
        for (BlockPos other : chosen) {
            int dx = Math.abs(other.getX() - candidate.getX());
            int dz = Math.abs(other.getZ() - candidate.getZ());
            if (Math.max(dx, dz) < minGap) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the spot has {@link PlantConfig#getClearance()} blocks of free space around it on
     * every side, so the tree has room to grow. Anything solid at plant level or one block above -
     * a wall, a fence, a pillar, another tree - inside that radius disqualifies the spot. The grid
     * itself is already inset from the selection border by the same clearance
     * (see {@code PlantConfig#steps}), this check catches walls standing inside the selection.
     */
    private boolean hasGrowingRoom(BlockPos soil) {
        int clearance = PlantConfig.getClearance();
        if (clearance <= 0) {
            return true;
        }
        BlockPos plant = soil.above();
        for (int dx = -clearance; dx <= clearance; dx++) {
            for (int dz = -clearance; dz <= clearance; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = plant.offset(dx, dy, dz);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!state.getCollisionShape(ctx.world(), pos).isEmpty()
                            || ApricornBlocks.isTreeBlock(state)
                            || ApricornPlanting.isSapling(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The highest block in column (x, z) inside the selection that an apricorn can be planted on,
     * or null when the column has none (already planted, occupied, or not soil).
     */
    private BlockPos plantableSoil(int x, int z) {
        for (int y = selMax.getY(); y >= selMin.getY() - 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = ctx.world().getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            // First non-air block from the top: the surface. Only that one may be planted on.
            return ApricornPlanting.canPlantAt(ctx.world(), pos) ? pos : null;
        }
        return null;
    }

    public void stop() {
        if (!running) {
            logDirect("Planting is not running.");
            return;
        }
        finish("Planting stopped. Planted " + planted + ", skipped " + skipped + ".");
    }

    public void pause() {
        if (!running) {
            logDirect("Planting is not running.");
            return;
        }
        if (paused) {
            logDirect("Planting is already paused.");
            return;
        }
        paused = true;
        logDirect("Planting paused. Type #plant resume to continue.");
    }

    public void resume() {
        if (!running) {
            logDirect("Planting is not running.");
            return;
        }
        if (!paused) {
            logDirect("Planting is not paused.");
            return;
        }
        paused = false;
        currentGoalStand = null;
        stallTicks = 0;
        lastPlayerPos = ctx.player() != null ? ctx.player().position() : Vec3.ZERO;
        logDirect("Planting resumed.");
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isActive() {
        return running;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!running) {
            return null;
        }
        if (paused) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ctx.player() == null || ctx.world() == null) {
            // Still active, and Baritone throws when an active process returns no command.
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (wanderedOff()) {
            finish("Wandered too far from the selection - planting stopped.");
            return null;
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        while (true) {
            if (targetIndex >= targets.size()) {
                finish("Planting complete. Planted " + planted + ", skipped " + skipped + ".");
                return null;
            }
            Target target = targets.get(targetIndex);
            BlockPos plantPos = target.soil.above();

            // Someone (or a previous attempt) already filled the spot.
            if (!ApricornPlanting.canPlantAt(ctx.world(), target.soil)) {
                if (ApricornPlanting.isSapling(ctx.world().getBlockState(plantPos))) {
                    planted++;
                } else {
                    skipped++;
                }
                nextTarget();
                continue;
            }
            if (exhausted.contains(target.type)) {
                skipped++;
                nextTarget();
                continue;
            }

            switch (phase) {
                case WALK: {
                    BlockPos stand = bestStandFor(plantPos);
                    if (stand == null) {
                        logDirect("No reachable stand for the spot at " + plantPos + ", skipping.");
                        skipped++;
                        nextTarget();
                        continue;
                    }
                    trackGoal(stand);
                    if (arrivedAt(stand)) {
                        phase = Phase.EQUIP;
                        phaseTicks = 0;
                        continue;
                    }
                    if (calcFailed || goalUnreachable(stand) || stuck(stand)) {
                        logDirect("Cannot reach stand " + stand + " for " + plantPos + ", skipping stand.");
                        skippedStands.add(stand);
                        continue;
                    }
                    return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
                }
                case EQUIP: {
                    if (!equip(target.type)) {
                        logDirect("Out of " + ApricornPlanting.displayName(target.type)
                                + " Apricorns - skipping the rest of that colour.");
                        exhausted.add(target.type);
                        skipped++;
                        nextTarget();
                        continue;
                    }
                    phase = Phase.CLICK;
                    phaseTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                case CLICK: {
                    target.attempts++;
                    if (target.attempts > MAX_PLANT_ATTEMPTS) {
                        logDirect("Could not plant at " + plantPos + " after " + MAX_PLANT_ATTEMPTS
                                + " attempts, skipping.");
                        skipped++;
                        nextTarget();
                        continue;
                    }
                    plantAt(target);
                    phase = Phase.VERIFY;
                    phaseTicks = 0;
                    clickCooldown = CLICK_COOLDOWN;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                case VERIFY: {
                    phaseTicks++;
                    BlockState state = ctx.world().getBlockState(plantPos);
                    if (ApricornPlanting.isSapling(state)) {
                        planted++;
                        nextTarget();
                        continue;
                    }
                    if (phaseTicks < VERIFY_TICKS) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    // Nothing appeared: click again (attempt counter ends the retries).
                    phase = Phase.CLICK;
                    phaseTicks = 0;
                    continue;
                }
                default:
                    // Unreachable, but an active process must never return no command.
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
    }

    private void nextTarget() {
        targetIndex++;
        phase = Phase.WALK;
        phaseTicks = 0;
    }

    // ------------------------------------------------------------------ planting

    /** Right-clicks the top face of the target's soil block with the apricorn in hand. */
    private void plantAt(Target target) {
        Vec3 eye = ctx.playerHead();
        BlockPos soil = target.soil;
        Vec3 clickPoint = new Vec3(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5);
        if (eye.distanceToSqr(clickPoint) > reachSq()) {
            logDebug("Soil at " + soil + " out of reach from here.");
            return;
        }
        baritone.getLookBehavior().updateTarget(rotationTo(eye, clickPoint), true);
        BlockHitResult hit = new BlockHitResult(clickPoint, Direction.UP, soil, false);
        try {
            InteractionResult result = ctx.playerController()
                    .processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            logDebug("Planted " + ApricornPlanting.displayName(target.type) + " at "
                    + soil.above() + " -> " + result);
        } catch (Throwable t) {
            logDebug("Planting at " + soil + " failed: " + t);
        }
    }

    /**
     * Puts an apricorn of the given colour in the main hand. Hotbar slots are selected directly;
     * a stack found in the main inventory is swapped into the current hotbar slot first. Returns
     * false when the player has none of that colour at all.
     */
    private boolean equip(ApricornType type) {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (ApricornPlanting.isStackOf(inv.getItem(i), type)) {
                if (inv.selected != i) {
                    inv.selected = i;
                    ctx.playerController().syncHeldItem();
                }
                return true;
            }
        }
        for (int i = 9; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!ApricornPlanting.isStackOf(stack, type)) {
                continue;
            }
            // InventoryMenu slot indices match the main-inventory indices 9..35, and a SWAP click
            // with button = hotbar index moves the stack into that hotbar slot.
            int hotbarSlot = inv.selected;
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                    ctx.player().inventoryMenu.containerId, i, hotbarSlot, ClickType.SWAP, ctx.player());
            ctx.playerController().syncHeldItem();
            return ApricornPlanting.isStackOf(inv.getItem(hotbarSlot), type);
        }
        return false;
    }

    // ------------------------------------------------------------------ stands / movement

    /**
     * Copy of the harvester's stand scan: highest walkable, non-tree surface of a column, searched
     * no deeper than {@link #STAND_SEARCH_DEPTH} below the selection so an open column cannot
     * produce a stand far under the farm (which the bot would then walk off to reach).
     */
    private BlockPos pathStand(int x, int z) {
        int top = selMax.getY() + 2;
        int bottom = selMin.getY() - STAND_SEARCH_DEPTH;
        for (int y = top; y >= bottom; y--) {
            BlockPos belowPos = new BlockPos(x, y, z);
            BlockState below = ctx.world().getBlockState(belowPos);
            if (ApricornBlocks.isTreeBlock(below) || ApricornPlanting.isSapling(below)) {
                continue;
            }
            if (below.getCollisionShape(ctx.world(), belowPos).isEmpty()) {
                continue;
            }
            BlockPos feetPos = new BlockPos(x, y + 1, z);
            BlockPos headPos = new BlockPos(x, y + 2, z);
            if (!ctx.world().getBlockState(feetPos).getCollisionShape(ctx.world(), feetPos).isEmpty()) {
                continue;
            }
            if (!ctx.world().getBlockState(headPos).getCollisionShape(ctx.world(), headPos).isEmpty()) {
                continue;
            }
            return new BlockPos(x, y + 1, z);
        }
        return null;
    }

    /**
     * The closest path stand from which the plant position can be right-clicked. The target's own
     * column is excluded: standing on the spot makes it impossible to place the sapling there.
     */
    private BlockPos bestStandFor(BlockPos plantPos) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos s : pathStands) {
            if (skippedStands.contains(s)) {
                continue;
            }
            if (s.getX() == plantPos.getX() && s.getZ() == plantPos.getZ()) {
                continue;
            }
            if (Math.abs(s.getX() - plantPos.getX()) > 4 || Math.abs(s.getZ() - plantPos.getZ()) > 4) {
                continue;
            }
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
            double d = eye.distanceToSqr(new Vec3(plantPos.getX() + 0.5, plantPos.getY(), plantPos.getZ() + 0.5));
            if (d <= reachSq() && d < bestDistSq) {
                bestDistSq = d;
                best = s;
            }
        }
        return best;
    }

    private double reachSq() {
        double reach = ctx.playerController().getBlockReachDistance() + REACH_MARGIN;
        return reach * reach;
    }

    private Rotation rotationTo(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        double len = d.length();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = len < 1e-6 ? 0.0 : Math.toDegrees(Math.asin(-d.y / len));
        return new Rotation((float) yaw, (float) pitch);
    }

    private void trackGoal(BlockPos stand) {
        if (stand.equals(currentGoalStand)) {
            return;
        }
        currentGoalStand = stand;
        goalSetTick = ctx.world().getGameTime();
        bestGoalDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        stallTicks = 0;
        lastPlayerPos = ctx.player().position();
    }

    private Vec3 standCenter(BlockPos stand) {
        return new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
    }

    private boolean arrivedAt(BlockPos stand) {
        if (new GoalBlock(stand).isInGoal(ctx.playerFeet())) {
            return true;
        }
        IPathingBehavior pathing = baritone.getPathingBehavior();
        if (pathing.isPathing() || pathing.getInProgress().isPresent()) {
            return false;
        }
        Vec3 p = ctx.player().position();
        Vec3 c = standCenter(stand);
        double dx = p.x - c.x;
        double dz = p.z - c.z;
        return Math.abs(p.y - c.y) <= 1.0
                && dx * dx + dz * dz <= ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE;
    }

    private boolean stuck(BlockPos stand) {
        Vec3 p = ctx.player().position();
        if (p.distanceToSqr(lastPlayerPos) < 1e-4) {
            stallTicks++;
        } else {
            stallTicks = 0;
            lastPlayerPos = p;
        }
        double distSq = p.distanceToSqr(standCenter(stand));
        if (distSq < bestGoalDistSq - PROGRESS_EPSILON) {
            bestGoalDistSq = distSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
        return stallTicks > STALL_LIMIT
                || noProgressTicks > NO_PROGRESS_LIMIT
                || ctx.world().getGameTime() - goalSetTick > GOAL_TIMEOUT_TICKS;
    }

    private boolean goalUnreachable(BlockPos stand) {
        if (ctx.world().getGameTime() - goalSetTick < UNREACHABLE_GRACE_TICKS) {
            return false;
        }
        IPathingBehavior pathing = baritone.getPathingBehavior();
        Optional<? extends IPathFinder> search = pathing.getInProgress();
        if (search.isPresent()) {
            return search.get().isFinished() && !search.get().bestPathSoFar().isPresent();
        }
        Optional<IPath> path = pathing.getPath();
        if (path.isPresent()) {
            if (pathing.isPathing()) {
                return false;
            }
            return Math.abs(path.get().getDest().getY() - stand.getY()) > 1;
        }
        return false;
    }

    // ------------------------------------------------------------------ teardown

    /** True when the player has ended up far outside the work area; see the harvester's copy. */
    private boolean wanderedOff() {
        Vec3 p = ctx.player().position();
        double dx = Math.max(0, Math.max(selMin.getX() - p.x, p.x - (selMax.getX() + 1)));
        double dz = Math.max(0, Math.max(selMin.getZ() - p.z, p.z - (selMax.getZ() + 1)));
        double dy = Math.max(0, Math.max(selMin.getY() - p.y, p.y - (selMax.getY() + 1)));
        return dx > LEASH_HORIZONTAL || dz > LEASH_HORIZONTAL || dy > LEASH_VERTICAL;
    }

    private void finish(String message) {
        running = false;
        paused = false;
        targets.clear();
        targetIndex = 0;
        phase = Phase.WALK;
        pathStands.clear();
        skippedStands.clear();
        exhausted.clear();
        BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        if (previousSlot >= 0 && previousSlot < 9 && ctx.player() != null) {
            ctx.player().getInventory().selected = previousSlot;
            ctx.playerController().syncHeldItem();
        }
        previousSlot = -1;
        // Deliberately no cancelEverything() here: finish() runs inside the pathing control
        // manager's iteration over its active-process list (see ApricornHarvestProcess#finish).
        logDirect(message);
    }

    @Override
    public void onLostControl() {
        if (running) {
            finish("Planting stopped (another Baritone action took over).");
        }
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public String displayName() {
        return "Apricorn Planter";
    }

    @Override
    public String displayName0() {
        return "Apricorn Planter";
    }
}
