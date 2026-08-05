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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Baritone process that bone-meals every apricorn sapling inside the selection until it grows.
 *
 * <p>For every {@code pixelmon:apricorn_plant_*} block in the selection the bot walks to a path
 * stand within reach, puts bone meal in its hand and right-clicks the sapling until the block is
 * no longer a sapling (it has become a tree) or {@link #maxApplications()} applications have been
 * spent on it. Nothing is ever broken: {@code allowBreak} is forced off for the whole run, and the
 * run ends as soon as the player is out of bone meal.
 */
public class ApricornBonemealProcess implements IBaritoneProcess {

    private static final double PRIORITY = IBaritoneProcess.DEFAULT_PRIORITY;
    /** Ticks between right-clicks, so the server can process each application. */
    private static final int CLICK_COOLDOWN = 4;
    /** Bone meal applications spent on a single sapling before it is given up on. */
    private static int maxApplications() {
        return AddonSettings.getBonemealMax();
    }
    /** Ticks to wait for the block update after a click before deciding it did not grow. */
    private static final int VERIFY_TICKS = 6;
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

    private enum Phase { WALK, EQUIP, CLICK, VERIFY }

    private boolean running;
    private boolean paused;
    private BlockPos selMin;
    private BlockPos selMax;

    /** Sapling positions still to grow, in walk order. */
    private final List<BlockPos> saplings = new ArrayList<>();
    private int index;
    private int applications;
    private Phase phase = Phase.WALK;
    private int phaseTicks;
    private int clickCooldown;
    private int grown;
    private int skipped;

    private final Set<BlockPos> skippedStands = new HashSet<>();
    private final List<BlockPos> pathStands = new ArrayList<>();

    private BlockPos currentGoalStand;
    private long goalSetTick;
    private double bestGoalDistSq;
    private int noProgressTicks;
    private int stallTicks;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    private boolean previousAllowBreak;
    /** Leaf blocks this run added to Baritone's blocksToAvoid, so only those are removed after. */
    private final List<net.minecraft.world.level.block.Block> canopyBlocksAdded = new ArrayList<>();
    private int previousSlot = -1;

    public ApricornBonemealProcess(IBaritone baritone) {
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

    /** Called from the {@code #bonemeal} command. */
    public void start() {
        if (running) {
            logDirect("Already bone-mealing. Use #bonemeal stop to cancel first.");
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
            logDirect("Selection is too large (" + volume + " blocks). Pick a smaller area.");
            return;
        }
        if (countBoneMeal() == 0) {
            logDirect("No bone meal in your inventory.");
            return;
        }

        saplings.clear();
        index = 0;
        applications = 0;
        phase = Phase.WALK;
        phaseTicks = 0;
        clickCooldown = 0;
        grown = 0;
        skipped = 0;
        paused = false;
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
        // Bone-meal from the paths, never from on top of a bush.
        CanopyAvoidance.avoid(canopyBlocksAdded);

        for (int x = selMin.getX(); x <= selMax.getX(); x++) {
            for (int z = selMin.getZ(); z <= selMax.getZ(); z++) {
                BlockPos stand = pathStand(x, z);
                if (stand != null) {
                    pathStands.add(stand);
                }
            }
        }

        collectSaplings();

        if (saplings.isEmpty()) {
            BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        CanopyAvoidance.release(canopyBlocksAdded);
            logDirect("No apricorn saplings in the selection.");
            return;
        }

        running = true;
        logDirect("Bone-mealing " + saplings.size() + " apricorn saplings in selection "
                + selMin + " -> " + selMax + ".");
    }

    /** Scans the selection for apricorn saplings, row by row so the walk stays local. */
    private void collectSaplings() {
        boolean reverse = false;
        for (int z = selMin.getZ(); z <= selMax.getZ(); z++) {
            for (int i = 0; i <= selMax.getX() - selMin.getX(); i++) {
                int x = reverse ? selMax.getX() - i : selMin.getX() + i;
                for (int y = selMin.getY(); y <= selMax.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ApricornPlanting.isSapling(ctx.world().getBlockState(pos))) {
                        saplings.add(pos);
                    }
                }
            }
            reverse = !reverse;
        }
    }

    public void stop() {
        if (!running) {
            logDirect("Bone-mealing is not running.");
            return;
        }
        finish("Bone-mealing stopped. Grown " + grown + ", skipped " + skipped + ".");
    }

    public void pause() {
        if (!running) {
            logDirect("Bone-mealing is not running.");
            return;
        }
        if (paused) {
            logDirect("Bone-mealing is already paused.");
            return;
        }
        paused = true;
        logDirect("Bone-mealing paused. Type #bonemeal resume to continue.");
    }

    public void resume() {
        if (!running) {
            logDirect("Bone-mealing is not running.");
            return;
        }
        if (!paused) {
            logDirect("Bone-mealing is not paused.");
            return;
        }
        paused = false;
        currentGoalStand = null;
        stallTicks = 0;
        lastPlayerPos = ctx.player() != null ? ctx.player().position() : Vec3.ZERO;
        logDirect("Bone-mealing resumed.");
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
            finish("Wandered too far from the selection - bone-mealing stopped.");
            return null;
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        while (true) {
            if (index >= saplings.size()) {
                finish("Bone-mealing complete. Grown " + grown + ", skipped " + skipped + ".");
                return null;
            }
            BlockPos sapling = saplings.get(index);
            BlockState state = ctx.world().getBlockState(sapling);

            if (!ApricornPlanting.isSapling(state)) {
                // Grown (or gone) since the scan.
                if (applications > 0) {
                    grown++;
                }
                nextSapling();
                continue;
            }
            if (applications >= maxApplications()) {
                logDirect("Sapling at " + sapling + " did not grow after " + maxApplications()
                        + " bone meals, skipping.");
                skipped++;
                nextSapling();
                continue;
            }

            switch (phase) {
                case WALK: {
                    BlockPos stand = bestStandFor(sapling);
                    if (stand == null) {
                        logDirect("No reachable stand for the sapling at " + sapling + ", skipping.");
                        skipped++;
                        nextSapling();
                        continue;
                    }
                    trackGoal(stand);
                    if (arrivedAt(stand)) {
                        phase = Phase.EQUIP;
                        phaseTicks = 0;
                        continue;
                    }
                    if (calcFailed || goalUnreachable(stand) || stuck(stand)) {
                        logDirect("Cannot reach stand " + stand + " for " + sapling + ", skipping stand.");
                        skippedStands.add(stand);
                        continue;
                    }
                    return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
                }
                case EQUIP: {
                    if (!equipBoneMeal()) {
                        finish("Out of bone meal. Grown " + grown + ", " + (saplings.size() - index)
                                + " saplings left.");
                        return null;
                    }
                    phase = Phase.CLICK;
                    phaseTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                case CLICK: {
                    applications++;
                    applyBoneMeal(sapling);
                    phase = Phase.VERIFY;
                    phaseTicks = 0;
                    clickCooldown = CLICK_COOLDOWN;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                case VERIFY: {
                    phaseTicks++;
                    if (!ApricornPlanting.isSapling(ctx.world().getBlockState(sapling))) {
                        grown++;
                        nextSapling();
                        continue;
                    }
                    if (phaseTicks < VERIFY_TICKS) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    // Still a sapling: apply again (the application counter ends the retries).
                    phase = Phase.EQUIP;
                    phaseTicks = 0;
                    continue;
                }
                default:
                    // Unreachable, but an active process must never return no command.
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
    }

    private void nextSapling() {
        index++;
        applications = 0;
        phase = Phase.WALK;
        phaseTicks = 0;
    }

    // ------------------------------------------------------------------ bone meal

    /** Right-clicks the sapling with the bone meal in hand. */
    private void applyBoneMeal(BlockPos sapling) {
        Vec3 eye = ctx.playerHead();
        Vec3 clickPoint = nearestPointOnBlock(eye, sapling);
        if (eye.distanceToSqr(clickPoint) > reachSq()) {
            logDebug("Sapling at " + sapling + " out of reach from here.");
            return;
        }
        baritone.getLookBehavior().updateTarget(rotationTo(eye, clickPoint), true);
        BlockHitResult hit = new BlockHitResult(clickPoint, faceToward(eye, sapling), sapling, false);
        try {
            InteractionResult result = ctx.playerController()
                    .processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            logDebug("Bone meal on " + sapling + " -> " + result);
        } catch (Throwable t) {
            logDebug("Bone meal on " + sapling + " failed: " + t);
        }
    }

    /** Total bone meal in the player's inventory. */
    private int countBoneMeal() {
        Inventory inv = ctx.player().getInventory();
        int total = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == Items.BONE_MEAL) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Puts bone meal in the main hand: a hotbar stack is selected directly, a stack in the main
     * inventory is swapped into the held slot first. False when there is none left.
     */
    private boolean equipBoneMeal() {
        Inventory inv = ctx.player().getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == Items.BONE_MEAL) {
                if (inv.selected != i) {
                    inv.selected = i;
                    ctx.playerController().syncHeldItem();
                }
                return true;
            }
        }
        for (int i = 9; i < inv.items.size(); i++) {
            if (inv.getItem(i).getItem() != Items.BONE_MEAL) {
                continue;
            }
            int hotbarSlot = inv.selected;
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                    ctx.player().inventoryMenu.containerId, i, hotbarSlot, ClickType.SWAP, ctx.player());
            ctx.playerController().syncHeldItem();
            return inv.getItem(hotbarSlot).getItem() == Items.BONE_MEAL;
        }
        return false;
    }

    // ------------------------------------------------------------------ stands / movement

    /**
     * Highest walkable, non-tree surface of a column, searched no deeper than
     * {@link #STAND_SEARCH_DEPTH} below the selection so an open column cannot produce a stand far
     * under the farm.
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

    /** The closest path stand from which the sapling can be right-clicked. */
    private BlockPos bestStandFor(BlockPos sapling) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos s : pathStands) {
            if (skippedStands.contains(s)) {
                continue;
            }
            if (Math.abs(s.getX() - sapling.getX()) > 4 || Math.abs(s.getZ() - sapling.getZ()) > 4) {
                continue;
            }
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
            double d = eye.distanceToSqr(nearestPointOnBlock(eye, sapling));
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

    private Vec3 nearestPointOnBlock(Vec3 p, BlockPos pos) {
        double x = clamp(p.x, pos.getX(), pos.getX() + 1.0);
        double y = clamp(p.y, pos.getY(), pos.getY() + 1.0);
        double z = clamp(p.z, pos.getZ(), pos.getZ() + 1.0);
        return new Vec3(x, y, z);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    private Direction faceToward(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
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
        saplings.clear();
        index = 0;
        applications = 0;
        phase = Phase.WALK;
        pathStands.clear();
        skippedStands.clear();
        BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        CanopyAvoidance.release(canopyBlocksAdded);
        if (previousSlot >= 0 && previousSlot < 9 && ctx.player() != null) {
            ctx.player().getInventory().selected = previousSlot;
            ctx.playerController().syncHeldItem();
        }
        previousSlot = -1;
        // No cancelEverything() here, see ApricornHarvestProcess#finish.
        logDirect(message);
    }

    @Override
    public void onLostControl() {
        if (running) {
            finish("Bone-mealing stopped (another Baritone action took over).");
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
        return "Apricorn Bonemealer";
    }

    @Override
    public String displayName0() {
        return "Apricorn Bonemealer";
    }
}
