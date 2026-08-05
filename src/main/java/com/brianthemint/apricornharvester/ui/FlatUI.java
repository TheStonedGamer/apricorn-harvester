package com.brianthemint.apricornharvester.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The addon's flat UI kit: a palette and a handful of self-drawn widgets (button, toggle, stepper,
 * dropdown) used by the configuration and factory screens.
 *
 * <p>Everything is plain {@link GuiGraphics} fills and text, so the screens need no textures and
 * do not look like the stone-panel vanilla widgets. The widgets are deliberately dumb: they draw
 * themselves, report clicks and call back - the screens own all state.
 */
public final class FlatUI {

    public static final int BG = 0xE6101216;
    public static final int PANEL = 0xFF1A1E24;
    public static final int PANEL_LIGHT = 0xFF232830;
    public static final int SIDEBAR = 0xFF15181D;
    public static final int BORDER = 0xFF2E353F;
    public static final int ACCENT = 0xFF4CC2FF;
    public static final int ACCENT_DIM = 0xFF2A6E8F;
    public static final int TEXT = 0xFFE6EAF0;
    public static final int TEXT_DIM = 0xFF97A0AD;
    public static final int OK = 0xFF6FCF7A;
    public static final int BAD = 0xFFE06C6C;

    private FlatUI() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** A bordered panel. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, PANEL);
    }

    /** Left-aligned label in the dim text colour. */
    public static void label(GuiGraphics g, String text, int x, int y) {
        g.drawString(font(), text, x, y, TEXT_DIM, false);
    }

    /** Base of every widget here: a rectangle that knows whether the mouse is over it. */
    public abstract static class Widget {
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        protected Widget(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean isOver(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        public abstract void render(GuiGraphics g, int mouseX, int mouseY);

        /** True when the click was consumed. */
        public abstract boolean mouseClicked(double mx, double my, int button);
    }

    /** Flat text button; the primary variant is filled with the accent colour. */
    public static final class Button extends Widget {
        private final Supplier<String> label;
        private final Runnable action;
        private final boolean primary;

        public Button(int x, int y, int w, int h, Supplier<String> label, Runnable action,
                      boolean primary) {
            super(x, y, w, h);
            this.label = label;
            this.action = action;
            this.primary = primary;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            boolean hover = isOver(mouseX, mouseY);
            int fill = primary ? (hover ? ACCENT : ACCENT_DIM) : (hover ? BORDER : PANEL_LIGHT);
            g.fill(x, y, x + w, y + h, fill);
            String text = label.get();
            g.drawString(font(), text, x + (w - font().width(text)) / 2, y + (h - 8) / 2,
                    primary ? 0xFF0A0E12 : TEXT, false);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !isOver(mx, my)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /** On/off switch drawn as a pill; clicking anywhere on it flips the value. */
    public static final class Toggle extends Widget {
        private final Supplier<Boolean> value;
        private final Consumer<Boolean> onChange;

        public Toggle(int x, int y, Supplier<Boolean> value, Consumer<Boolean> onChange) {
            super(x, y, 34, 16);
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            boolean on = Boolean.TRUE.equals(value.get());
            boolean hover = isOver(mouseX, mouseY);
            g.fill(x, y, x + w, y + h, on ? (hover ? ACCENT : ACCENT_DIM) : (hover ? BORDER : PANEL_LIGHT));
            int knobX = on ? x + w - 14 : x + 2;
            g.fill(knobX, y + 2, knobX + 12, y + h - 2, on ? 0xFF0A0E12 : TEXT_DIM);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !isOver(mx, my)) {
                return false;
            }
            onChange.accept(!Boolean.TRUE.equals(value.get()));
            return true;
        }
    }

    /** Number field with -/+ buttons. Right-clicking a button steps by ten. */
    public static final class Stepper extends Widget {
        private final Supplier<Integer> value;
        private final Consumer<Integer> onChange;
        private final String suffix;

        public Stepper(int x, int y, int w, Supplier<Integer> value, Consumer<Integer> onChange,
                       String suffix) {
            super(x, y, w, 18);
            this.value = value;
            this.onChange = onChange;
            this.suffix = suffix;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            g.fill(x, y, x + w, y + h, PANEL_LIGHT);
            boolean minusHover = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + h;
            boolean plusHover = mouseX >= x + w - 18 && mouseX < x + w && mouseY >= y && mouseY < y + h;
            g.fill(x, y, x + 18, y + h, minusHover ? BORDER : PANEL_LIGHT);
            g.fill(x + w - 18, y, x + w, y + h, plusHover ? BORDER : PANEL_LIGHT);
            g.drawString(font(), "-", x + 8, y + 5, ACCENT, false);
            g.drawString(font(), "+", x + w - 11, y + 5, ACCENT, false);
            String text = value.get() + (suffix.isEmpty() ? "" : " " + suffix);
            g.drawString(font(), text, x + (w - font().width(text)) / 2, y + 5, TEXT, false);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!isOver(mx, my) || (button != 0 && button != 1)) {
                return false;
            }
            int step = button == 1 ? 10 : 1;
            if (mx < x + 18) {
                onChange.accept(value.get() - step);
                return true;
            }
            if (mx >= x + w - 18) {
                onChange.accept(value.get() + step);
                return true;
            }
            return false;
        }
    }

    /**
     * Dropdown with a scrollable list. The list is drawn on top of everything else, so screens
     * must render their dropdowns last and offer them clicks first.
     */
    public static final class Dropdown<T> extends Widget {
        private static final int ROW_H = 14;
        private static final int MAX_ROWS = 8;

        private final List<T> values;
        private final Function<T, String> labeller;
        private final Consumer<T> onSelect;
        private T selected;
        private boolean open;
        private int scroll;

        public Dropdown(int x, int y, int w, List<T> values, T selected,
                        Function<T, String> labeller, Consumer<T> onSelect) {
            super(x, y, w, 20);
            this.values = values;
            this.selected = selected;
            this.labeller = labeller;
            this.onSelect = onSelect;
        }

        public void close() {
            open = false;
        }

        public T selected() {
            return selected;
        }

        private int rows() {
            return Math.min(MAX_ROWS, values.size());
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0) {
                return false;
            }
            if (isOver(mx, my)) {
                open = !open;
                return true;
            }
            if (!open) {
                return false;
            }
            int listTop = y + h + 1;
            if (mx >= x && mx < x + w && my >= listTop && my < listTop + rows() * ROW_H) {
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

        public boolean mouseScrolled(double mx, double my, double scrollY) {
            if (!open) {
                return false;
            }
            int listTop = y + h + 1;
            if (mx < x || mx >= x + w || my < listTop || my >= listTop + rows() * ROW_H) {
                return false;
            }
            scroll = (int) Math.max(0, Math.min(Math.max(0, values.size() - rows()),
                    scroll - Math.signum(scrollY)));
            return true;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            boolean hover = isOver(mouseX, mouseY);
            g.fill(x, y, x + w, y + h, hover || open ? BORDER : PANEL_LIGHT);
            String text = selected == null ? "-" : labeller.apply(selected);
            g.drawString(font(), font().plainSubstrByWidth(text, w - 22), x + 6, y + 6, TEXT, false);
            g.drawString(font(), open ? "^" : "v", x + w - 12, y + 6, ACCENT, false);
            if (!open) {
                return;
            }
            int rows = rows();
            int listTop = y + h + 1;
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
                g.drawString(font(),
                        font().plainSubstrByWidth(labeller.apply(values.get(index)), w - 12),
                        x + 6, rowY + 3, isSelected ? ACCENT : TEXT, false);
            }
        }
    }
}
