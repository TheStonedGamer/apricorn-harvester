package com.brianthemint.apricornharvester.pokeball;

import com.brianthemint.apricornharvester.TaskLocations;
import com.brianthemint.apricornharvester.ui.FlatUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * The Poke Ball factory screen: pick a ball, a count and the travel commands, see exactly what the
 * run will mine, smelt and craft, then start it.
 *
 * <p>Drawn with the addon's flat widget kit ({@link FlatUI}) rather than the stone-textured vanilla
 * widgets, so it needs no textures and matches the settings screen.
 */
public class PokeballScreen extends Screen {

    private static final int BG = FlatUI.BG;
    private static final int PANEL_LIGHT = FlatUI.PANEL_LIGHT;
    private static final int BORDER = FlatUI.BORDER;
    private static final int ACCENT = FlatUI.ACCENT;
    private static final int TEXT = FlatUI.TEXT;
    private static final int TEXT_DIM = FlatUI.TEXT_DIM;
    private static final int OK = FlatUI.OK;
    private static final int BAD = FlatUI.BAD;

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 292;

    private final PokeballFactory factory;

    private FlatUI.Dropdown<RecipeHolder<?>> ballDropdown;
    private FlatUI.Dropdown<Item> fuelDropdown;
    private FlatUI.Dropdown<String> farmDropdown;
    private EditBox countBox;
    private EditBox mineCommandBox;
    private EditBox homeCommandBox;
    private EditBox farmCommandBox;
    private final List<FlatUI.Widget> buttons = new ArrayList<>();

    /** Cached plan preview, recomputed whenever a setting changes. */
    private CraftPlan preview;

    public PokeballScreen(PokeballFactory factory) {
        super(Component.literal("Poke Ball Factory"));
        this.factory = factory;
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    protected void init() {
        buttons.clear();
        int x = left() + 16;
        int y = top() + 40;
        int fieldW = PANEL_W - 32;

        List<RecipeHolder<?>> balls = PokeballRecipes.ballRecipes();
        RecipeHolder<?> selected = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        if (selected == null && !balls.isEmpty()) {
            selected = balls.get(0);
            PokeballConfig.setBallRecipeId(selected.id().toString());
        }
        ballDropdown = new FlatUI.Dropdown<>(x, y, fieldW - 90, balls, selected,
                PokeballRecipes::ballName,
                value -> {
                    PokeballConfig.setBallRecipeId(value.id().toString());
                    refreshPreview();
                });

        countBox = new EditBox(this.font, x + fieldW - 84, y + 1, 84, 18, Component.literal("Count"));
        countBox.setValue(String.valueOf(PokeballConfig.getCount()));
        countBox.setResponder(value -> {
            try {
                PokeballConfig.setCount(Integer.parseInt(value.trim()));
                refreshPreview();
            } catch (NumberFormatException ignored) {
                // Half-typed numbers are normal; the config keeps its last valid value.
            }
        });
        addWidget(countBox);

        int cmdY = y + 46;
        mineCommandBox = new EditBox(this.font, x, cmdY, (fieldW - 8) / 2, 18,
                Component.literal("Mine command"));
        mineCommandBox.setValue(PokeballConfig.getMineCommand());
        mineCommandBox.setMaxLength(64);
        mineCommandBox.setResponder(PokeballConfig::setMineCommand);
        addWidget(mineCommandBox);

        homeCommandBox = new EditBox(this.font, x + (fieldW + 8) / 2, cmdY, (fieldW - 8) / 2, 18,
                Component.literal("Home command"));
        homeCommandBox.setValue(PokeballConfig.getHomeCommand());
        homeCommandBox.setMaxLength(64);
        homeCommandBox.setResponder(PokeballConfig::setHomeCommand);
        addWidget(homeCommandBox);

        List<Item> fuels = List.of(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.BLAZE_ROD,
                Items.DRIED_KELP_BLOCK);
        fuelDropdown = new FlatUI.Dropdown<>(x, cmdY + 44, (fieldW - 8) / 2, fuels, PokeballConfig.getFuel(),
                PokeballRecipes::nameOf,
                value -> {
                    PokeballConfig.setFuel(value);
                    refreshPreview();
                });

        // Which saved selection the harvest step picks apricorns from.
        List<String> areas = new ArrayList<>();
        areas.add("(none)");
        areas.addAll(TaskLocations.savedSelectionNames());
        String currentArea = TaskLocations.getSelection(TaskLocations.Task.HARVEST);
        farmDropdown = new FlatUI.Dropdown<>(x + (fieldW + 8) / 2, cmdY + 44, (fieldW - 8) / 2, areas,
                currentArea.isEmpty() || !areas.contains(currentArea) ? areas.get(0) : currentArea,
                name -> name,
                name -> TaskLocations.setSelection(TaskLocations.Task.HARVEST,
                        "(none)".equals(name) ? "" : name));

        farmCommandBox = new EditBox(this.font, x, cmdY + 88, fieldW, 18,
                Component.literal("Farm command"));
        farmCommandBox.setValue(TaskLocations.getCommand(TaskLocations.Task.HARVEST));
        farmCommandBox.setMaxLength(64);
        farmCommandBox.setResponder(value ->
                TaskLocations.setCommand(TaskLocations.Task.HARVEST, value));
        addWidget(farmCommandBox);

        int buttonY = top() + PANEL_H - 30;
        buttons.add(new FlatUI.Button(x, buttonY, 74, 20,
                () -> factory.isRunning() ? "Running..." : "Run",
                () -> {
                    if (!factory.isRunning()) {
                        onClose();
                        factory.start();
                    }
                }, true));
        buttons.add(new FlatUI.Button(x + 82, buttonY, 74, 20, () -> "Cancel",
                () -> {
                    if (factory.isRunning()) {
                        factory.stop();
                    }
                }, false));
        buttons.add(new FlatUI.Button(x + 164, buttonY, 74, 20, () -> "Plan",
                this::refreshPreview, false));
        buttons.add(new FlatUI.Button(left() + PANEL_W - 16 - 56, buttonY, 56, 20, () -> "Close",
                this::onClose, false));

        refreshPreview();
    }

    /** Recomputes what the current settings would cost, for the plan panel. */
    private void refreshPreview() {
        RecipeHolder<?> ball = PokeballRecipes.ballRecipeById(PokeballConfig.getBallRecipeId());
        if (ball == null || this.minecraft == null || this.minecraft.player == null) {
            preview = null;
            return;
        }
        preview = PokeballRecipes.plan(ball, PokeballConfig.getCount(),
                this.minecraft.player.getInventory());
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Dropdowns first: their open list floats above everything else.
        if (ballDropdown.mouseClicked(mouseX, mouseY, button)) {
            fuelDropdown.close();
            farmDropdown.close();
            return true;
        }
        if (fuelDropdown.mouseClicked(mouseX, mouseY, button)) {
            ballDropdown.close();
            farmDropdown.close();
            return true;
        }
        if (farmDropdown.mouseClicked(mouseX, mouseY, button)) {
            ballDropdown.close();
            fuelDropdown.close();
            return true;
        }
        for (FlatUI.Widget b : buttons) {
            if (b.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ballDropdown.mouseScrolled(mouseX, mouseY, scrollY)
                || fuelDropdown.mouseScrolled(mouseX, mouseY, scrollY)
                || farmDropdown.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
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
        g.fill(0, 0, this.width, this.height, BG);

        int x = left();
        int y = top();
        FlatUI.panel(g, x, y, PANEL_W, PANEL_H);

        // title bar
        g.fill(x, y, x + PANEL_W, y + 26, PANEL_LIGHT);
        g.fill(x, y + 26, x + PANEL_W, y + 27, BORDER);
        g.drawString(this.font, "Poke Ball Factory", x + 14, y + 9, TEXT, false);
        String status = factory.isRunning() ? factory.status() : "Idle";
        int statusW = this.font.width(status);
        g.drawString(this.font, status, x + PANEL_W - 14 - statusW, y + 9,
                factory.isRunning() ? ACCENT : TEXT_DIM, false);

        int fx = x + 16;
        g.drawString(this.font, "Ball", fx, y + 32, TEXT_DIM, false);
        g.drawString(this.font, "Amount", x + PANEL_W - 16 - this.font.width("Amount"), y + 32,
                TEXT_DIM, false);
        countBox.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font, "Go-mine command", fx, y + 78, TEXT_DIM, false);
        g.drawString(this.font, "Go-home command", x + 16 + (PANEL_W - 24) / 2, y + 78, TEXT_DIM, false);
        mineCommandBox.render(g, mouseX, mouseY, partialTick);
        homeCommandBox.render(g, mouseX, mouseY, partialTick);

        g.drawString(this.font, "Furnace fuel", fx, y + 122, TEXT_DIM, false);
        g.drawString(this.font, "Apricorn farm area", x + 16 + (PANEL_W - 24) / 2, y + 122,
                TEXT_DIM, false);
        g.drawString(this.font, "Go-farm command", fx, y + 166, TEXT_DIM, false);
        farmCommandBox.render(g, mouseX, mouseY, partialTick);

        renderPlan(g, fx, y + 208, PANEL_W - 32);

        for (FlatUI.Widget b : buttons) {
            b.render(g, mouseX, mouseY);
        }
        // Dropdowns last so their open lists cover the panel.
        fuelDropdown.render(g, mouseX, mouseY);
        farmDropdown.render(g, mouseX, mouseY);
        ballDropdown.render(g, mouseX, mouseY);
    }

    /** The plan panel: what the run will do, or what is missing. */
    private void renderPlan(GuiGraphics g, int x, int y, int width) {
        int height = 44;
        g.fill(x, y, x + width, y + height, PANEL_LIGHT);
        g.fill(x, y, x + width, y + 1, BORDER);

        if (preview == null) {
            g.drawString(this.font, "No recipe data - join a world first.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        if (!preview.isPossible()) {
            g.drawString(this.font, "Missing: " + String.join(", ", preview.missing), x + 8, y + 8,
                    BAD, false);
            g.drawString(this.font, "Apricorns come from the farm (#apricorn).", x + 8, y + 20,
                    TEXT_DIM, false);
            return;
        }
        if (preview.isEmpty()) {
            g.drawString(this.font, "You already have everything needed.", x + 8, y + 8, OK, false);
            return;
        }
        int line = 0;
        for (CraftPlan.Step step : preview.steps) {
            if (line >= 3) {
                g.drawString(this.font, "+" + (preview.steps.size() - 3) + " more steps",
                        x + 8, y + 8 + line * 12, TEXT_DIM, false);
                break;
            }
            g.drawString(this.font, step.describe(), x + 8, y + 8 + line * 12, TEXT, false);
            line++;
        }
    }
}
