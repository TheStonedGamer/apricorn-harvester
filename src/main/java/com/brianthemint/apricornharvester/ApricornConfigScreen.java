package com.brianthemint.apricornharvester;

import baritone.api.utils.Helper;
import com.brianthemint.apricornharvester.pokeball.PokeballScreen;
import com.brianthemint.apricornharvester.ui.FlatUI;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;


/**
 * One screen for every setting in the addon, opened with its own key binding (Options &gt;
 * Controls &gt; Apricorn Harvester) or {@code #config}.
 *
 * <p>Four tabs: harvesting, planting, bone meal and the task areas. Everything written here goes
 * straight into {@link AddonSettings}, {@link PlantConfig} and {@link TaskLocations}, which persist
 * to disk, so the same values are what the {@code #apricorn}, {@code #plant}, {@code #bonemeal} and
 * {@code #pokeball} commands use.
 */
public class ApricornConfigScreen extends Screen {

    private static final int PANEL_W = 400;
    private static final int PANEL_H = 244;
    private static final int SIDEBAR_W = 96;

    private enum Tab {
        HARVEST("Harvest"),
        PLANT("Planting"),
        BONEMEAL("Bone meal"),
        MINE("Mine & hunt"),
        AREAS("Task areas");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private final AddonContext context;

    private Tab tab = Tab.HARVEST;
    /** Mine &amp; hunt tab state; kept on the screen because it is per-run, not a saved setting. */
    private int oreIndex;
    private int oreAmount = 32;
    private int huntHops = 10;
    private final List<FlatUI.Widget> widgets = new ArrayList<>();
    private final List<FlatUI.Dropdown<?>> dropdowns = new ArrayList<>();
    private final List<EditBox> boxes = new ArrayList<>();

    public ApricornConfigScreen(AddonContext context) {
        super(Component.literal("Apricorn Harvester settings"));
        this.context = context;
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

    @Override
    protected void init() {
        widgets.clear();
        dropdowns.clear();
        boxes.clear();
        this.clearWidgets();

        int x = contentX();
        int y = top() + 42;
        int w = contentW();

        switch (tab) {
            case HARVEST -> initHarvest(x, y, w);
            case PLANT -> initPlant(x, y, w);
            case BONEMEAL -> initBonemeal(x, y, w);
            case MINE -> initMineHunt(x, y, w);
            case AREAS -> initAreas(x, y, w);
        }

        addRunButtons();
        widgets.add(new FlatUI.Button(left() + PANEL_W - 76, top() + PANEL_H - 28, 62, 20,
                () -> "Close", this::onClose, false));
    }

    /**
     * The Run/Cancel pair for the tab's job. Run loads the area saved for that task first, so the
     * button does the whole thing; while the job is running Run turns into a disabled-looking
     * label and Cancel stops it.
     */
    private void addRunButtons() {
        int x = contentX();
        int y = top() + PANEL_H - 28;
        switch (tab) {
            case HARVEST -> {
                if (context.harvest() != null) {
                    runPair(x, y, () -> context.harvest().isActive(),
                            () -> {
                                context.applyAreaFor(TaskLocations.Task.HARVEST);
                                context.harvest().start();
                            },
                            () -> context.harvest().stop());
                }
            }
            case PLANT -> {
                if (context.plant() != null) {
                    runPair(x, y, () -> context.plant().isRunning(),
                            () -> {
                                context.applyAreaFor(TaskLocations.Task.PLANT);
                                context.plant().start();
                            },
                            () -> context.plant().stop());
                }
            }
            case BONEMEAL -> {
                if (context.bonemeal() != null) {
                    runPair(x, y, () -> context.bonemeal().isRunning(),
                            () -> {
                                context.applyAreaFor(TaskLocations.Task.BONEMEAL);
                                context.bonemeal().start();
                            },
                            () -> context.bonemeal().stop());
                }
            }
            case AREAS -> {
                // No single job to run here; each row has its own "Go" button instead.
            }
        }
    }

    private void runPair(int x, int y, java.util.function.BooleanSupplier running,
                         Runnable start, Runnable stop) {
        widgets.add(new FlatUI.Button(x, y, 74, 20,
                () -> running.getAsBoolean() ? "Running..." : "Run",
                () -> {
                    if (running.getAsBoolean()) {
                        return;
                    }
                    // Only one job at a time: they all steer the same player, and Baritone would
                    // hand control to whichever process happens to win on priority.
                    if (context.anyRunning()) {
                        Helper.HELPER.logDirect("Another job is already running - cancel it first.");
                        return;
                    }
                    this.onClose();
                    start.run();
                }, true));
        widgets.add(new FlatUI.Button(x + 82, y, 74, 20, () -> "Cancel",
                () -> {
                    if (running.getAsBoolean()) {
                        stop.run();
                    }
                }, false));
    }

    private void initHarvest(int x, int y, int w) {
        widgets.add(new FlatUI.Toggle(x + w - 34, y, AddonSettings::isHarvestTops,
                AddonSettings::setHarvestTops));
        widgets.add(new FlatUI.Toggle(x + w - 34, y + 30, AddonSettings::isHarvestDeposit,
                AddonSettings::setHarvestDeposit));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 58, 96, AddonSettings::getChestRadius,
                AddonSettings::setChestRadius, "blocks"));

        // Colour filter: one button per apricorn colour, lit when that colour is picked.
        ApricornType[] types = ApricornPlanting.types();
        int colourY = y + 96;
        int buttonW = (w - 12) / 4;
        for (int i = 0; i < types.length; i++) {
            ApricornType type = types[i];
            int bx = x + (i % 4) * (buttonW + 4);
            int by = colourY + (i / 4) * 22;
            widgets.add(new FlatUI.Button(bx, by, buttonW, 18,
                    () -> ApricornPlanting.displayName(type),
                    () -> {
                        AddonSettings.setColourHarvested(type, !AddonSettings.isColourHarvested(type));
                        // The lit/unlit state is baked in at construction, so rebuild to show it.
                        rebuildWidgets();
                    },
                    AddonSettings.isColourHarvested(type)));
        }
        int allY = colourY + 44;
        widgets.add(new FlatUI.Button(x, allY, buttonW, 18, () -> "All", () -> {
            AddonSettings.setHarvestColours(Arrays.asList(ApricornPlanting.types()));
            rebuildWidgets();
        }, false));
        widgets.add(new FlatUI.Button(x + buttonW + 4, allY, buttonW, 18, () -> "Invert", () -> {
            List<ApricornType> inverted = new ArrayList<>();
            for (ApricornType type : ApricornPlanting.types()) {
                if (!AddonSettings.isColourHarvested(type)) {
                    inverted.add(type);
                }
            }
            // Nothing selected means "all", so an inversion that empties the set is ignored.
            if (!inverted.isEmpty()) {
                AddonSettings.setHarvestColours(inverted);
            }
            rebuildWidgets();
        }, false));
    }

    private void initPlant(int x, int y, int w) {
        widgets.add(new FlatUI.Stepper(x + w - 96, y, 96, PlantConfig::getSpacing,
                PlantConfig::setSpacing, "blocks"));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 28, 96, PlantConfig::getClearance,
                PlantConfig::setClearance, "blocks"));
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 56, 96, PlantConfig::getRowTolerance,
                PlantConfig::setRowTolerance, "blocks"));
        widgets.add(new FlatUI.Button(x + w - 150, y + 84, 150, 18,
                () -> PlantConfig.getRowAxis().label(),
                () -> PlantConfig.setRowAxis(PlantConfig.getRowAxis().other()), false));

        FlatUI.Dropdown<ApricornType> colours = new FlatUI.Dropdown<>(x + w - 150, y + 110, 150,
                Arrays.asList(ApricornPlanting.types()), PlantConfig.getDefaultType(),
                ApricornPlanting::displayName, PlantConfig::setDefaultType);
        dropdowns.add(colours);

        widgets.add(new FlatUI.Button(x, y + 140, 190, 20, () -> "Per-row colours...", () -> {
            if (context.plant() != null) {
                this.minecraft.setScreen(new ApricornPlantScreen(context.baritone(), context.plant()));
            }
        }, false));
    }

    private void initBonemeal(int x, int y, int w) {
        widgets.add(new FlatUI.Stepper(x + w - 96, y, 96, AddonSettings::getBonemealMax,
                AddonSettings::setBonemealMax, "max"));
    }

    /** Standalone ore mining and the apricorn hunt: pick, then Run. */
    private void initMineHunt(int x, int y, int w) {
        List<OreMineController.Ore> ores = OreMineController.Ore.available();
        FlatUI.Dropdown<OreMineController.Ore> oreDropdown = new FlatUI.Dropdown<>(x + w - 150, y, 150,
                ores, ores.isEmpty() ? null : ores.get(Math.min(oreIndex, ores.size() - 1)),
                OreMineController.Ore::label,
                ore -> oreIndex = ores.indexOf(ore));
        dropdowns.add(oreDropdown);

        widgets.add(new FlatUI.Stepper(x + w - 96, y + 28, 96, () -> oreAmount,
                value -> oreAmount = Math.max(1, Math.min(2304, value)), "ore"));

        widgets.add(new FlatUI.Button(x, y + 56, 110, 20,
                () -> context.ore() != null && context.ore().isRunning() ? "Mining..." : "Mine ore",
                () -> {
                    if (context.ore() == null || context.ore().isRunning() || ores.isEmpty()) {
                        return;
                    }
                    onClose();
                    context.ore().start(oreDropdown.selected(), oreAmount);
                }, true));
        widgets.add(new FlatUI.Button(x + 118, y + 56, 90, 20, () -> "Cancel", () -> {
            if (context.ore() != null && context.ore().isRunning()) {
                context.ore().stop();
            }
        }, false));

        // --- apricorn hunt
        widgets.add(new FlatUI.Stepper(x + w - 96, y + 100, 96, () -> huntHops,
                value -> huntHops = Math.max(0, Math.min(100, value)), "hops"));
        widgets.add(new FlatUI.Button(x, y + 128, 110, 20,
                () -> context.hunter() != null && context.hunter().isRunning() ? "Hunting..." : "Hunt colours",
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

    private void initAreas(int x, int y, int w) {
        List<String> areas = new ArrayList<>();
        areas.add("(none)");
        areas.addAll(TaskLocations.savedSelectionNames());

        int rowY = y;
        for (TaskLocations.Task task : TaskLocations.Task.values()) {
            String current = TaskLocations.getSelection(task);
            FlatUI.Dropdown<String> areaDropdown = new FlatUI.Dropdown<>(x + 62, rowY, 96, areas,
                    current.isEmpty() || !areas.contains(current) ? areas.get(0) : current,
                    name -> name,
                    name -> TaskLocations.setSelection(task, "(none)".equals(name) ? "" : name));
            dropdowns.add(areaDropdown);

            EditBox commandBox = new EditBox(this.font, x + 166, rowY + 1, w - 200, 18,
                    Component.literal(task.label() + " command"));
            commandBox.setValue(TaskLocations.getCommand(task));
            commandBox.setMaxLength(64);
            commandBox.setResponder(value -> TaskLocations.setCommand(task, value));
            addWidget(commandBox);
            boxes.add(commandBox);

            // Travel there and load the area, the same as "#loc <task> go".
            widgets.add(new FlatUI.Button(x + w - 30, rowY + 1, 30, 18, () -> "Go", () -> {
                boolean travelled = TaskLocations.sendTravel(task);
                boolean applied = TaskLocations.applySelection(context.baritone(), task);
                if (!travelled && !applied) {
                    Helper.HELPER.logDirect(task.label() + " has no travel command and no area set.");
                } else {
                    this.onClose();
                }
            }, false));

            rowY += 26;
        }
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
        for (FlatUI.Widget widget : widgets) {
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        // Tab strip
        int listTop = top() + 34;
        for (int i = 0; i < Tab.values().length; i++) {
            int rowY = listTop + i * 20;
            if (mouseX >= left() + 4 && mouseX < left() + SIDEBAR_W - 4
                    && mouseY >= rowY && mouseY < rowY + 20) {
                tab = Tab.values()[i];
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (FlatUI.Dropdown<?> dropdown : dropdowns) {
            if (dropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
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
        String hint = "settings are saved automatically";
        g.drawString(this.font, hint, x + PANEL_W - 14 - this.font.width(hint), y + 9,
                FlatUI.TEXT_DIM, false);

        // tab strip
        g.fill(x, y + 27, x + SIDEBAR_W, y + PANEL_H, FlatUI.SIDEBAR);
        int listTop = y + 34;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab t = Tab.values()[i];
            int rowY = listTop + i * 20;
            boolean hover = mouseX >= x + 4 && mouseX < x + SIDEBAR_W - 4
                    && mouseY >= rowY && mouseY < rowY + 20;
            if (t == tab) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + 20, FlatUI.ACCENT_DIM);
            } else if (hover) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + 20, FlatUI.PANEL_LIGHT);
            }
            g.drawString(this.font, t.label, x + 12, rowY + 6,
                    t == tab ? 0xFFFFFFFF : FlatUI.TEXT, false);
        }

        renderTabContent(g, mouseX, mouseY, partialTick);

        for (FlatUI.Widget widget : widgets) {
            widget.render(g, mouseX, mouseY);
        }
        // Dropdowns last: their open lists float above the rest.
        for (FlatUI.Dropdown<?> dropdown : dropdowns) {
            dropdown.render(g, mouseX, mouseY);
        }
    }

    private void renderTabContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int y = top() + 42;
        switch (tab) {
            case HARVEST -> {
                FlatUI.label(g, "Reach for high apricorns (tops)", x, y + 4);
                FlatUI.label(g, "Deposit into a chest afterwards", x, y + 34);
                FlatUI.label(g, "Chest search radius", x, y + 63);
                FlatUI.label(g, "Colours to harvest", x, y + 86);
                String filter = AddonSettings.isEveryColourHarvested()
                        ? "every colour" : ApricornHarvestProcess.colourFilterText();
                g.drawString(this.font, filter,
                        x + contentW() - this.font.width(filter), y + 86, FlatUI.ACCENT, false);
            }
            case PLANT -> {
                FlatUI.label(g, "Grid spacing", x, y + 4);
                FlatUI.label(g, "Wall clearance", x, y + 32);
                FlatUI.label(g, "Row snap", x, y + 60);
                FlatUI.label(g, "Row direction", x, y + 88);
                FlatUI.label(g, "Default colour", x, y + 116);
                note(g, x, y + 166, "Snap lets a plant shift off its grid cell to find soil, so",
                        "rows that are not perfectly straight still come out complete.");
            }
            case BONEMEAL -> {
                FlatUI.label(g, "Bone meals per sapling", x, y + 4);
                note(g, x, y + 30, "How often one sapling is bone-mealed before the bot gives",
                        "up on it and moves to the next.");
            }
            case MINE -> {
                FlatUI.label(g, "Ore", x, y + 6);
                FlatUI.label(g, "Amount", x, y + 34);
                note(g, x, y + 84, "Mines one ore on its own: out with the mine command, back",
                        "with the crafting base's. Same as #ore platinum 32.");
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
                    rowY += 26;
                }
                for (EditBox box : boxes) {
                    box.render(g, mouseX, mouseY, partialTick);
                }
                note(g, x, top() + PANEL_H - 46,
                        "Areas come from #save <name>. The Poke Ball factory uses the",
                        "mine, harvest and craft rows.");
            }
        }
    }

    private void note(GuiGraphics g, int x, int y, String... lines) {
        int lineY = y;
        for (String line : lines) {
            g.drawString(this.font, line, x, lineY, FlatUI.TEXT_DIM, false);
            lineY += 10;
        }
    }

    /** Opens the Poke Ball factory screen; kept here so the key binding has one entry point. */
    public void openPokeballScreen() {
        if (context.pokeball() != null) {
            this.minecraft.setScreen(new PokeballScreen(context.pokeball()));
        }
    }
}
