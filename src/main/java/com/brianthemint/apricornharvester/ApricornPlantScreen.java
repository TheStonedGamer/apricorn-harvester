package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.selection.ISelection;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Planting GUI: grid spacing plus one apricorn colour per planting row, and a Start button.
 *
 * <p>The rows shown are the rows of the current Baritone selection (every
 * {@link PlantConfig#getSpacing()} blocks along Z); the list scrolls when the selection has more
 * rows than fit on screen. All changes are written straight into {@link PlantConfig}, which is
 * what {@link ApricornPlantProcess} and {@code #plant} read.
 *
 * <p>Opened with the key bound in Options &gt; Controls &gt; Apricorn Harvester
 * (see {@link ApricornKeybinds}) or with {@code #plant gui}.
 */
public class ApricornPlantScreen extends Screen {

    private static final int ROWS_VISIBLE = 6;
    private static final int ROW_HEIGHT = 22;

    private final IBaritone baritone;
    private final ApricornPlantProcess process;

    private BlockPos selMin;
    private BlockPos selMax;
    private List<Integer> rows = new ArrayList<>();
    private int scroll;

    public ApricornPlantScreen(IBaritone baritone, ApricornPlantProcess process) {
        super(Component.literal("Apricorn Planting"));
        this.baritone = baritone;
        this.process = process;
    }

    @Override
    protected void init() {
        readSelection();

        int cx = this.width / 2;
        int top = 40;

        // ---- spacing
        int spacingY = top;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            PlantConfig.setSpacing(PlantConfig.getSpacing() - 1);
            scroll = 0;
            rebuildWidgets();
        }).bounds(cx + 20, spacingY, 20, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal(PlantConfig.getSpacing() + " blocks"), b -> {
                }).bounds(cx + 44, spacingY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            PlantConfig.setSpacing(PlantConfig.getSpacing() + 1);
            scroll = 0;
            rebuildWidgets();
        }).bounds(cx + 118, spacingY, 20, 20).build());

        // ---- clearance from walls / selection border
        int clearanceY = top + 24;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            PlantConfig.setClearance(PlantConfig.getClearance() - 1);
            scroll = 0;
            rebuildWidgets();
        }).bounds(cx + 20, clearanceY, 20, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal(PlantConfig.getClearance() + " blocks"), b -> {
                }).bounds(cx + 44, clearanceY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            PlantConfig.setClearance(PlantConfig.getClearance() + 1);
            scroll = 0;
            rebuildWidgets();
        }).bounds(cx + 118, clearanceY, 20, 20).build());

        // ---- row direction (only two values, so both mouse buttons swap it)
        int dirY = top + 48;
        addRenderableWidget(new CycleButton(cx - 100, dirY, 200, 20,
                Component.literal("Rows: " + PlantConfig.getRowAxis().label()),
                back -> {
                    PlantConfig.setRowAxis(PlantConfig.getRowAxis().other());
                    scroll = 0;
                    rebuildWidgets();
                }));

        // ---- set every row at once
        int allY = top + 72;
        addRenderableWidget(new CycleButton(cx - 100, allY, 200, 20,
                Component.literal("All rows: " + ApricornPlanting.displayName(PlantConfig.getDefaultType())),
                back -> {
                    ApricornType next = cycle(PlantConfig.getDefaultType(), back);
                    if (selMin != null) {
                        PlantConfig.setAllRows(selMin, selMax, next);
                    } else {
                        PlantConfig.setDefaultType(next);
                    }
                    rebuildWidgets();
                }));

        // ---- per-row colour list
        int listTop = top + 116;
        if (selMin == null) {
            // No selection: only the spacing/default controls make sense.
            addDoneButton();
            return;
        }
        int maxScroll = Math.max(0, rows.size() - ROWS_VISIBLE);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int shown = Math.min(ROWS_VISIBLE, rows.size());
        for (int i = 0; i < shown; i++) {
            int rowIndex = scroll + i;
            int rowZ = rows.get(rowIndex);
            int y = listTop + i * ROW_HEIGHT;
            addRenderableWidget(new CycleButton(cx - 100, y, 200, 20,
                    Component.literal("Row " + rowIndex + " (" + PlantConfig.rowCoordName() + "=" + rowZ + "): "
                            + ApricornPlanting.displayName(PlantConfig.getRowType(rowZ))),
                    back -> {
                        PlantConfig.setRowType(rowZ, cycle(PlantConfig.getRowType(rowZ), back));
                        rebuildWidgets();
                    }));
        }

        if (maxScroll > 0 && shown > 1) {
            addRenderableWidget(Button.builder(Component.literal("^"), b -> {
                scroll = Math.max(0, scroll - ROWS_VISIBLE);
                rebuildWidgets();
            }).bounds(cx + 104, listTop, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("v"), b -> {
                scroll = Math.min(Math.max(0, rows.size() - ROWS_VISIBLE), scroll + ROWS_VISIBLE);
                rebuildWidgets();
            }).bounds(cx + 104, listTop + (shown - 1) * ROW_HEIGHT, 20, 20).build());
        }

        int buttonsY = listTop + shown * ROW_HEIGHT + 8;
        addRenderableWidget(Button.builder(
                Component.literal(process.isRunning() ? "Planting..." : "Run"), b -> {
                    if (process.isRunning()) {
                        return;
                    }
                    onClose();
                    process.start();
                }).bounds(cx - 100, buttonsY, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            process.stop();
            rebuildWidgets();
        }).bounds(cx + 2, buttonsY, 98, 20).build());
        addDoneButtonAt(buttonsY + 24);
    }

    private void addDoneButton() {
        addDoneButtonAt(this.height - 30);
    }

    private void addDoneButtonAt(int y) {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    /** Reads the current Baritone selection and the row list derived from it. */
    private void readSelection() {
        ISelection sel = baritone.getSelectionManager().getOnlySelection();
        if (sel == null) {
            sel = baritone.getSelectionManager().getLastSelection();
        }
        if (sel == null) {
            selMin = null;
            selMax = null;
            rows = new ArrayList<>();
            return;
        }
        selMin = sel.min();
        selMax = sel.max();
        rows = PlantConfig.rowsOf(selMin, selMax);
    }

    /** Next colour on a left click, previous colour on a right click ({@code backwards}). */
    private static ApricornType cycle(ApricornType type, boolean backwards) {
        ApricornType[] all = ApricornPlanting.types();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == type) {
                int step = backwards ? all.length - 1 : 1;
                return all[(i + step) % all.length];
            }
        }
        return all[0];
    }

    /**
     * A button that also reacts to the right mouse button, so a click cycles forwards and a
     * right click cycles backwards. Vanilla {@link Button} only accepts the left button.
     */
    private static final class CycleButton extends Button {

        private final Consumer<Boolean> onCycle;

        private CycleButton(int x, int y, int width, int height, Component message,
                            Consumer<Boolean> onCycle) {
            super(x, y, width, height, message, b -> {
            }, DEFAULT_NARRATION);
            this.onCycle = onCycle;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.active && this.visible && (button == 0 || button == 1)
                    && this.isMouseOver(mouseX, mouseY)) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                this.onCycle.accept(button == 1);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!rows.isEmpty() && rows.size() > ROWS_VISIBLE) {
            int maxScroll = rows.size() - ROWS_VISIBLE;
            int next = (int) Math.max(0, Math.min(maxScroll, scroll - Math.signum(scrollY)));
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 16, 0xFFFFFF);
        graphics.drawString(this.font, Component.literal("Spacing:"), cx - 100, 46, 0xFFFFFF, false);
        graphics.drawString(this.font, Component.literal("Wall clearance:"), cx - 100, 70, 0xFFFFFF, false);
        if (selMin == null) {
            graphics.drawCenteredString(this.font,
                    Component.literal("No selection - use #sel pos1 / #sel pos2 first"),
                    cx, 150, 0xFF5555);
        } else if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Selection too narrow for a clearance of " + PlantConfig.getClearance()),
                    cx, 148, 0xFF5555);
        } else {
            graphics.drawCenteredString(this.font,
                    Component.literal(rows.size() + " rows (" + PlantConfig.rowCoordName()
                            + "), selection " + selMin.getX() + "," + selMin.getZ()
                            + " -> " + selMax.getX() + "," + selMax.getZ()),
                    cx, 148, 0xAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
