package com.brianthemint.apricornharvester.pokeball;

import com.brianthemint.apricornharvester.TaskLocations;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Poke Ball factory screen: pick a ball, a count and the travel commands, see exactly what the
 * run will mine, smelt and craft, then start it.
 *
 * <p>Drawn as a flat dark panel with its own dropdowns and buttons instead of the stone-textured
 * vanilla widgets - everything here is plain {@link GuiGraphics} fills, so it stays self-contained
 * and needs no textures.
 */
public class PokeballScreen extends Screen {

    // -- palette -----------------------------------------------------------------
    private static final int BG = 0xE6101216;
    private static final int PANEL = 0xFF1A1E24;
    private static final int PANEL_LIGHT = 0xFF232830;
    private static final int BORDER = 0xFF2E353F;
    private static final int ACCENT = 0xFF4CC2FF;
    private static final int ACCENT_DIM = 0xFF2A6E8F;
    private static final int TEXT = 0xFFE6EAF0;
    private static final int TEXT_DIM = 0xFF97A0AD;
    private static final int OK = 0xFF6FCF7A;
    private static final int BAD = 0xFFE06C6C;

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 292;

    private final PokeballFactory factory;

    private Dropdown<RecipeHolder<?>> ballDropdown;
    private Dropdown<Item> fuelDropdown;
    private Dropdown<String> farmDropdown;
    private EditBox countBox;
    private EditBox mineCommandBox;
    private EditBox homeCommandBox;
    private EditBox farmCommandBox;
    private final List<FlatButton> buttons = new ArrayList<>();

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
        ballDropdown = new Dropdown<>(x, y, fieldW - 90, balls, selected,
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
        fuelDropdown = new Dropdown<>(x, cmdY + 44, (fieldW - 8) / 2, fuels, PokeballConfig.getFuel(),
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
        farmDropdown = new Dropdown<>(x + (fieldW + 8) / 2, cmdY + 44, (fieldW - 8) / 2, areas,
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
        buttons.add(new FlatButton(x, buttonY, 100, 20, () -> factory.isRunning() ? "Stop" : "Start",
                () -> {
                    if (factory.isRunning()) {
                        factory.stop();
                    } else {
                        factory.start();
                    }
                }, true));
        buttons.add(new FlatButton(x + 108, buttonY, 100, 20, () -> "Refresh plan",
                this::refreshPreview, false));
        buttons.add(new FlatButton(left() + PANEL_W - 16 - 80, buttonY, 80, 20, () -> "Close",
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
        for (FlatButton b : buttons) {
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
        panel(g, x, y, PANEL_W, PANEL_H);

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

        for (FlatButton b : buttons) {
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

    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, PANEL);
    }

    // ------------------------------------------------------------------ widgets

    /** Flat text button; the primary one is drawn in the accent colour. */
    private final class FlatButton {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final Supplier<String> label;
        private final Runnable action;
        private final boolean primary;

        FlatButton(int x, int y, int w, int h, Supplier<String> label, Runnable action, boolean primary) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
            this.primary = primary;
        }

        boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !isOver(mx, my)) {
                return false;
            }
            action.run();
            return true;
        }

        boolean isOver(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        void render(GuiGraphics g, int mouseX, int mouseY) {
            boolean hover = isOver(mouseX, mouseY);
            int fill;
            if (primary) {
                fill = hover ? ACCENT : ACCENT_DIM;
            } else {
                fill = hover ? BORDER : PANEL_LIGHT;
            }
            g.fill(x, y, x + w, y + h, fill);
            String text = label.get();
            int tw = PokeballScreen.this.font.width(text);
            g.drawString(PokeballScreen.this.font, text, x + (w - tw) / 2,
                    y + (h - 8) / 2, primary ? 0xFF0A0E12 : TEXT, false);
        }
    }

    /** Flat dropdown with a scrollable list that floats over the panel while open. */
    private final class Dropdown<T> {
        private static final int ROW_H = 14;
        private static final int MAX_ROWS = 8;

        private final int x;
        private final int y;
        private final int w;
        private final List<T> values;
        private final java.util.function.Function<T, String> labeller;
        private final Consumer<T> onSelect;
        private T selected;
        private boolean open;
        private int scroll;

        Dropdown(int x, int y, int w, List<T> values, T selected,
                 java.util.function.Function<T, String> labeller, Consumer<T> onSelect) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.values = values;
            this.selected = selected;
            this.labeller = labeller;
            this.onSelect = onSelect;
        }

        void close() {
            open = false;
        }

        private int visibleRows() {
            return Math.min(MAX_ROWS, values.size());
        }

        boolean mouseClicked(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            if (mx >= x && mx < x + w && my >= y && my < y + 20) {
                open = !open;
                return true;
            }
            if (!open) {
                return false;
            }
            int listTop = y + 21;
            int rows = visibleRows();
            if (mx >= x && mx < x + w && my >= listTop && my < listTop + rows * ROW_H) {
                int index = scroll + (int) ((my - listTop) / ROW_H);
                if (index >= 0 && index < values.size()) {
                    selected = values.get(index);
                    onSelect.accept(selected);
                }
                open = false;
                return true;
            }
            open = false;
            return false;
        }

        boolean mouseScrolled(double mx, double my, double scrollY) {
            if (!open) {
                return false;
            }
            int rows = visibleRows();
            int listTop = y + 21;
            if (mx < x || mx >= x + w || my < listTop || my >= listTop + rows * ROW_H) {
                return false;
            }
            int max = Math.max(0, values.size() - rows);
            scroll = (int) Math.max(0, Math.min(max, scroll - Math.signum(scrollY)));
            return true;
        }

        void render(GuiGraphics g, int mouseX, int mouseY) {
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
            g.fill(x, y, x + w, y + 20, hover || open ? BORDER : PANEL_LIGHT);
            String text = selected == null ? "-" : labeller.apply(selected);
            g.drawString(PokeballScreen.this.font,
                    PokeballScreen.this.font.plainSubstrByWidth(text, w - 22),
                    x + 6, y + 6, TEXT, false);
            // caret
            g.drawString(PokeballScreen.this.font, open ? "^" : "v", x + w - 12, y + 6, ACCENT, false);

            if (!open) {
                return;
            }
            int rows = visibleRows();
            int listTop = y + 21;
            g.fill(x - 1, listTop - 1, x + w + 1, listTop + rows * ROW_H + 1, BORDER);
            g.fill(x, listTop, x + w, listTop + rows * ROW_H, PANEL);
            for (int i = 0; i < rows; i++) {
                int index = scroll + i;
                if (index >= values.size()) {
                    break;
                }
                int rowY = listTop + i * ROW_H;
                boolean rowHover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_H;
                boolean isSelected = values.get(index) == selected;
                if (rowHover) {
                    g.fill(x, rowY, x + w, rowY + ROW_H, ACCENT_DIM);
                } else if (isSelected) {
                    g.fill(x, rowY, x + w, rowY + ROW_H, PANEL_LIGHT);
                }
                g.drawString(PokeballScreen.this.font,
                        PokeballScreen.this.font.plainSubstrByWidth(labeller.apply(values.get(index)), w - 12),
                        x + 6, rowY + 3, isSelected ? ACCENT : TEXT, false);
            }
        }
    }
}
