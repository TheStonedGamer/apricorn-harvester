package com.brianthemint.apricornharvester;

import baritone.api.utils.Helper;
import com.brianthemint.apricornharvester.pokeball.CraftPlan;
import com.brianthemint.apricornharvester.pokeball.PokeballConfig;
import com.brianthemint.apricornharvester.pokeball.PokeballRecipes;
import com.brianthemint.apricornharvester.ui.FlatUI;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The addon's single window: every job and every setting behind one key, split into tabs.
 *
 * <p>This replaces the separate planting, Poke Ball, settings and help screens - and the three key
 * bindings that opened them. Each tab owns its controls and, where there is a job to run, its own
 * Run/Cancel pair; the sidebar switches between them and remembers the last tab, so reopening the
 * window comes back where you left it.
 */
public class ApricornGui extends Screen {

    private static final int PANEL_W = 460;
    private static final int PANEL_H = 300;
    private static final int SIDEBAR_W = 108;
    private static final int TAB_H = 20;
    private static final int ROW_H = 22;
    private static final int ROWS_VISIBLE = 7;
    private static final int FARM_ROWS_VISIBLE = 6;

    /** The tabs, in sidebar order. */
    public enum Tab {
        FARMS("Farms"),
        HARVEST("Harvest"),
        PLANT("Planting"),
        ROWS("Row colours"),
        BONEMEAL("Bone meal"),
        POKEBALL("Poke Balls"),
        MINE("Mine & hunt"),
        AREAS("Task areas"),
        HELP("Help");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** Remembered between openings, so the window comes back on the tab you were using. */
    private static Tab lastTab = Tab.FARMS;
    /**
     * The live context, so the commands that used to open their own screen can open this window
     * without each of them having to be handed every job.
     */
    private static AddonContext shared;

    private final AddonContext context;

    private final List<FlatUI.Widget> widgets = new ArrayList<>();
    private final List<FlatUI.Dropdown<?>> dropdowns = new ArrayList<>();
    private final List<EditBox> boxes = new ArrayList<>();

    // per-tab state that belongs to the window rather than to a saved setting
    private int rowScroll;
    private int farmScroll;
    private int oreIndex;
    private int oreAmount = 32;
    private int huntHops = 10;
    private int helpTopic;
    private CraftPlan pokeballPlan;

    private final List<String[]> helpTopics = List.of(
            ApricornHelpCommand.WORKFLOW, ApricornHelpCommand.FARMS, ApricornHelpCommand.HARVEST,
            ApricornHelpCommand.PLANT, ApricornHelpCommand.BONEMEAL, ApricornHelpCommand.POKEBALL,
            ApricornHelpCommand.ORE, ApricornHelpCommand.HUNT, ApricornHelpCommand.AREAS,
            ApricornHelpCommand.KEYS);
    private final List<String> helpTopicNames = List.of(
            "Workflow", "Farms", "Harvest", "Planting", "Bone meal", "Poke Balls",
            "Ore", "Hunt", "Task areas", "Keys");

    public ApricornGui(AddonContext context) {
        super(Component.literal("Apricorn Harvester"));
        this.context = context;
    }

    /** Called once at startup, so {@link #open} works from anywhere. */
    public static void setContext(AddonContext context) {
        shared = context;
    }

    /** Opens the window on a particular tab, for the commands that used to have their own screen. */
    public static void open(Tab tab) {
        if (shared == null) {
            Helper.HELPER.logDirect("The addon is not hooked into Baritone yet.");
            return;
        }
        lastTab = tab;
        net.minecraft.client.Minecraft.getInstance().setScreen(new ApricornGui(shared));
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    private int contentX() {
        return left() + SIDEBAR_W + 14;
    }

    private int contentW() {
        return PANEL_W - SIDEBAR_W - 28;
    }

    private int contentY() {
        return top() + 42;
    }

    private int buttonRowY() {
        return top() + PANEL_H - 28;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        widgets.clear();
        dropdowns.clear();
        boxes.clear();
        this.clearWidgets();

        int x = contentX();
        int y = contentY();
        int w = contentW();

        switch (lastTab) {
            case FARMS -> initFarms(x, y, w);
            case HARVEST -> initHarvest(x, y, w);
            case PLANT -> initPlant(x, y, w);
            case ROWS -> initRows(x, y, w);
            case BONEMEAL -> initBonemeal(x, y, w);
            case POKEBALL -> initPokeball(x, y, w);
            case MINE -> initMineHunt(x, y, w);
            case AREAS -> initAreas(x, y, w);
            case HELP -> initHelp(x, y, w);
        }

        widgets.add(new FlatUI.Button(left() + PANEL_W - 76, buttonRowY(), 62, 20,
                () -> "Close", this::onClose, false));
    }

    // -- farms

    private void initFarms(int x, int y, int w) {
        // The farms themselves, one clickable row each: the list is the point of this tab, so it
        // is shown outright rather than hidden behind a dropdown.
        List<String> names = FarmMap.names();
        int maxScroll = Math.max(0, names.size() - FARM_ROWS_VISIBLE);
        farmScroll = Math.max(0, Math.min(farmScroll, maxScroll));
        int shown = Math.min(FARM_ROWS_VISIBLE, names.size());
        String selected = FarmSelection.currentName();

        for (int i = 0; i < shown; i++) {
            String name = names.get(farmScroll + i);
            FarmMap farm = FarmMap.load(name);
            boolean isSelected = name.equalsIgnoreCase(selected);
            String label = farm == null || !farm.isMapped()
                    ? name + "  (not mapped)"
                    : name + "   " + farm.stands.size() + " stands, " + farm.trees.size() + " trees";
            widgets.add(new FlatUI.Button(x, y + i * 22, w - (maxScroll > 0 ? 26 : 0), 20,
                    () -> label,
                    () -> {
                        FarmSelection.select(name);
                        FarmSelection.applyToBaritone(context.baritone());
                        Helper.HELPER.logDirect("Working on farm '" + name + "'.");
                        rebuildWidgets();
                    }, isSelected));
        }
        if (maxScroll > 0) {
            widgets.add(new FlatUI.Button(x + w - 22, y, 22, 20, () -> "^", () -> {
                farmScroll = Math.max(0, farmScroll - FARM_ROWS_VISIBLE);
                rebuildWidgets();
            }, false));
            widgets.add(new FlatUI.Button(x + w - 22, y + (shown - 1) * 22, 22, 20, () -> "v", () -> {
                farmScroll = Math.min(maxScroll, farmScroll + FARM_ROWS_VISIBLE);
                rebuildWidgets();
            }, false));
        }

        int actionY = y + Math.max(shown, 1) * 22 + 10;
        widgets.add(new FlatUI.Button(x, actionY, 120, 20,
                () -> mapperRunning() ? "Mapping..." : "Map selection", this::startMapping, true));
        widgets.add(new FlatUI.Button(x + 128, actionY, 90, 20, () -> "Re-map", () -> {
            FarmMap farm = FarmSelection.current();
            if (farm != null && !mapperRunning()) {
                onClose();
                context.mapper().start(farm.name, farm.min, farm.max);
            }
        }, false));
        widgets.add(new FlatUI.Button(x + 226, actionY, 64, 20, () -> "Cancel", () -> {
            if (mapperRunning()) {
                context.mapper().stop();
            }
        }, false));
    }

    private boolean mapperRunning() {
        return context.mapper() != null && context.mapper().isRunning();
    }

    private void startMapping() {
        if (context.mapper() == null || mapperRunning()) {
            return;
        }
        var sel = context.baritone().getSelectionManager().getLastSelection();
        if (sel == null) {
            Helper.HELPER.logDirect("No selection - mark the farm with #sel pos1/pos2 first.");
            return;
        }
        String name = FarmSelection.currentName();
        if (name.isEmpty()) {
            int n = 1;
            while (FarmMap.exists("farm" + n)) {
                n++;
            }
            name = "farm" + n;
        }
        onClose();
        context.mapper().start(name, sel.min(), sel.max());
    }

    // -- harvest

    private void initHarvest(int x, int y, int w) {
        widgets.add(new FlatUI.Toggle(x + w - 34, y, AddonSettings::isHarvestTops,
                AddonSettings::setHarvestTops));
        widgets.add(new FlatUI.Toggle(x + w - 34, y + 26, AddonSettings::isHarvestDeposit,
                AddonSettings::setHarvestDeposit));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 50, 96, AddonSettings::getChestRadius,
                AddonSettings::setChestRadius, "blocks"));

        ApricornType[] types = ApricornPlanting.types();
        int colourY = y + 90;
        int buttonW = (w - 12) / 4;
        for (int i = 0; i < types.length; i++) {
            ApricornType type = types[i];
            widgets.add(new FlatUI.Button(x + (i % 4) * (buttonW + 4), colourY + (i / 4) * 22,
                    buttonW, 18, () -> ApricornPlanting.displayName(type),
                    () -> {
                        AddonSettings.setColourHarvested(type, !AddonSettings.isColourHarvested(type));
                        rebuildWidgets();
                    }, AddonSettings.isColourHarvested(type)));
        }
        widgets.add(new FlatUI.Button(x, colourY + 44, buttonW, 18, () -> "All", () -> {
            AddonSettings.setHarvestColours(Arrays.asList(ApricornPlanting.types()));
            rebuildWidgets();
        }, false));
        widgets.add(new FlatUI.Button(x + buttonW + 4, colourY + 44, buttonW, 18, () -> "Invert", () -> {
            List<ApricornType> inverted = new ArrayList<>();
            for (ApricornType type : ApricornPlanting.types()) {
                if (!AddonSettings.isColourHarvested(type)) {
                    inverted.add(type);
                }
            }
            if (!inverted.isEmpty()) {
                AddonSettings.setHarvestColours(inverted);
            }
            rebuildWidgets();
        }, false));

        runPair(() -> context.harvest() != null && context.harvest().isActive(),
                () -> {
                    context.applyAreaFor(TaskLocations.Task.HARVEST);
                    context.harvest().start();
                },
                () -> context.harvest().stop());
    }

    // -- planting

    private void initPlant(int x, int y, int w) {
        widgets.add(new FlatUI.Stepper(x + w - 96, y, 96, PlantConfig::getSpacing,
                PlantConfig::setSpacing, "blocks"));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 28, 96, PlantConfig::getClearance,
                PlantConfig::setClearance, "blocks"));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 56, 96, PlantConfig::getRowTolerance,
                PlantConfig::setRowTolerance, "blocks"));
        widgets.add(new FlatUI.Button(x + w - 170, y + 84, 170, 18,
                () -> PlantConfig.getRowAxis().label(),
                () -> PlantConfig.setRowAxis(PlantConfig.getRowAxis().other()), false));
        dropdowns.add(new FlatUI.Dropdown<>(x + w - 170, y + 110, 170,
                Arrays.asList(ApricornPlanting.types()), PlantConfig.getDefaultType(),
                ApricornPlanting::displayName, PlantConfig::setDefaultType));

        runPair(() -> context.plant() != null && context.plant().isRunning(),
                () -> {
                    context.applyAreaFor(TaskLocations.Task.PLANT);
                    context.plant().start();
                },
                () -> context.plant().stop());
    }

    // -- per-row colours

    private void initRows(int x, int y, int w) {
        List<Integer> rows = plantRows();
        if (rows.isEmpty()) {
            return;
        }
        int maxScroll = Math.max(0, rows.size() - ROWS_VISIBLE);
        rowScroll = Math.max(0, Math.min(rowScroll, maxScroll));
        int shown = Math.min(ROWS_VISIBLE, rows.size());
        for (int i = 0; i < shown; i++) {
            int index = rowScroll + i;
            int coord = rows.get(index);
            widgets.add(new FlatUI.Button(x, y + i * ROW_H, w - 30, 18,
                    () -> "Row " + index + " (" + PlantConfig.rowCoordName() + "=" + coord + "): "
                            + ApricornPlanting.displayName(PlantConfig.getRowType(coord)),
                    () -> {
                        // Left click is the only signal a plain button gives, so this cycles
                        // forwards; the Previous button below steps the other way.
                        PlantConfig.setRowType(coord, cycle(PlantConfig.getRowType(coord), false));
                        rebuildWidgets();
                    }, false));
        }
        if (maxScroll > 0) {
            widgets.add(new FlatUI.Button(x + w - 24, y, 24, 18, () -> "^", () -> {
                rowScroll = Math.max(0, rowScroll - ROWS_VISIBLE);
                rebuildWidgets();
            }, false));
            widgets.add(new FlatUI.Button(x + w - 24, y + (shown - 1) * ROW_H, 24, 18, () -> "v", () -> {
                rowScroll = Math.min(maxScroll, rowScroll + ROWS_VISIBLE);
                rebuildWidgets();
            }, false));
        }
        widgets.add(new FlatUI.Button(x, buttonRowY(), 90, 20, () -> "All rows", () -> {
            var sel = context.baritone().getSelectionManager().getLastSelection();
            if (sel != null) {
                PlantConfig.setAllRows(sel.min(), sel.max(),
                        cycle(PlantConfig.getDefaultType(), false));
            }
            rebuildWidgets();
        }, false));
    }

    private static ApricornType cycle(ApricornType type, boolean backwards) {
        ApricornType[] all = ApricornPlanting.types();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == type) {
                return all[(i + (backwards ? all.length - 1 : 1)) % all.length];
            }
        }
        return all[0];
    }

    /** The planting rows of the current selection, or empty when there is no selection. */
    private List<Integer> plantRows() {
        var sel = context.baritone().getSelectionManager().getLastSelection();
        return sel == null ? List.of() : PlantConfig.rowsOf(sel.min(), sel.max());
    }

    // -- bone meal

    private void initBonemeal(int x, int y, int w) {
        widgets.add(new FlatUI.Stepper(x + w - 96, y, 96, AddonSettings::getBonemealMax,
                AddonSettings::setBonemealMax, "max"));
        runPair(() -> context.bonemeal() != null && context.bonemeal().isRunning(),
                () -> {
                    context.applyAreaFor(TaskLocations.Task.BONEMEAL);
                    context.bonemeal().start();
                },
                () -> context.bonemeal().stop());
    }

    // -- poke balls

    private void initPokeball(int x, int y, int w) {
        List<RecipeHolder<?>> balls = PokeballRecipes.ballRecipes();
        RecipeHolder<?> selected = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        if (selected == null && !balls.isEmpty()) {
            selected = balls.get(0);
            PokeballConfig.setBallRecipeId(selected.id().toString());
        }
        if (!balls.isEmpty()) {
            dropdowns.add(new FlatUI.Dropdown<>(x, y, w - 100, balls, selected,
                    PokeballRecipes::ballName,
                    value -> {
                        PokeballConfig.setBallRecipeId(value.id().toString());
                        refreshPokeballPlan();
                        rebuildWidgets();
                    }));
        }
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 1, 96, PokeballConfig::getCount,
                value -> {
                    PokeballConfig.setCount(value);
                    refreshPokeballPlan();
                }, "balls"));

        dropdowns.add(new FlatUI.Dropdown<>(x, y + 44, (w - 8) / 2,
                List.of(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.BLAZE_ROD,
                        Items.DRIED_KELP_BLOCK),
                PokeballConfig.getFuel(), PokeballRecipes::nameOf, PokeballConfig::setFuel));
        widgets.add(new FlatUI.Stepper(x + (w + 8) / 2, y + 45, (w - 8) / 2,
                PokeballConfig::getStationRadius, PokeballConfig::setStationRadius, "blocks"));

        refreshPokeballPlan();
        widgets.add(new FlatUI.Button(x + 164, buttonRowY(), 74, 20, () -> "Plan",
                this::refreshPokeballPlan, false));
        runPair(() -> context.pokeball() != null && context.pokeball().isRunning(),
                () -> context.pokeball().start(),
                () -> context.pokeball().stop());
    }

    private void refreshPokeballPlan() {
        RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        pokeballPlan = ball == null || this.minecraft == null || this.minecraft.player == null
                ? null
                : PokeballRecipes.plan(ball, PokeballConfig.getCount(),
                        this.minecraft.player.getInventory());
    }

    // -- mine & hunt

    private void initMineHunt(int x, int y, int w) {
        List<OreMineController.Ore> ores = OreMineController.Ore.available();
        FlatUI.Dropdown<OreMineController.Ore> oreDropdown = null;
        if (!ores.isEmpty()) {
            oreDropdown = new FlatUI.Dropdown<>(x + w - 170, y, 170, ores,
                    ores.get(Math.min(oreIndex, ores.size() - 1)), OreMineController.Ore::label,
                    ore -> oreIndex = ores.indexOf(ore));
            dropdowns.add(oreDropdown);
        }
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 28, 96, () -> oreAmount,
                value -> oreAmount = Math.max(1, Math.min(2304, value)), "ore"));

        FlatUI.Dropdown<OreMineController.Ore> chosen = oreDropdown;
        widgets.add(new FlatUI.Button(x, y + 56, 110, 20,
                () -> context.ore() != null && context.ore().isRunning() ? "Mining..." : "Mine ore",
                () -> {
                    if (context.ore() == null || context.ore().isRunning() || chosen == null) {
                        return;
                    }
                    onClose();
                    context.ore().start(chosen.selected(), oreAmount);
                }, true));
        widgets.add(new FlatUI.Button(x + 118, y + 56, 90, 20, () -> "Cancel", () -> {
            if (context.ore() != null && context.ore().isRunning()) {
                context.ore().stop();
            }
        }, false));

        widgets.add(new FlatUI.Stepper(x + w - 96, y + 100, 96, () -> huntHops,
                value -> huntHops = Math.max(0, Math.min(100, value)), "hops"));
        widgets.add(new FlatUI.Button(x, y + 128, 110, 20,
                () -> context.hunter() != null && context.hunter().isRunning()
                        ? "Hunting..." : "Hunt colours",
                () -> {
                    if (context.hunter() == null || context.hunter().isRunning()) {
                        return;
                    }
                    onClose();
                    context.hunter().start(EnumSet.noneOf(ApricornType.class), huntHops);
                }, true));
        widgets.add(new FlatUI.Button(x + 118, y + 128, 90, 20, () -> "Scan here",
                ApricornHunter::report, false));
        widgets.add(new FlatUI.Button(x + 214, y + 128, 76, 20, () -> "Cancel", () -> {
            if (context.hunter() != null && context.hunter().isRunning()) {
                context.hunter().stop();
            }
        }, false));
    }

    // -- task areas

    private void initAreas(int x, int y, int w) {
        List<String> areas = new ArrayList<>();
        areas.add("(none)");
        areas.addAll(TaskLocations.savedSelectionNames());

        int rowY = y;
        for (TaskLocations.Task task : TaskLocations.Task.values()) {
            String current = TaskLocations.getSelection(task);
            dropdowns.add(new FlatUI.Dropdown<>(x + 62, rowY, 96, areas,
                    current.isEmpty() || !areas.contains(current) ? areas.get(0) : current,
                    name -> name,
                    name -> TaskLocations.setSelection(task, "(none)".equals(name) ? "" : name)));

            EditBox commandBox = new EditBox(this.font, x + 166, rowY + 1, w - 200, 18,
                    Component.literal(task.label() + " command"));
            commandBox.setValue(TaskLocations.getCommand(task));
            commandBox.setMaxLength(64);
            commandBox.setResponder(value -> TaskLocations.setCommand(task, value));
            addWidget(commandBox);
            boxes.add(commandBox);

            widgets.add(new FlatUI.Button(x + w - 30, rowY + 1, 30, 18, () -> "Go", () -> {
                boolean travelled = TaskLocations.sendTravel(task);
                boolean applied = TaskLocations.applySelection(context.baritone(), task);
                if (!travelled && !applied) {
                    Helper.HELPER.logDirect(task.label() + " has no travel command and no area set.");
                } else {
                    onClose();
                }
            }, false));
            rowY += 24;
        }
    }

    // -- help

    private void initHelp(int x, int y, int w) {
        int buttonW = (w - 12) / 4;
        for (int i = 0; i < helpTopicNames.size(); i++) {
            int index = i;
            widgets.add(new FlatUI.Button(x + (i % 4) * (buttonW + 4), y + (i / 4) * 22,
                    buttonW, 18, () -> helpTopicNames.get(index),
                    () -> {
                        helpTopic = index;
                        rebuildWidgets();
                    }, i == helpTopic));
        }
    }

    /** Adds the Run/Cancel pair a job tab shares. */
    private void runPair(BooleanSupplier running, Runnable start, Runnable stop) {
        int x = contentX();
        int y = buttonRowY();
        widgets.add(new FlatUI.Button(x, y, 74, 20,
                () -> running.getAsBoolean() ? "Running..." : "Run",
                () -> {
                    if (running.getAsBoolean()) {
                        return;
                    }
                    if (context.anyRunning()) {
                        Helper.HELPER.logDirect("Another job is already running - cancel it first.");
                        return;
                    }
                    onClose();
                    start.run();
                }, true));
        widgets.add(new FlatUI.Button(x + 82, y, 74, 20, () -> "Cancel", () -> {
            if (running.getAsBoolean()) {
                stop.run();
            }
        }, false));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (FlatUI.Dropdown<?> dropdown : dropdowns) {
            if (dropdown.mouseClicked(mouseX, mouseY, button)) {
                dropdowns.forEach(other -> {
                    if (other != dropdown) {
                        other.close();
                    }
                });
                return true;
            }
        }
        // Right-clicking a row colour steps backwards; the widgets only report left clicks.
        if (lastTab == Tab.ROWS && button == 1 && rowColourBack(mouseX, mouseY)) {
            return true;
        }
        for (FlatUI.Widget widget : widgets) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        int listTop = top() + 34;
        for (int i = 0; i < Tab.values().length; i++) {
            int rowY = listTop + i * TAB_H;
            if (mouseX >= left() + 4 && mouseX < left() + SIDEBAR_W - 4
                    && mouseY >= rowY && mouseY < rowY + TAB_H) {
                lastTab = Tab.values()[i];
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Right click on a row button: previous colour. */
    private boolean rowColourBack(double mouseX, double mouseY) {
        List<Integer> rows = plantRows();
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int shown = Math.min(ROWS_VISIBLE, rows.size());
        for (int i = 0; i < shown; i++) {
            int rowY = y + i * ROW_H;
            if (mouseX >= x && mouseX < x + w - 30 && mouseY >= rowY && mouseY < rowY + 18) {
                int coord = rows.get(rowScroll + i);
                PlantConfig.setRowType(coord, cycle(PlantConfig.getRowType(coord), true));
                rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (FlatUI.Dropdown<?> dropdown : dropdowns) {
            if (dropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        if (lastTab == Tab.ROWS) {
            int max = Math.max(0, plantRows().size() - ROWS_VISIBLE);
            int next = (int) Math.max(0, Math.min(max, rowScroll - Math.signum(scrollY)));
            if (next != rowScroll) {
                rowScroll = next;
                rebuildWidgets();
                return true;
            }
        }
        if (lastTab == Tab.FARMS) {
            int max = Math.max(0, FarmMap.names().size() - FARM_ROWS_VISIBLE);
            int next = (int) Math.max(0, Math.min(max, farmScroll - Math.signum(scrollY)));
            if (next != farmScroll) {
                farmScroll = next;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, FlatUI.BG);
        int x = left();
        int y = top();

        FlatUI.panel(g, x, y, PANEL_W, PANEL_H);
        g.fill(x, y, x + PANEL_W, y + 26, FlatUI.PANEL_LIGHT);
        g.fill(x, y + 26, x + PANEL_W, y + 27, FlatUI.BORDER);
        g.drawString(this.font, "Apricorn Harvester", x + 14, y + 9, FlatUI.TEXT, false);
        String status = jobStatus();
        g.drawString(this.font, status, x + PANEL_W - 14 - this.font.width(status), y + 9,
                context.anyRunning() ? FlatUI.ACCENT : FlatUI.TEXT_DIM, false);

        g.fill(x, y + 27, x + SIDEBAR_W, y + PANEL_H, FlatUI.SIDEBAR);
        int listTop = y + 34;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab tab = Tab.values()[i];
            int rowY = listTop + i * TAB_H;
            boolean hover = mouseX >= x + 4 && mouseX < x + SIDEBAR_W - 4
                    && mouseY >= rowY && mouseY < rowY + TAB_H;
            if (tab == lastTab) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + TAB_H, FlatUI.ACCENT_DIM);
            } else if (hover) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + TAB_H, FlatUI.PANEL_LIGHT);
            }
            g.drawString(this.font, tab.label, x + 12, rowY + 6,
                    tab == lastTab ? 0xFFFFFFFF : FlatUI.TEXT, false);
        }

        renderTab(g, mouseX, mouseY, partialTick);

        for (FlatUI.Widget widget : widgets) {
            widget.render(g, mouseX, mouseY);
        }
        for (FlatUI.Dropdown<?> dropdown : dropdowns) {
            dropdown.render(g, mouseX, mouseY);
        }
    }

    /** What the window's title bar reports: whichever job is running. */
    private String jobStatus() {
        if (context.harvest() != null && context.harvest().isActive()) {
            return "Harvesting";
        }
        if (context.plant() != null && context.plant().isRunning()) {
            return "Planting";
        }
        if (context.bonemeal() != null && context.bonemeal().isRunning()) {
            return "Bone-mealing";
        }
        if (context.pokeball() != null && context.pokeball().isRunning()) {
            return context.pokeball().status();
        }
        if (context.ore() != null && context.ore().isRunning()) {
            return context.ore().status();
        }
        if (context.hunter() != null && context.hunter().isRunning()) {
            return context.hunter().status();
        }
        if (mapperRunning()) {
            return context.mapper().status();
        }
        return "Idle";
    }

    private void renderTab(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        switch (lastTab) {
            case FARMS -> {
                int count = FarmMap.names().size();
                FlatUI.label(g, count == 0 ? "No farms yet" : count + " farm(s) - click one to work on it",
                        x, y - 10);
                int belowList = y + Math.max(Math.min(FARM_ROWS_VISIBLE, count), 1) * 22 + 38;
                FarmMap farm = FarmSelection.current();
                if (mapperRunning()) {
                    g.drawString(this.font, context.mapper().status(), x, belowList,
                            FlatUI.ACCENT, false);
                } else if (farm == null) {
                    g.drawString(this.font, count == 0
                                    ? "Select an area with #sel pos1 / pos2, then Map selection."
                                    : "No farm selected.",
                            x, belowList, FlatUI.TEXT_DIM, false);
                } else {
                    g.drawString(this.font, farm.summary(), x, belowList,
                            farm.isMapped() ? FlatUI.TEXT : FlatUI.BAD, false);
                    g.drawString(this.font, "bounds " + farm.min.getX() + "," + farm.min.getZ()
                            + " -> " + farm.max.getX() + "," + farm.max.getZ(),
                            x, belowList + 12, FlatUI.TEXT_DIM, false);
                }
                note(g, x, belowList + 30,
                        "Mapping walks the farm once so every chunk loads, recording the",
                        "paths, trees, colours and containers. Harvesting then plans over",
                        "the whole field instead of only the part you can see.");
            }
            case HARVEST -> {
                FlatUI.label(g, "Reach for high apricorns (tops)", x, y + 4);
                FlatUI.label(g, "Deposit into a chest afterwards", x, y + 30);
                FlatUI.label(g, "Chest search radius", x, y + 55);
                FlatUI.label(g, "Colours to harvest", x, y + 80);
                String filter = AddonSettings.isEveryColourHarvested()
                        ? "every colour" : ApricornHarvestProcess.colourFilterText();
                g.drawString(this.font, filter, x + w - this.font.width(filter), y + 80,
                        FlatUI.ACCENT, false);
            }
            case PLANT -> {
                FlatUI.label(g, "Grid spacing", x, y + 4);
                FlatUI.label(g, "Wall clearance", x, y + 32);
                FlatUI.label(g, "Row snap", x, y + 60);
                FlatUI.label(g, "Row direction", x, y + 88);
                FlatUI.label(g, "Default colour", x, y + 116);
                note(g, x, y + 150, "Snap lets a plant shift off its grid cell to find soil, so",
                        "rows that are not perfectly straight still come out complete.");
            }
            case ROWS -> {
                List<Integer> rows = plantRows();
                if (rows.isEmpty()) {
                    g.drawString(this.font, "No selection - use #sel pos1 / #sel pos2, or pick a farm.",
                            x, y + 4, FlatUI.BAD, false);
                } else {
                    note(g, x, top() + PANEL_H - 52,
                            rows.size() + " rows. Left click a row for the next colour,",
                            "right click for the previous one.");
                }
            }
            case BONEMEAL -> {
                FlatUI.label(g, "Bone meals per sapling", x, y + 4);
                note(g, x, y + 30, "How often one sapling is bone-mealed before the bot gives",
                        "up on it and moves to the next.");
            }
            case POKEBALL -> {
                FlatUI.label(g, "Ball", x, y - 10);
                FlatUI.label(g, "Amount", x + w - 96, y - 10);
                FlatUI.label(g, "Furnace fuel", x, y + 34);
                FlatUI.label(g, "Station search", x + (w + 8) / 2, y + 34);
                renderPokeballPlan(g, x, y + 76, w);
            }
            case MINE -> {
                FlatUI.label(g, "Ore", x, y + 6);
                FlatUI.label(g, "Amount", x, y + 34);
                note(g, x, y + 84, "Mines one ore on its own: out with the mine command, back",
                        "with the crafting base's.");
                FlatUI.label(g, "Hunt hops", x, y + 106);
                note(g, x, y + 154, "The hunt looks for apricorn colours you have none of, hopping",
                        "with the hunt command until it finds one, then paths to it.");
            }
            case AREAS -> {
                FlatUI.label(g, "Task", x, y - 10);
                FlatUI.label(g, "Area", x + 62, y - 10);
                FlatUI.label(g, "Travel command", x + 166, y - 10);
                int rowY = y;
                for (TaskLocations.Task task : TaskLocations.Task.values()) {
                    g.drawString(this.font, task.label(), x, rowY + 6, FlatUI.TEXT, false);
                    rowY += 24;
                }
                for (EditBox box : boxes) {
                    box.render(g, mouseX, mouseY, partialTick);
                }
            }
            case HELP -> {
                int lineY = y + 62;
                for (String line : helpTopics.get(helpTopic)) {
                    boolean command = line.trim().startsWith("#") || line.trim().startsWith("> #");
                    g.drawString(this.font, line, x, lineY,
                            command ? FlatUI.ACCENT : FlatUI.TEXT, false);
                    lineY += 11;
                }
            }
        }
    }

    /** The Poke Ball plan preview: what a run would mine, harvest, smelt and craft. */
    private void renderPokeballPlan(GuiGraphics g, int x, int y, int width) {
        g.fill(x, y, x + width, y + 60, FlatUI.PANEL_LIGHT);
        g.fill(x, y, x + width, y + 1, FlatUI.BORDER);
        if (pokeballPlan == null) {
            g.drawString(this.font, "No recipe data - join a world first.", x + 8, y + 8,
                    FlatUI.TEXT_DIM, false);
            return;
        }
        if (!pokeballPlan.isPossible()) {
            g.drawString(this.font, "Missing: " + String.join(", ", pokeballPlan.missing),
                    x + 8, y + 8, FlatUI.BAD, false);
            return;
        }
        if (pokeballPlan.isEmpty()) {
            g.drawString(this.font, "You already have everything needed.", x + 8, y + 8,
                    FlatUI.OK, false);
            return;
        }
        int line = 0;
        for (CraftPlan.Step step : pokeballPlan.steps) {
            if (line >= 4) {
                g.drawString(this.font, "+" + (pokeballPlan.steps.size() - 4) + " more steps",
                        x + 8, y + 8 + line * 12, FlatUI.TEXT_DIM, false);
                break;
            }
            g.drawString(this.font, step.describe(), x + 8, y + 8 + line * 12, FlatUI.TEXT, false);
            line++;
        }
    }

    private void note(GuiGraphics g, int x, int y, String... lines) {
        int lineY = y;
        for (String line : lines) {
            g.drawString(this.font, line, x, lineY, FlatUI.TEXT_DIM, false);
            lineY += 10;
        }
    }
}
