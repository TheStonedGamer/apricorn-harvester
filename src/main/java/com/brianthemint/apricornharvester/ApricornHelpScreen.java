package com.brianthemint.apricornharvester;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The help screen behind {@code #ah gui}: the same cheat sheets as the chat command, with a topic
 * list on the left and the selected topic on the right. Styled like the Poke Ball factory screen -
 * flat panels, no textures.
 */
public class ApricornHelpScreen extends Screen {

    private static final int BG = 0xE6101216;
    private static final int PANEL = 0xFF1A1E24;
    private static final int PANEL_LIGHT = 0xFF232830;
    private static final int BORDER = 0xFF2E353F;
    private static final int ACCENT = 0xFF4CC2FF;
    private static final int ACCENT_DIM = 0xFF2A6E8F;
    private static final int TEXT = 0xFFE6EAF0;
    private static final int TEXT_DIM = 0xFF97A0AD;

    private static final int PANEL_W = 420;
    private static final int PANEL_H = 240;
    private static final int SIDEBAR_W = 108;
    private static final int ROW_H = 18;

    /** One entry of the topic list: its label and the lines it shows. */
    private record Topic(String label, String[] lines) {
    }

    private final List<Topic> topics = new ArrayList<>();
    private int selected;

    public ApricornHelpScreen() {
        super(Component.literal("Apricorn Harvester Help"));
        topics.add(new Topic("Workflow", ApricornHelpCommand.WORKFLOW));
        topics.add(new Topic("Farms", ApricornHelpCommand.FARMS));
        topics.add(new Topic("Harvest", ApricornHelpCommand.HARVEST));
        topics.add(new Topic("Plant", ApricornHelpCommand.PLANT));
        topics.add(new Topic("Bone meal", ApricornHelpCommand.BONEMEAL));
        topics.add(new Topic("Poke Balls", ApricornHelpCommand.POKEBALL));
        topics.add(new Topic("Ore", ApricornHelpCommand.ORE));
        topics.add(new Topic("Hunt", ApricornHelpCommand.HUNT));
        topics.add(new Topic("Task areas", ApricornHelpCommand.AREAS));
        topics.add(new Topic("Hotkeys", ApricornHelpCommand.KEYS));
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x = left();
            int listTop = top() + 34;
            for (int i = 0; i < topics.size(); i++) {
                int rowY = listTop + i * ROW_H;
                if (mouseX >= x + 8 && mouseX < x + SIDEBAR_W && mouseY >= rowY && mouseY < rowY + ROW_H) {
                    selected = i;
                    return true;
                }
            }
            int closeX = left() + PANEL_W - 62;
            int closeY = top() + PANEL_H - 26;
            if (mouseX >= closeX && mouseX < closeX + 54 && mouseY >= closeY && mouseY < closeY + 18) {
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BG);
        int x = left();
        int y = top();

        g.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, BORDER);
        g.fill(x, y, x + PANEL_W, y + PANEL_H, PANEL);
        g.fill(x, y, x + PANEL_W, y + 26, PANEL_LIGHT);
        g.fill(x, y + 26, x + PANEL_W, y + 27, BORDER);
        g.drawString(this.font, "Apricorn Harvester", x + 14, y + 9, TEXT, false);
        g.drawString(this.font, "#ah  -  #help <command> for full pages",
                x + PANEL_W - 14 - this.font.width("#ah  -  #help <command> for full pages"),
                y + 9, TEXT_DIM, false);

        // topic list
        g.fill(x, y + 27, x + SIDEBAR_W, y + PANEL_H, 0xFF15181D);
        int listTop = y + 34;
        for (int i = 0; i < topics.size(); i++) {
            int rowY = listTop + i * ROW_H;
            boolean hover = mouseX >= x + 8 && mouseX < x + SIDEBAR_W
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (i == selected) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + ROW_H, ACCENT_DIM);
            } else if (hover) {
                g.fill(x + 4, rowY, x + SIDEBAR_W - 4, rowY + ROW_H, PANEL_LIGHT);
            }
            g.drawString(this.font, topics.get(i).label(), x + 12, rowY + 5,
                    i == selected ? 0xFFFFFFFF : TEXT, false);
        }

        // selected topic
        int contentX = x + SIDEBAR_W + 12;
        int contentY = y + 36;
        for (String line : topics.get(selected).lines()) {
            boolean isCommand = line.trim().startsWith("#") || line.trim().startsWith("  #");
            g.drawString(this.font, line, contentX, contentY,
                    line.isEmpty() ? TEXT_DIM : (isCommand ? ACCENT : TEXT), false);
            contentY += 11;
        }

        // close button
        int closeX = x + PANEL_W - 62;
        int closeY = y + PANEL_H - 26;
        boolean closeHover = mouseX >= closeX && mouseX < closeX + 54
                && mouseY >= closeY && mouseY < closeY + 18;
        g.fill(closeX, closeY, closeX + 54, closeY + 18, closeHover ? BORDER : PANEL_LIGHT);
        g.drawString(this.font, "Close", closeX + (54 - this.font.width("Close")) / 2, closeY + 5,
                TEXT, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
