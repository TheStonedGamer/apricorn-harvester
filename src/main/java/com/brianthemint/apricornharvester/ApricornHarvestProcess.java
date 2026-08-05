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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Baritone process that harvests mature Pixelmon apricorns inside a selection.
 *
 * <p>The bot never breaks blocks and never climbs onto trees: it only walks on the farm's
 * 1-block-wide paths (columns with a walkable non-tree surface). Two phases:
 * <ol>
 *   <li><b>Patrol</b> - boustrophedon walk over every <em>path</em> column of the selection at
 *       one-block stops. Tree columns are skipped. At every stop the 3x3 footprint around it is
 *       scanned (whole selection height) and every mature apricorn within reach is right-click
 *       harvested. Once a stop is picked clean, the bot walks round the bush and collects the
 *       apricorns that just fell before moving on to the next column.</li>
 *   <li><b>Climb pass</b> - after the patrol, the selection is rescanned; any apricorn still
 *       mature could not be reached from the ground stops, so the bot visits the path stand
 *       closest to each one ({@link GoalBlock}) and harvests whatever is within reach from
 *       there. Targets no path stand can reach are left standing. Only runs when
 *       {@code #apricorn tops true}.</li>
 *   <li><b>Pickup pass</b> - finally the bot walks over every dropped apricorn lying inside the
 *       selection so it is collected by vanilla item pickup. Drops in tree columns (which the bot
 *       may not walk into) and drops that stay on the ground - a full inventory - are skipped.</li>
 * </ol>
 * While active, Baritone's {@code allowBreak} setting is forced to {@code false} (and restored
 * afterwards), so no block is ever broken.
 *
 * <p>The run can be paused in place with {@link #pause()} (from {@code #apricorn pause}) and
 * continued with {@link #resume()}. Baritone's global {@code #pause}/{@code #resume} also
 * freeze and continue this process: the harvester runs at {@link IBaritoneProcess#DEFAULT_PRIORITY}
 * so Baritone's pause process (one step higher) can take control without clearing harvest state.
 */
public class ApricornHarvestProcess implements IBaritoneProcess {

    private static final double PRIORITY = IBaritoneProcess.DEFAULT_PRIORITY;
    /** Ticks between right-clicks (server needs a moment to process each one). */
    private static final int CLICK_COOLDOWN = 5;
    /** Maximum click attempts per block before giving up on it. */
    private static final int MAX_CLICK_ATTEMPTS = 3;
    /** After clicking a block, wait this many ticks before re-checking/re-clicking it. */
    private static final long RESCAN_DELAY_TICKS = 12;
    /** Ticks without any player movement while a movement goal is set -> treat as stuck. */
    private static final int STALL_LIMIT = 160;
    /**
     * Ticks without getting any closer to the current goal -> treat as stuck. Catches the case
     * the plain stall check misses: the bot keeps moving (oscillating between two blocks, sliding
     * off a bed, re-pathing back and forth) but never actually approaches the stand.
     */
    private static final int NO_PROGRESS_LIMIT = 120;
    /** Hard ceiling on how long a single goal may be pursued before it is skipped. */
    private static final int GOAL_TIMEOUT_TICKS = 500;
    /** Distance (blocks) the player must close for a tick to count as progress. */
    private static final double PROGRESS_EPSILON = 0.25;
    /**
     * When Baritone is idle (no path, no search) and the player is this close to the stand, the
     * stop counts as reached even though the feet block is not exactly the goal block. Everything
     * harvested from a stop is reach-checked anyway, so an off-by-one stand is harmless - and
     * insisting on the exact block is what made the bot hang on stands it can stand beside but
     * not on (slabs, farmland edges, occupied blocks).
     */
    private static final double ARRIVAL_TOLERANCE = 1.6;
    /**
     * Ticks a goal must be set before a path that does not reach it counts as "unreachable".
     * Gives Baritone a moment to replace the previous path with one for the current goal.
     */
    private static final int UNREACHABLE_GRACE_TICKS = 20;
    /** Ticks to stand on a dropped apricorn before deciding it cannot be collected. */
    private static final int PICKUP_WAIT_TICKS = 30;
    /** Ticks to wait after the last click of a stop so the drops have spawned before collecting. */
    private static final int DROP_SETTLE_TICKS = 15;
    /** Horizontal radius (blocks) around a patrol stop that counts as "around this bush". */
    private static final double LOCAL_PICKUP_RADIUS = 5.0;
    private static final long MAX_SELECTION_VOLUME = 2_000_000L;
    private static final int MAX_SELECTION_HEIGHT = 64;
    /** Extra margin added to the vanilla block reach when deciding what we can click. */
    private static final double REACH_MARGIN = 0.5;
    /**
     * How far below the selection a column may be searched for a stand. Deep enough for sunken
     * paths and irrigation channels, shallow enough that an open column never sends the bot down
     * a ravine or into a cave to "reach" a stand that has nothing to do with the farm.
     */
    private static final int STAND_SEARCH_DEPTH = 4;
    /**
     * How far outside the selection the player may get before the run is abandoned. Generous
     * enough for pathing around the outside of the farm, tight enough to catch a bot that is
     * walking off to somewhere it has no business being.
     */
    private static final double LEASH_HORIZONTAL = 48.0;
    private static final double LEASH_VERTICAL = 24.0;

    private final IBaritone baritone;
    private final IPlayerContext ctx;

    private boolean running;
    /** True while the run is temporarily frozen by #pause; patrol state is kept so #resume continues. */
    private boolean paused;
    private BlockPos selMin;
    private BlockPos selMax;

    /** The path stands the sweep will visit, in walking order. */
    private final List<BlockPos> harvestStands = new ArrayList<>();
    private int standIndex;
    /** Stands Baritone could not reach at all. Those stay skipped for the whole run. */
    private final Set<BlockPos> unreachableStands = new HashSet<>();
    private boolean patrolDone;
    /**
     * True once the selection has been re-scanned after the first pass. The bush list is built at
     * start, so anything that ripened during the run - or that no bush claimed - would otherwise
     * be left standing.
     */
    private boolean rescanned;

    /**
     * Whether the climb pass runs at all ({@code #apricorn tops true|false}, default false).
     *
     * <p>Apricorns in the middle and at the top of a tree cannot be reached from the 1-block
     * paths, so the climb pass ends up sending the bot at stand after stand that can never see
     * them - which reads as the bot getting stuck. With tops off the run ends after the patrol
     * and those apricorns are simply left standing.
     */
    private boolean harvestTops;

    /** True during the final pass that walks over dropped apricorns lying in the farm. */
    private boolean pickingUp;
    /** Entity ids of dropped items that could not be collected, so they are not retried forever. */
    private final Set<Integer> skippedItems = new HashSet<>();
    /** Ticks spent standing on the current item's column waiting for the pickup to happen. */
    private int pickupWait;
    /** Entity id the pickup wait counter belongs to, so a new item starts with a fresh wait. */
    private int pickupItemId = -1;
    /** True while collecting the drops around the bush that was just picked, mid-patrol. */
    private boolean localPickup;
    /** Ticks left to wait for the drops of the current stop to spawn. */
    private int localPickupDelay;
    /** Column the local pickup is centred on (the patrol stop whose bush was just picked). */
    private int localPickupX;
    private int localPickupZ;
    private final List<BlockPos> climbTargets = new ArrayList<>();
    /** One stand (feet position) per walkable, non-tree column: the farm paths. */
    private final List<BlockPos> pathStands = new ArrayList<>();
    /** Deduplicated path stands the climb pass will visit, chosen per remaining target. */
    private final List<BlockPos> climbStands = new ArrayList<>();
    /** The value of Baritone's allowBreak setting before this run, so it can be restored. */
    private boolean previousAllowBreak;

    private final Set<BlockPos> clickQueue = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> clickAttempts = new HashMap<>();
    private final Map<BlockPos, Long> lastClickTick = new HashMap<>();
    private int clickCooldown;

    private Vec3 lastPlayerPos;
    private int stallTicks;
    /** The stand the current movement goal points at, so the timers only reset on a real change. */
    private BlockPos currentGoalStand;
    /** When the current goal was first set (world time), for the grace period and hard timeout. */
    private long goalSetTick;
    /** Best (smallest) squared distance to the current stand seen so far, for progress detection. */
    private double bestGoalDistSq;
    /** Ticks since the player last got measurably closer to the current stand. */
    private enum TowerPhase {
        IDLE, EQUIP_DIRT, LOOK_DOWN, JUMP_PLACE, WAIT_LAND, HARVEST, LOOK_BREAK, BREAKING, WAIT_FALL
    }
    private TowerPhase towerPhase = TowerPhase.IDLE;
    private BlockPos towerBlockPos = null;
    private int towerTicks = 0;
    private int previousSlot = -1;
    private boolean towerSkippedForRun = false;

    /** Whether to deposit apricorns into a nearby chest after harvesting. */
    private boolean depositEnabled;
    /**
     * Deposit phase state machine. Runs after the pickup pass, only when the player has
     * apricorns in inventory and deposit is enabled.
     */
    private enum DepositPhase {
        IDLE, SCAN, WALK, OPEN, WAIT_SCREEN, DEPOSITING, CLOSE, DONE
    }
    private DepositPhase depositPhase = DepositPhase.IDLE;
    /** The container block the bot is walking to / interacting with. */
    private BlockPos depositChestPos;
    /** Ticks spent in the current deposit sub-phase. */
    private int depositTicks;
    /**
     * True when this deposit is an emergency trip in the middle of the harvest (the inventory
     * filled up) rather than the final one. The run continues afterwards instead of finishing.
     */
    private boolean midRunDeposit;
    /**
     * Set when a deposit trip failed in a way that will keep failing - no container in range, none
     * reachable, or one that had no room. Stops the bot shuttling to the same dead end every time
     * the inventory fills for the rest of the run.
     */
    private boolean depositUnavailable;
    /** Containers that turned out to be full during this run, so they are not tried again. */
    private final Set<BlockPos> triedContainers = new HashSet<>();
    /** Apricorns carried at the previous deposit tick, to tell a real transfer from a dud click. */
    private int depositLastCount = -1;
    /** Consecutive deposit clicks that moved nothing - the sign of a full container. */
    private int depositNoProgress;
    /** Dud clicks tolerated before a container is written off as full. */
    private static final int DEPOSIT_STALL_CLICKS = 3;

    private boolean harvestedAtThisStop;
    private int noProgressTicks;

    public ApricornHarvestProcess(IBaritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    /**
     * True for an apricorn this run should actually pick: ripe, and of a colour the colour filter
     * allows ({@code #apricorn colours ...} / the settings screen). Everything the harvester looks
     * at goes through here, so an unwanted colour is never clustered, walked to or clicked.
     */
    private static boolean isWanted(BlockState state) {
        if (!ApricornBlocks.isMature(state)) {
            return false;
        }
        ApricornType type = ApricornPlanting.typeOfLeaves(state);
        return type == null || AddonSettings.isColourHarvested(type);
    }

    /** "only Red, Blue" - the colour filter in words, for chat. */
    public static String colourFilterText() {
        if (AddonSettings.isEveryColourHarvested()) {
            return "all colours";
        }
        StringBuilder sb = new StringBuilder("only ");
        boolean first = true;
        for (ApricornType type : AddonSettings.getHarvestColours()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(ApricornPlanting.displayName(type));
            first = false;
        }
        return sb.toString();
    }

    /** Chat/log helpers; IBaritoneProcess does not extend Helper, so delegate to the API singleton. */
    private static void logDirect(String message) {
        Helper.HELPER.logDirect(message);
    }

    private static void logDebug(String message) {
        Helper.HELPER.logDebug(message);
    }

    /** Called from the {@code #apricorn} command. */
    public void start() {
        if (running) {
            logDirect("Already harvesting. Use #apricorn stop to cancel first.");
            return;
        }
        ISelection selection = baritone.getSelectionManager().getOnlySelection();
        if (selection == null) {
            selection = baritone.getSelectionManager().getLastSelection();
        }
        if (selection == null) {
            logDirect("No selection. Select the tree area first with #sel pos1 and #sel pos2.");
            return;
        }
        this.selMin = selection.min();
        this.selMax = selection.max();
        long volume = (long) (selMax.getX() - selMin.getX() + 1)
                * (long) (selMax.getZ() - selMin.getZ() + 1)
                * (long) (selMax.getY() - selMin.getY() + 1);
        if (volume > MAX_SELECTION_VOLUME || selMax.getY() - selMin.getY() + 1 > MAX_SELECTION_HEIGHT) {
            logDirect("Selection is too large to harvest (" + volume + " blocks). Pick a smaller area.");
            return;
        }

        // Pick up the persisted settings, so a restart keeps the configured behaviour.
        this.harvestTops = AddonSettings.isHarvestTops();
        this.depositEnabled = AddonSettings.isHarvestDeposit();

        this.harvestStands.clear();
        this.standIndex = 0;
        this.unreachableStands.clear();
        this.rescanned = false;
        this.patrolDone = false;
        this.pickingUp = false;
        this.localPickup = false;
        this.localPickupDelay = 0;
        this.skippedItems.clear();
        this.pickupWait = 0;
        this.pickupItemId = -1;
        this.paused = false;
        this.climbTargets.clear();
        this.climbStands.clear();
        this.pathStands.clear();
        this.clickQueue.clear();
        this.clickAttempts.clear();
        this.lastClickTick.clear();
        this.clickCooldown = 0;
        this.stallTicks = 0;
        this.lastPlayerPos = ctx.player() != null ? ctx.player().position() : Vec3.ZERO;
        this.goalSetTick = ctx.world() != null ? ctx.world().getGameTime() : 0L;
        this.currentGoalStand = null;
        this.bestGoalDistSq = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        this.harvestedAtThisStop = false;
        this.towerPhase = TowerPhase.IDLE;
        this.towerBlockPos = null;
        this.towerTicks = 0;
        this.previousSlot = -1;
        this.towerSkippedForRun = false;
        this.depositPhase = DepositPhase.IDLE;
        this.depositChestPos = null;
        this.depositTicks = 0;
        this.midRunDeposit = false;
        this.depositUnavailable = false;
        this.triedContainers.clear();
        this.depositLastCount = -1;
        this.depositNoProgress = 0;
        // The bot must never break apricorn leaves/logs: disable breaking for the whole run
        // (settings are read live by the pathfinder, so every path respects it).
        this.previousAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
        BaritoneAPI.getSettings().allowBreak.value = false;
        // Precalculate path stands
        this.pathStands.clear();
        for (int x = selMin.getX(); x <= selMax.getX(); x++) {
            for (int z = selMin.getZ(); z <= selMax.getZ(); z++) {
                BlockPos stand = pathStand(x, z);
                if (stand != null) {
                    pathStands.add(stand);
                }
            }
        }

        // Plan the sweep: which path stands can actually see an apricorn worth picking.
        buildSweep();
        this.patrolDone = this.harvestStands.isEmpty();

        this.running = true;
        logDirect("Apricorn harvesting started in selection " + selMin + " -> " + selMax
                + ". " + this.harvestStands.size() + " stop(s) to visit"
                + (AddonSettings.isEveryColourHarvested() ? "" : " (" + colourFilterText() + ")")
                + ".");
    }

    /**
     * Called from the {@code #apricorn stop} command (runs on the client/chat thread, not
     * inside Baritone's process iteration, so mutating state here is safe).
     *
     * <p>Stops immediately by flipping the flags. Pathing itself is cancelled by
     * {@link baritone.api.pathing.calc.IPathingControlManager} on the next tick: once this
     * process reports inactive, no process returns a {@link PathingCommand}, so preTick()
     * calls {@code cancelSegmentIfSafe()} and clears the goal.
     */
    public void stop() {
        if (!running) {
            logDirect("Apricorn harvesting is not running.");
            return;
        }
        running = false;
        paused = false;
        pickingUp = false;
        localPickup = false;
        skippedItems.clear();
        patrolDone = true;
        towerPhase = TowerPhase.IDLE;
        depositPhase = DepositPhase.IDLE;
        depositChestPos = null;
        depositTicks = 0;
        clickQueue.clear();
        clickAttempts.clear();
        lastClickTick.clear();
        climbTargets.clear();
        climbStands.clear();
        pathStands.clear();
        BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        logDirect("Apricorn harvesting stopped.");
    }

    /**
     * Pauses harvesting in place (harvest-only scope): the bot stops moving, clicking and
     * advancing, but keeps the whole run state so {@link #resume()} continues from where it
     * left off. While paused the process stays in control and returns {@code REQUEST_PAUSE},
     * the pattern Baritone's own pause/resume commands use.
     *
     * <p>Baritone's global {@code #pause}/{@code #resume} also freeze and resume this harvest
     * (their pause process outranks this one, but is temporary so state is kept); this method
     * only pauses the harvest without touching anything else Baritone is doing.
     */
    public void pause() {
        if (!running) {
            logDirect("Apricorn harvesting is not running.");
            return;
        }
        if (paused) {
            logDirect("Apricorn harvesting is already paused.");
            return;
        }
        paused = true;
        clickQueue.clear();
        clickAttempts.clear();
        lastClickTick.clear();
        logDirect("Apricorn harvesting paused. Type #apricorn resume to continue.");
    }

    /** Resumes a harvest paused with {@link #pause()}. */
    public void resume() {
        if (!running) {
            logDirect("Apricorn harvesting is not running.");
            return;
        }
        if (!paused) {
            logDirect("Apricorn harvesting is not paused.");
            return;
        }
        paused = false;
        stallTicks = 0;
        // Force fresh timers for whatever stand is next: time spent paused must not count as
        // time spent failing to reach it.
        currentGoalStand = null;
        lastPlayerPos = ctx.player() != null ? ctx.player().position() : Vec3.ZERO;
        logDirect("Apricorn harvesting resumed.");
    }

    @Override
    public boolean isActive() {
        return running;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        // NOTE: the first parameter is calcFailed (the pathfinder could not compute a path to the
        // goal we asked for), NOT "is this the first tick". Treating it as a start flag is what
        // spammed "Apricorn harvesting underway" every tick and, worse, threw away the one
        // authoritative unreachable signal Baritone gives us - so the bot sat on an impossible
        // stand until a timeout fired instead of skipping it at once.
        if (!running) {
            return null;
        }
        if (paused) {
            // Frozen by #apricorn pause: hold control, stop all movement and interaction.
            // REQUEST_PAUSE (with a null goal) keeps the in-progress goal alive so that
            // resume() continues pathing exactly where it left off.
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ctx.player() == null || ctx.world() == null) {
            // Mid-disconnect: still active, so hold rather than returning no command at all.
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (wanderedOff()) {
            finish("Wandered too far from the selection - harvest stopped. (Check the selection"
                    + " covers the farm; #apricorn to restart.)");
            return null;
        }

        if (clickCooldown > 0) {
            clickCooldown--;
            if (clickCooldown > 0) {
                // do not move while an interaction is being processed
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (!clickQueue.isEmpty()) {
            performNextClick();
            if (!clickQueue.isEmpty()) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // A full inventory means every apricorn from here on would be left lying on the ground, so
        // break off and empty out before carrying on with the sweep.
        if (depositEnabled && !depositUnavailable && !patrolDone
                && depositPhase == DepositPhase.IDLE && emptyInventorySlots() == 0
                && ApricornBlocks.hasApricorns(ctx.player().getInventory())) {
            midRunDeposit = true;
            depositPhase = DepositPhase.SCAN;
            depositChestPos = null;
            depositTicks = 0;
            currentGoalStand = null;
            logDirect("Inventory full - going to empty it into a container.");
        }

        if (depositPhase != DepositPhase.IDLE && depositPhase != DepositPhase.DONE) {
            return tickDeposit(calcFailed);
        }
        if (pickingUp) {
            return tickPickupPass(calcFailed);
        }
        return tickPatrol(calcFailed);
    }

    // ------------------------------------------------------------------ deposit

    /**
     * Walks the farm's paths and harvests everything in reach from each stop.
     *
     * <p>The farm this is built for is a grid of 3x3x3 bushes with 1-wide paths between them, so
     * one path stand touches up to four bushes and every apricorn is in reach of some path. Working
     * bush by bush therefore walks the same paths over and over; sweeping the paths instead visits
     * each stand once, clicks everything the stand can see - whichever bush it belongs to - and
     * collects the drops before moving on.
     */
    private PathingCommand tickPatrol(boolean calcFailedIn) {
        boolean calcFailed = calcFailedIn;
        while (!patrolDone) {
            if (localPickup) {
                PathingCommand cmd = tickLocalPickup(calcFailed);
                calcFailed = false;
                if (cmd != null) {
                    return cmd;
                }
                localPickup = false;
                nextStand();
                continue;
            }

            if (standIndex >= harvestStands.size()) {
                // One re-scan before finishing: catches apricorns that ripened during the run.
                if (!rescanned && rescanForMissedApricorns()) {
                    continue;
                }
                patrolDone = true;
                continue;
            }

            BlockPos stand = harvestStands.get(standIndex);
            if (unreachableStands.contains(stand) || !standHasWork(stand)) {
                nextStand();
                continue;
            }

            if (towerPhase != TowerPhase.IDLE) {
                PathingCommand cmd = tickTowering();
                if (cmd != null) {
                    return cmd;
                }
            }

            trackGoal(stand);

            if (arrivedAt(stand)) {
                fillClickQueueAtStop();
                if (!clickQueue.isEmpty()) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                if (harvestTops && towerPhase == TowerPhase.IDLE && !towerSkippedForRun
                        && hasHighApricorns(stand)) {
                    towerPhase = TowerPhase.EQUIP_DIRT;
                    towerTicks = 0;
                    PathingCommand cmd = tickTowering();
                    if (cmd != null) {
                        return cmd;
                    }
                }
                // Everything this stand can reach is picked: collect what fell, then move on.
                localPickup = true;
                localPickupDelay = DROP_SETTLE_TICKS;
                localPickupX = stand.getX();
                localPickupZ = stand.getZ();
                skippedItems.clear();
                harvestedAtThisStop = false;
                continue;
            }

            if (calcFailed || goalUnreachable(stand)) {
                calcFailed = false;
                logDirect("Cannot reach stand " + stand + " - skipping it.");
                unreachableStands.add(stand);
                nextStand();
                continue;
            }
            if (stuck(stand)) {
                logDirect("Stalled near stand " + stand + ", skipping ahead.");
                unreachableStands.add(stand);
                nextStand();
                continue;
            }
            return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return beginPickupPass();
    }

    /** Moves to the next stand of the sweep. */
    private void nextStand() {
        standIndex++;
        currentGoalStand = null;
    }

    /** True when the stand can still reach an apricorn worth clicking. */
    private boolean standHasWork(BlockPos stand) {
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
        int r = (int) Math.ceil(Math.sqrt(reachSq()));
        int minX = Math.max(selMin.getX(), stand.getX() - r);
        int maxX = Math.min(selMax.getX(), stand.getX() + r);
        int minZ = Math.max(selMin.getZ(), stand.getZ() - r);
        int maxZ = Math.min(selMax.getZ(), stand.getZ() + r);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = selMin.getY(); y <= selMax.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (clickAttempts.getOrDefault(pos, 0) >= MAX_CLICK_ATTEMPTS) {
                        continue;
                    }
                    BlockState state = ctx.world().getBlockState(pos);
                    if (isWanted(state) && eye.distanceToSqr(nearestPointOnBlock(eye, pos)) <= reachSq()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Orders the path stands into a sweep: row by row, alternating direction, so the bot walks the
     * farm like a lawnmower instead of criss-crossing it.
     */
    private void buildSweep() {
        harvestStands.clear();
        List<BlockPos> stands = new ArrayList<>(pathStands);
        stands.sort((a, b) -> {
            if (a.getZ() != b.getZ()) {
                return Integer.compare(a.getZ(), b.getZ());
            }
            boolean reverse = ((a.getZ() - selMin.getZ()) & 1) == 1;
            return reverse ? Integer.compare(b.getX(), a.getX()) : Integer.compare(a.getX(), b.getX());
        });
        for (BlockPos stand : stands) {
            if (standHasWork(stand)) {
                harvestStands.add(stand);
            }
        }
        standIndex = 0;
    }

    /**
     * Re-plans the sweep once every stand has been visited, so apricorns that ripened during the
     * run - a long harvest on a big farm easily outlasts a growth stage - are picked instead of
     * left standing.
     *
     * @return true when there is more to do and the sweep should carry on
     */
    private boolean rescanForMissedApricorns() {
        rescanned = true;
        currentGoalStand = null;
        buildSweep();
        if (harvestStands.isEmpty()) {
            return false;
        }
        logDirect("Re-scan found " + harvestStands.size() + " more stop(s) with ripe apricorns.");
        return true;
    }

    private PathingCommand tickTowering() {
        if (towerPhase == TowerPhase.IDLE) return null;

        towerTicks++;
        Vec3 p = ctx.player().position();
        
        switch (towerPhase) {
            case EQUIP_DIRT:
                if (towerTicks == 1) {
                    // Check clearance above
                    BlockPos head = ctx.playerFeet().above(2);
                    if (!ctx.world().getBlockState(head).getCollisionShape(ctx.world(), head).isEmpty()) {
                        logDirect("Low canopy at " + head + ", skipping tower here.");
                        towerPhase = TowerPhase.IDLE;
                        return null;
                    }
                    int slot = findDirtSlot();
                    if (slot == -1) {
                        if (!towerSkippedForRun) {
                            logDirect("No dirt in hotbar/inventory! Skipping all towering for this run.");
                            towerSkippedForRun = true;
                        }
                        towerPhase = TowerPhase.IDLE;
                        return null;
                    }
                    previousSlot = ctx.player().getInventory().selected;
                    ctx.player().getInventory().selected = slot;
                    ctx.playerController().syncHeldItem();
                    towerPhase = TowerPhase.LOOK_DOWN;
                    towerTicks = 0;
                }
                break;
                
            case LOOK_DOWN:
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), 90.0f), true);
                if (towerTicks > 2) {
                    towerPhase = TowerPhase.JUMP_PLACE;
                    towerTicks = 0;
                }
                break;
                
            case JUMP_PLACE:
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), 90.0f), true);
                if (towerTicks == 1) {
                    ctx.player().jumpFromGround();
                } else if (towerTicks > 1 && p.y > ctx.playerFeet().getY() + 0.1) {
                    BlockPos placePos = ctx.playerFeet();
                    towerBlockPos = placePos;
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false);
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                    towerPhase = TowerPhase.WAIT_LAND;
                    towerTicks = 0;
                }
                break;
                
            case WAIT_LAND:
                if (ctx.player().onGround() && ctx.playerFeet().getY() == towerBlockPos.getY() + 1) {
                    towerPhase = TowerPhase.HARVEST;
                    towerTicks = 0;
                }
                break;
                
            case HARVEST:
                if (towerTicks == 1) {
                    fillClickQueueAtStop(); // Will use new elevated eye height
                }
                if (clickQueue.isEmpty()) {
                    towerPhase = TowerPhase.LOOK_BREAK;
                    towerTicks = 0;
                }
                break;
                
            case LOOK_BREAK:
                if (towerTicks == 1) {
                    if (previousSlot != -1) {
                        ctx.player().getInventory().selected = previousSlot;
                        ctx.playerController().syncHeldItem();
                    }
                }
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), 90.0f), true);
                if (towerTicks > 2) {
                    towerPhase = TowerPhase.BREAKING;
                    towerTicks = 0;
                }
                break;
                
            case BREAKING:
                baritone.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(), 90.0f), true);
                if (ctx.world().getBlockState(towerBlockPos).isAir()) {
                    ctx.playerController().resetBlockRemoving();
                    towerPhase = TowerPhase.WAIT_FALL;
                    towerTicks = 0;
                } else {
                    if (towerTicks == 1) {
                        ctx.playerController().clickBlock(towerBlockPos, Direction.UP);
                    } else {
                        ctx.playerController().onPlayerDamageBlock(towerBlockPos, Direction.UP);
                    }
                }
                break;
                
            case WAIT_FALL:
                if (ctx.player().onGround()) {
                    towerPhase = TowerPhase.IDLE;
                    towerTicks = 0;
                }
                break;
        }
        
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private int findDirtSlot() {
        net.minecraft.world.entity.player.Inventory inv = ctx.player().getInventory();
        // Check hotbar first (slots 0-8)
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (id.equals("minecraft:dirt") || id.equals("minecraft:coarse_dirt") || id.equals("minecraft:rooted_dirt")) {
                    return i;
                }
            }
        }
        return -1; // No dirt in hotbar
    }

    private boolean hasHighApricorns(BlockPos stand) {
        Vec3 elevatedEye = new Vec3(stand.getX() + 0.5, stand.getY() + 1 + 1.62, stand.getZ() + 0.5);
        int r = (int) Math.ceil(Math.sqrt(reachSq()));
        int minX = Math.max(selMin.getX(), stand.getX() - r);
        int maxX = Math.min(selMax.getX(), stand.getX() + r);
        int minZ = Math.max(selMin.getZ(), stand.getZ() - r);
        int maxZ = Math.min(selMax.getZ(), stand.getZ() + r);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = stand.getY() + 1; y <= selMax.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (isWanted(state)
                            && elevatedEye.distanceToSqr(nearestPointOnBlock(elevatedEye, pos)) <= reachSq()
                            && clickAttempts.getOrDefault(pos, 0) < MAX_CLICK_ATTEMPTS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Scan everything the player could possibly click from the current stop. The radius is the
     * block reach, not a fixed 3x3: with a 1-wide path between trees, tree columns two or more
     * blocks off the path were never even looked at, which left whole trees unpicked.
     */
    private void fillClickQueueAtStop() {
        if (currentGoalStand == null) return;
        int r = (int) Math.ceil(Math.sqrt(reachSq()));
        int minX = Math.max(selMin.getX(), currentGoalStand.getX() - r);
        int maxX = Math.min(selMax.getX(), currentGoalStand.getX() + r);
        int minZ = Math.max(selMin.getZ(), currentGoalStand.getZ() - r);
        int maxZ = Math.min(selMax.getZ(), currentGoalStand.getZ() + r);
        Vec3 eye = ctx.playerHead();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = selMin.getY(); y <= selMax.getY(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (isWanted(state)
                            && eye.distanceToSqr(nearestPointOnBlock(eye, pos)) <= reachSq()
                            && clickAttempts.getOrDefault(pos, 0) < MAX_CLICK_ATTEMPTS) {
                        clickQueue.add(pos);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ climb pass

    /** {@code #apricorn tops true|false}: whether the climb pass runs after the patrol. */
    public void setHarvestTops(boolean value) {
        harvestTops = value;
        AddonSettings.setHarvestTops(value);
        logDirect("Apricorn tops = " + value
                + (value ? " (will try to reach high apricorns from the paths after the patrol)."
                         : " (high apricorns that the patrol cannot reach are left standing)."));
    }

    public boolean isHarvestTops() {
        return AddonSettings.isHarvestTops();
    }

    // ------------------------------------------------------------------ pickup pass

    /**
     * Final pass: walk over every dropped apricorn lying inside the farm so it is picked up.
     * Vanilla pickup does the collecting, so the bot only has to stand on the item's column -
     * no clicking involved. Items in columns with no walkable path stand (under a tree) and items
     * that stay on the ground after {@link #PICKUP_WAIT_TICKS} (full inventory) are skipped.
     */
    private PathingCommand beginPickupPass() {
        pickingUp = true;
        skippedItems.clear();
        pickupWait = 0;
        currentGoalStand = null;
        return tickPickupPass(false);
    }

    private PathingCommand tickPickupPass(boolean calcFailedIn) {
        boolean calcFailed = calcFailedIn;
        while (true) {
            ItemEntity item = nearestDroppedApricorn();
            if (item == null) {
                // No more drops. If deposit is enabled and the player has apricorns in
                // inventory, transition to the deposit phase.
                if (depositEnabled && ApricornBlocks.hasApricorns(ctx.player().getInventory())) {
                    depositPhase = DepositPhase.SCAN;
                    depositChestPos = null;
                    depositTicks = 0;
                    logDirect("Harvest complete. Depositing apricorns into a nearby container...");
                    return tickDeposit(calcFailed);
                }
                finish("Apricorn harvesting complete.");
                return null;
            }
            PathingCommand cmd = walkToPickUp(item, calcFailed);
            calcFailed = false;
            if (cmd != null) {
                return cmd;
            }
        }
    }

    /**
     * Collects the apricorns that fell around the stop that was just picked. Returns null when
     * there is nothing left within {@link #LOCAL_PICKUP_RADIUS} of it, which tells the patrol to
     * move on to the next column.
     */
    private PathingCommand tickLocalPickup(boolean calcFailedIn) {
        if (localPickupDelay > 0) {
            localPickupDelay--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        boolean calcFailed = calcFailedIn;
        while (true) {
            ItemEntity item = nearestDroppedApricorn(localPickupX, localPickupZ, LOCAL_PICKUP_RADIUS);
            if (item == null) {
                return null;
            }
            PathingCommand cmd = walkToPickUp(item, calcFailed);
            calcFailed = false;
            if (cmd != null) {
                return cmd;
            }
        }
    }

    /**
     * One step of collecting a single dropped apricorn: returns the pathing command to keep
     * walking to it (or to stand on it while vanilla pickup happens), or null when this item is
     * done with - collected, or given up on and added to {@link #skippedItems}.
     */
    private PathingCommand walkToPickUp(ItemEntity item, boolean calcFailed) {
        if (item.getId() != pickupItemId) {
            pickupItemId = item.getId();
            pickupWait = 0;
        }
        BlockPos itemPos = item.blockPosition();
        BlockPos stand = pathStand(itemPos.getX(), itemPos.getZ());
        if (stand == null) {
            // Lying in a tree column the bot may not walk into.
            skippedItems.add(item.getId());
            return null;
        }
        trackGoal(stand);
        if (arrivedAt(stand)) {
            // Standing on it: vanilla pickup takes over. If the item is still there after a
            // moment, it cannot be collected (typically a full inventory) - leave it.
            if (++pickupWait > PICKUP_WAIT_TICKS) {
                logDirect("Could not pick up the apricorn at " + itemPos + " (inventory full?), skipping.");
                skippedItems.add(item.getId());
                pickupWait = 0;
                return null;
            }
            // If the item is far above the stand (e.g. on top of leaves in a tree column),
            // jumping will never reach it — skip it immediately.
            if (item.getY() > stand.getY() + 2.5) {
                logDebug("Item at " + itemPos + " is too high above stand " + stand + ", skipping.");
                skippedItems.add(item.getId());
                pickupWait = 0;
                return null;
            }
            // Keep jumping under items that are above ground (e.g. on top of leaves or slabs),
            // bringing the player collision box closer to the item for vanilla pickup.
            if (item.getY() > ctx.player().getY() + 0.3 && ctx.player().onGround()) {
                ctx.player().jumpFromGround();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (calcFailed || goalUnreachable(stand) || stuck(stand)) {
            logDirect("Cannot reach the apricorn at " + itemPos + ", skipping.");
            skippedItems.add(item.getId());
            return null;
        }
        return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /** The closest dropped apricorn inside the selection that has not been given up on. */
    private ItemEntity nearestDroppedApricorn() {
        return nearestDroppedApricorn(0, 0, -1.0);
    }

    /**
     * The closest dropped apricorn that has not been given up on. With a positive radius only
     * drops within that horizontal distance of column (cx, cz) are considered - "around this
     * bush"; with a negative radius the whole selection is searched.
     */
    private ItemEntity nearestDroppedApricorn(int cx, int cz, double radius) {
        AABB box = new AABB(
                selMin.getX(), selMin.getY(), selMin.getZ(),
                selMax.getX() + 1.0, selMax.getY() + 1.0, selMax.getZ() + 1.0)
                .inflate(1.0);
        Vec3 p = ctx.player().position();
        ItemEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ItemEntity e : ctx.world().getEntitiesOfClass(ItemEntity.class, box)) {
            if (!e.isAlive() || skippedItems.contains(e.getId())
                    || !ApricornBlocks.isApricornItem(e.getItem())) {
                continue;
            }
            if (radius > 0) {
                double dx = e.getX() - (cx + 0.5);
                double dz = e.getZ() - (cz + 0.5);
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
            }
            double d = e.position().distanceToSqr(p);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = e;
            }
        }
        return best;
    }

    /** Mature apricorns still standing anywhere in the selection. */
    private int countMature() {
        int n = 0;
        for (int x = selMin.getX(); x <= selMax.getX(); x++) {
            for (int z = selMin.getZ(); z <= selMax.getZ(); z++) {
                for (int y = selMin.getY(); y <= selMax.getY(); y++) {
                    if (isWanted(ctx.world().getBlockState(new BlockPos(x, y, z)))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ deposit

    /** True if the player has set deposit mode via {@code #apricorn deposit true}. */
    public void setDepositEnabled(boolean value) {
        depositEnabled = value;
        AddonSettings.setHarvestDeposit(value);
        logDirect("Apricorn auto-deposit = " + value
                + (value ? " (will look for a nearby container after harvesting)."
                         : " (will not deposit - apricorns stay in inventory)."));
    }

    public boolean isDepositEnabled() {
        return AddonSettings.isHarvestDeposit();
    }

    /**
     * Deposit phase state machine. After the pickup pass finds no more drops on the ground,
     * this phase scans for a container with empty slots, walks to it, opens it, shift-clicks
     * all apricorns into the container, then closes the screen.
     */
    private PathingCommand tickDeposit(boolean calcFailed) {
        switch (depositPhase) {
            case SCAN: {
                // Find every container within the configured range of the selection
                int scanRange = AddonSettings.getChestRadius();
                int cx = (selMin.getX() + selMax.getX()) / 2;
                int cz = (selMin.getZ() + selMax.getZ()) / 2;
                int minX = Math.max(cx - scanRange, Math.min(selMin.getX(), ctx.player().blockPosition().getX()) - scanRange);
                int maxX = Math.min(cx + scanRange, Math.max(selMax.getX(), ctx.player().blockPosition().getX()) + scanRange);
                int minZ = Math.max(cz - scanRange, Math.min(selMin.getZ(), ctx.player().blockPosition().getZ()) - scanRange);
                int maxZ = Math.min(cz + scanRange, Math.max(selMax.getZ(), ctx.player().blockPosition().getZ()) + scanRange);

                BlockPos bestPos = null;
                int bestEmptySlots = 0;
                double bestDistSq = Double.MAX_VALUE;
                Vec3 playerPos = ctx.player().position();

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = Math.max(-64, Math.min(selMin.getY(), ctx.player().blockPosition().getY()) - 8);
                                y <= Math.max(selMax.getY(), ctx.player().blockPosition().getY()) + 8; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = ctx.world().getBlockState(pos);
                            if (!ApricornBlocks.isContainer(state) || triedContainers.contains(pos)) {
                                continue;
                            }
                            int emptySlots = countEmptyContainerSlots(pos);
                            if (emptySlots <= 0) {
                                continue;
                            }
                            double d = playerPos.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (d < bestDistSq) {
                                bestDistSq = d;
                                bestPos = pos;
                                bestEmptySlots = emptySlots;
                            }
                        }
                    }
                }

                if (bestPos == null) {
                    logDirect("No container with free space within " + scanRange + " blocks"
                            + (triedContainers.isEmpty() ? "" : " (" + triedContainers.size() + " full)")
                            + " - raise it with #apricorn chestradius <n>.");
                    return endDeposit("Apricorn harvesting complete (no deposit target).", true);
                }

                depositChestPos = bestPos;
                depositNoProgress = 0;
                depositLastCount = -1;
                logDirect("Walking to container at " + bestPos + " (" + bestEmptySlots + " empty slots)...");
                depositPhase = DepositPhase.WALK;
                depositTicks = 0;
                // fall through to WALK
            }
            case WALK: {
                boolean arrived = currentGoalStand != null && arrivedAt(currentGoalStand);
                BlockPos ppos = ctx.player().blockPosition();
                if (arrived || (Math.abs(ppos.getX() - depositChestPos.getX()) <= 3
                        && Math.abs(ppos.getY() - depositChestPos.getY()) <= 2
                        && Math.abs(ppos.getZ() - depositChestPos.getZ()) <= 3
                        && !baritone.getPathingBehavior().isPathing()
                        && !baritone.getPathingBehavior().getInProgress().isPresent())) {
                    depositPhase = DepositPhase.OPEN;
                    depositTicks = 0;
                    logDirect("Opening container...");
                    return tickDeposit(false); // recurse into OPEN
                }
                BlockPos stand = pathStand(depositChestPos.getX(), depositChestPos.getZ());
                if (stand == null) {
                    // Cannot reach the chest's column at all — try the player's own feet
                    stand = ctx.playerFeet();
                }
                trackGoal(stand);
                if (goalUnreachable(stand) || stuck(stand)) {
                    logDirect("Cannot reach container at " + depositChestPos + ".");
                    return endDeposit("Apricorn harvesting complete (could not reach container).", true);
                }
                return new PathingCommand(new GoalBlock(stand), PathingCommandType.SET_GOAL_AND_PATH);
            }
            case OPEN: {
                // Face the container and right-click it
                Vec3 eye = ctx.playerHead();
                Vec3 clickPoint = nearestPointOnBlock(eye, depositChestPos);
                Direction face = faceToward(eye, depositChestPos);
                BlockHitResult hit = new BlockHitResult(clickPoint, face, depositChestPos, false);
                baritone.getLookBehavior().updateTarget(rotationTo(eye, clickPoint), true);
                if (depositTicks == 1) {
                    try {
                        InteractionResult result = ctx.playerController()
                                .processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                        if (result != InteractionResult.PASS) {
                            logDebug("Opened container at " + depositChestPos + " -> " + result);
                        }
                    } catch (Throwable t) {
                        logDebug("Opening container failed: " + t);
                    }
                }
                depositTicks++;
                if (Minecraft.getInstance().screen != null) {
                    depositPhase = DepositPhase.DEPOSITING;
                    depositTicks = 0;
                    logDirect("Depositing apricorns...");
                    return tickDeposit(false);
                }
                if (depositTicks > 20) {
                    logDirect("Container at " + depositChestPos + " did not open.");
                    return endDeposit("Apricorn harvesting complete (could not open container).", true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case DEPOSITING: {
                AbstractContainerMenu menu = ctx.player().containerMenu;
                if (menu == null || menu.slots.isEmpty()) {
                    logDirect("Container menu not available, skipping deposit.");
                    depositPhase = DepositPhase.CLOSE;
                    depositTicks = 0;
                    break;
                }
                // The player inventory is the last 36 slots (9 hotbar + 27 main) of the container menu.
                // Slot index 0 = container's first slot. Player inventory starts at (containerSlots).
                int playerInvStart = menu.slots.size() - 36;
                if (playerInvStart < 0) {
                    playerInvStart = 0;
                }

                // A shift-click into a full container silently does nothing, so progress is judged
                // by the apricorns actually leaving the inventory - not by having clicked.
                int carrying = ApricornBlocks.countApricorns(ctx.player().getInventory());
                if (carrying < depositLastCount) {
                    depositNoProgress = 0;
                } else if (depositLastCount >= 0) {
                    depositNoProgress++;
                }
                depositLastCount = carrying;

                if (depositNoProgress > DEPOSIT_STALL_CLICKS) {
                    logDirect("Container at " + depositChestPos + " is full - looking for another.");
                    triedContainers.add(depositChestPos);
                    closeScreenOnly();
                    depositPhase = DepositPhase.SCAN;
                    depositTicks = 0;
                    depositNoProgress = 0;
                    depositLastCount = -1;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                for (int i = playerInvStart; i < menu.slots.size(); i++) {
                    Slot slot = menu.getSlot(i);
                    if (!slot.hasItem()) continue;
                    ItemStack stack = slot.getItem();
                    if (!ApricornBlocks.isApricornItem(stack)) continue;

                    Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    // Wait for the server to process before the next click
                    depositTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // No apricorns left in inventory — close the screen
                depositPhase = DepositPhase.CLOSE;
                depositTicks = 0;
                // fall through
            }
            case CLOSE: {
                Minecraft.getInstance().screen = null; // force-close on client
                ctx.player().closeContainer();
                String msg = "Apricorns deposited.";
                // Still full after a deposit means the container took nothing useful; stop trying
                // mid-run rather than shuttling back and forth for the rest of the harvest.
                boolean stillFull = emptyInventorySlots() == 0;
                if (ApricornBlocks.hasApricorns(ctx.player().getInventory()) && stillFull) {
                    msg = "Could not deposit all apricorns (container full?).";
                }
                return endDeposit("Apricorn harvesting complete. " + msg, stillFull);
            }
            default:
                break;
        }
        // Whatever happened, the run is still active here, so hold rather than returning nothing.
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Ends a deposit trip. A mid-run trip hands control back to the sweep so the harvest carries
     * on where it left off; the final one finishes the run.
     *
     * @param finalMessage what to say if this was the run's last act
     * @param failed       true when the trip hit something that will keep failing, so no further
     *                     mid-run trips are attempted
     */
    private PathingCommand endDeposit(String finalMessage, boolean failed) {
        depositPhase = DepositPhase.IDLE;
        depositChestPos = null;
        depositTicks = 0;
        depositNoProgress = 0;
        depositLastCount = -1;
        currentGoalStand = null;
        if (failed) {
            depositUnavailable = true;
        }
        if (midRunDeposit) {
            midRunDeposit = false;
            logDirect(failed
                    ? "Carrying on without depositing - drops that do not fit will be left."
                    : "Emptied out, back to harvesting.");
            // NOT null: the run is still active, and Baritone throws IllegalStateException when an
            // active process returns no command. Hold still for this tick; the sweep resumes on the
            // next one.
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        depositPhase = DepositPhase.DONE;
        finish(finalMessage);
        return null;
    }

    /** Closes the container screen without ending the deposit, for moving to the next chest. */
    private void closeScreenOnly() {
        Minecraft.getInstance().screen = null;
        ctx.player().closeContainer();
    }

    /** Free slots in the player's main inventory and hotbar. */
    private int emptyInventorySlots() {
        net.minecraft.world.entity.player.Inventory inv = ctx.player().getInventory();
        int empty = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.getItem(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /**
     * Counts empty slots in a container block entity at the given position.
     * Returns 0 if the block is not a container or has no block entity.
     */
    private int countEmptyContainerSlots(BlockPos pos) {
        BlockEntity be = ctx.world().getBlockEntity(pos);
        if (be instanceof ChestBlockEntity chest) {
            int empty = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).isEmpty()) empty++;
            }
            return empty;
        }
        if (be instanceof BarrelBlockEntity barrel) {
            int empty = 0;
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                if (barrel.getItem(i).isEmpty()) empty++;
            }
            return empty;
        }
        if (be instanceof ShulkerBoxBlockEntity shulker) {
            int empty = 0;
            for (int i = 0; i < shulker.getContainerSize(); i++) {
                if (shulker.getItem(i).isEmpty()) empty++;
            }
            return empty;
        }
        return 0;
    }

    // ------------------------------------------------------------------ path stands

    /**
     * Feet position of the highest walkable, non-tree surface in column (x, z), or null when the
     * column has none. The scan walks down from just above the selection so the bot adapts to
     * whatever surface height the terrain actually has (raised beds, trenches, channels...), but
     * it stops {@link #STAND_SEARCH_DEPTH} blocks below the selection: a column that is open all
     * the way down (a hole, a ravine, a cave under the farm) must not produce a "stand" far below
     * the farm, or the bot walks off into the depths to reach it. Tree blocks are never a stand:
     * the bot stays on the farm's paths and never walks on, into or through apricorn trees.
     */
    private BlockPos pathStand(int x, int z) {
        int top = selMax.getY() + 2;
        int bottom = selMin.getY() - STAND_SEARCH_DEPTH;
        for (int y = top; y >= bottom; y--) {
            BlockPos belowPos = new BlockPos(x, y, z);
            BlockState below = ctx.world().getBlockState(belowPos);
            if (ApricornBlocks.isTreeBlock(below)) {
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
     * The path stand closest to the given target from which it can be harvested, or null. Stands
     * Baritone could not reach are skipped. Used by the pickup and deposit passes; the harvest
     * sweep walks {@link #harvestStands} instead.
     */
    private BlockPos bestStandFor(BlockPos target) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos s : pathStands) {
            if (unreachableStands.contains(s)) continue;
            if (Math.abs(s.getX() - target.getX()) > 4 || Math.abs(s.getZ() - target.getZ()) > 4) {
                continue;
            }
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + 1.62, s.getZ() + 0.5);
            double d = eye.distanceToSqr(nearestPointOnBlock(eye, target));
            if (d <= reachSq() && d < bestDistSq) {
                bestDistSq = d;
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ interactions

    private void performNextClick() {
        BlockPos target = clickQueue.iterator().next();
        BlockState state = ctx.world().getBlockState(target);
        if (!ApricornBlocks.isMature(state)) {
            // previous click already harvested it (or it changed), no need to click again
            clickQueue.remove(target);
            clickAttempts.remove(target);
            return;
        }
        Vec3 eye = ctx.playerHead();
        Vec3 clickPoint = nearestPointOnBlock(eye, target);
        if (eye.distanceToSqr(clickPoint) > reachSq()) {
            // out of reach from here: leave it for the climb pass
            clickQueue.remove(target);
            return;
        }
        long now = ctx.world().getGameTime();
        Long lastClick = lastClickTick.get(target);
        if (lastClick != null && now - lastClick < RESCAN_DELAY_TICKS) {
            // wait for the block update of a previous click before re-checking
            clickQueue.remove(target);
            clickQueue.add(target);
            return;
        }
        int attempts = clickAttempts.merge(target, 1, Integer::sum);
        if (attempts > MAX_CLICK_ATTEMPTS) {
            clickQueue.remove(target);
            // Keep the clickAttempts entry so re-scans at this stop will not re-add the block.
            logDirect("Could not harvest " + target + " after " + MAX_CLICK_ATTEMPTS + " attempts, skipping.");
            return;
        }
        Direction face = faceToward(eye, target);
        BlockHitResult hit = new BlockHitResult(clickPoint, face, target, false);
        baritone.getLookBehavior().updateTarget(rotationTo(eye, clickPoint), true);
        try {
            InteractionResult result = ctx.playerController()
                    .processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
            if (result != InteractionResult.PASS) {
                logDebug("Clicked " + target + " -> " + result);
                harvestedAtThisStop = true;
            }
        } catch (Throwable t) {
            logDebug("Click on " + target + " failed: " + t);
        }
        lastClickTick.put(target, now);
        clickCooldown = CLICK_COOLDOWN;
    }

    private double reachSq() {
        double reach = ctx.playerController().getBlockReachDistance() + REACH_MARGIN;
        return reach * reach;
    }

    /** Nearest point of the block's cube to the given point (the click point on its surface). */
    private Vec3 nearestPointOnBlock(Vec3 p, BlockPos pos) {
        double x = clamp(p.x, pos.getX(), pos.getX() + 1.0);
        double y = clamp(p.y, pos.getY(), pos.getY() + 1.0);
        double z = clamp(p.z, pos.getZ(), pos.getZ() + 1.0);
        return new Vec3(x, y, z);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** The face of the block that faces the eye, for a plausible hit vector. */
    private Direction faceToward(Vec3 eye, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double dx = eye.x - cx;
        double dy = eye.y - cy;
        double dz = eye.z - cz;
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

    /**
     * Starts (or continues) tracking the goal for the given stand. The timers are only reset when
     * the stand actually changes - resetting them on every tick, as the old code did by writing
     * goalSetTick before returning the pathing command, made the grace period permanent and
     * disabled the unreachable check entirely.
     */
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

    /** Center of the stand block at feet height, the point movement progress is measured against. */
    private Vec3 standCenter(BlockPos stand) {
        return new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
    }

    /**
     * True once the stop counts as reached: either the feet are exactly on the stand, or Baritone
     * has finished pathing (nothing in progress) and the player ended up within
     * {@link #ARRIVAL_TOLERANCE} of it. Everything harvested from a stop is reach-checked, so
     * accepting a neighbouring block is safe and avoids hanging forever on a stand the pathfinder
     * refuses to end exactly on.
     */
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

    /**
     * True when the run has to give up on the current stand. Three independent signals, because
     * the plain "did not move" check alone let the bot loop forever whenever it kept moving
     * without ever arriving:
     * <ul>
     *   <li>stationary for {@link #STALL_LIMIT} ticks,</li>
     *   <li>no measurable approach to the stand for {@link #NO_PROGRESS_LIMIT} ticks (oscillation),</li>
     *   <li>{@link #GOAL_TIMEOUT_TICKS} spent on this one stand, whatever it was doing.</li>
     * </ul>
     */
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

    /**
     * True when Baritone is clearly not going to reach the given stand, so the column can be
     * skipped without waiting out the full stall limit. Only used after the goal has been set
     * for {@link #UNREACHABLE_GRACE_TICKS}, so an in-flight path swap for the new goal is never
     * mistaken for failure. Two signals are treated as proof:
     * <ul>
     *   <li>Baritone is following a path that ends on a different level than the stand - the
     *       bot cannot break blocks or climb 2-high walls, so a destination on another level
     *       can never reach this goal.</li>
     *   <li>The path search finished without producing any path at all.</li>
     * </ul>
     * A partial path (search timed out while extending) is NOT proof of unreachability: Baritone
     * may still re-path and get there.
     */
    private boolean goalUnreachable(BlockPos stand) {
        if (ctx.world().getGameTime() - goalSetTick < UNREACHABLE_GRACE_TICKS) {
            return false;
        }
        IPathingBehavior pathing = baritone.getPathingBehavior();
        Optional<? extends IPathFinder> search = pathing.getInProgress();
        if (search.isPresent()) {
            // A search is still running: whatever the current segment looks like, the destination
            // may still change. Never call it unreachable while the pathfinder is thinking.
            return search.get().isFinished() && !search.get().bestPathSoFar().isPresent();
        }
        Optional<IPath> path = pathing.getPath();
        if (path.isPresent()) {
            // Only judge the segment once the bot is actually done with it. A segment that ends
            // on another level mid-route is normal (Baritone splits long paths); a *finished*
            // one that ends on another level means it cannot get up/down to the stand.
            if (pathing.isPathing()) {
                return false;
            }
            return Math.abs(path.get().getDest().getY() - stand.getY()) > 1;
        }
        return false;
    }

    /**
     * True when the player has ended up far outside the work area. Every goal this process sets is
     * inside the selection, so being this far away means something went wrong (an impossible stand,
     * a knockback, a teleport, a path around an obstacle that went sideways) - and continuing would
     * just be the bot walking off into nowhere. The run is stopped instead.
     */
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
        pickingUp = false;
        localPickup = false;
        skippedItems.clear();
        patrolDone = true;
        towerPhase = TowerPhase.IDLE;
        clickQueue.clear();
        clickAttempts.clear();
        lastClickTick.clear();
        climbTargets.clear();
        climbStands.clear();
        pathStands.clear();
        BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
        // IMPORTANT: do NOT call baritone.getPathingBehavior().cancelEverything() here.
        // finish() runs inside PathingControlManager.executeProcesses(), which is iterating
        // its 'active' ArrayList; cancelEverything() clears that list and the JDK ArrayList
        // iterator then throws ConcurrentModificationException on the next next().
        // Returning null + inactive is enough: preTick() sees command == null and calls
        // cancelSegmentIfSafe() + secretInternalSetGoal(null), which stops pathing.
        logDirect(message);
    }

    @Override
    public void onLostControl() {
        if (running) {
            running = false;
            paused = false;
            pickingUp = false;
            localPickup = false;
            skippedItems.clear();
            towerPhase = TowerPhase.IDLE;
            clickQueue.clear();
            climbTargets.clear();
            climbStands.clear();
            pathStands.clear();
            BaritoneAPI.getSettings().allowBreak.value = previousAllowBreak;
            logDirect("Apricorn harvesting stopped (another Baritone action took over).");
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
        return "Apricorn Harvester";
    }

    @Override
    public String displayName0() {
        return "Apricorn Harvester";
    }
}
