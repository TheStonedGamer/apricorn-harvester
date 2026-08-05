package com.brianthemint.apricornharvester;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.selection.ISelection;
import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code #plant} command: plants apricorns on a grid inside the current Baritone selection,
 * with a per-row colour. The same settings are editable in the addon window (the Row colours tab,
 * bound to a key in Minecraft's controls).
 */
public class ApricornPlantCommand implements ICommand {

    private final IBaritone baritone;
    private final ApricornPlantProcess process;

    public ApricornPlantCommand(IBaritone baritone, ApricornPlantProcess process) {
        this.baritone = baritone;
        this.process = process;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        if (!args.hasAny()) {
            process.start();
            return;
        }
        String sub = args.getString();

        switch (sub.toLowerCase()) {
            case "start":
            case "go":
                process.start();
                return;
            case "stop":
            case "cancel":
                process.stop();
                return;
            case "pause":
                process.pause();
                return;
            case "resume":
                process.resume();
                return;
            case "gui":
                ApricornGui.open(ApricornGui.Tab.ROWS);
                return;
            case "spacing": {
                if (!args.hasAny()) {
                    logDirect("Spacing = " + PlantConfig.getSpacing() + " blocks.");
                    return;
                }
                try {
                    int value = Integer.parseInt(args.getString());
                    PlantConfig.setSpacing(value);
                    logDirect("Spacing = " + PlantConfig.getSpacing() + " blocks.");
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of blocks (" + PlantConfig.MIN_SPACING + "-"
                            + PlantConfig.MAX_SPACING + ").");
                }
                return;
            }
            case "snap":
            case "tolerance": {
                if (!args.hasAny()) {
                    logDirect("Row snap = " + PlantConfig.getRowTolerance()
                            + " blocks (how far a plant may shift off the grid to find soil).");
                    return;
                }
                try {
                    PlantConfig.setRowTolerance(Integer.parseInt(args.getString()));
                    int value = PlantConfig.getRowTolerance();
                    logDirect("Row snap = " + value
                            + (value == 0 ? " (strict grid: cells with no usable soil are skipped)."
                                          : " (rows may wobble up to " + value + " block(s))."));
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of blocks (0-" + PlantConfig.MAX_ROW_TOLERANCE + ").");
                }
                return;
            }
            case "clearance":
            case "margin": {
                if (!args.hasAny()) {
                    logDirect("Clearance = " + PlantConfig.getClearance()
                            + " blocks from walls and the selection border.");
                    return;
                }
                try {
                    PlantConfig.setClearance(Integer.parseInt(args.getString()));
                    logDirect("Clearance = " + PlantConfig.getClearance()
                            + " blocks (first plant is " + (PlantConfig.getClearance() + 1)
                            + " blocks in from a wall).");
                } catch (NumberFormatException e) {
                    logDirect("Expected a number of blocks (0-" + PlantConfig.MAX_CLEARANCE + ").");
                }
                return;
            }
            case "dir":
            case "direction":
            case "rows": {
                if (!args.hasAny()) {
                    logDirect("Row direction = " + PlantConfig.getRowAxis().label() + ".");
                    return;
                }
                PlantConfig.RowAxis axis = parseAxis(args.getString());
                if (axis == null) {
                    logDirect("Expected x (east-west rows), z (north-south rows) or flip.");
                    return;
                }
                PlantConfig.setRowAxis(axis);
                logDirect("Row direction = " + axis.label()
                        + ". Rows are numbered along " + PlantConfig.rowCoordName() + ".");
                return;
            }
            case "type": {
                if (!args.hasAny()) {
                    logDirect("Default apricorn = " + ApricornPlanting.displayName(PlantConfig.getDefaultType()) + ".");
                    return;
                }
                ApricornType type = ApricornPlanting.parse(args.getString());
                if (type == null) {
                    logDirect("Unknown apricorn colour. Try: " + colours());
                    return;
                }
                PlantConfig.setDefaultType(type);
                logDirect("Default apricorn = " + ApricornPlanting.displayName(type) + ".");
                return;
            }
            case "all": {
                BlockPos[] sel = selection();
                if (sel == null) {
                    return;
                }
                if (!args.hasAny()) {
                    logDirect("Usage: #plant all <colour>");
                    return;
                }
                ApricornType type = ApricornPlanting.parse(args.getString());
                if (type == null) {
                    logDirect("Unknown apricorn colour. Try: " + colours());
                    return;
                }
                PlantConfig.setAllRows(sel[0], sel[1], type);
                logDirect("All rows set to " + ApricornPlanting.displayName(type) + ".");
                return;
            }
            case "row": {
                BlockPos[] sel = selection();
                if (sel == null) {
                    return;
                }
                List<Integer> rows = PlantConfig.rowsOf(sel[0], sel[1]);
                String coord = PlantConfig.rowCoordName();
                if (!args.hasAny()) {
                    logDirect("Rows in this selection (" + PlantConfig.getRowAxis().label() + "):");
                    for (int i = 0; i < rows.size(); i++) {
                        logDirect("  " + i + ": " + coord + "=" + rows.get(i) + " = "
                                + ApricornPlanting.displayName(PlantConfig.getRowType(rows.get(i))));
                    }
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args.getString());
                } catch (NumberFormatException e) {
                    logDirect("Expected a row index (0-" + (rows.size() - 1) + ").");
                    return;
                }
                if (index < 0 || index >= rows.size()) {
                    logDirect("Row " + index + " is outside this selection (0-" + (rows.size() - 1) + ").");
                    return;
                }
                if (!args.hasAny()) {
                    logDirect("Row " + index + " (" + coord + "=" + rows.get(index) + ") = "
                            + ApricornPlanting.displayName(PlantConfig.getRowType(rows.get(index))));
                    return;
                }
                ApricornType type = ApricornPlanting.parse(args.getString());
                if (type == null) {
                    logDirect("Unknown apricorn colour. Try: " + colours());
                    return;
                }
                PlantConfig.setRowType(rows.get(index), type);
                logDirect("Row " + index + " (" + coord + "=" + rows.get(index) + ") = "
                        + ApricornPlanting.displayName(type));
                return;
            }
            default:
                logDirect("Unknown argument '" + sub
                        + "'. Usage: #plant [start|stop|pause|resume|gui|spacing|rows|type|all|row]");
        }
    }

    /** min/max of the current selection, or null (with a chat message) when there is none. */
    private BlockPos[] selection() {
        ISelection sel = baritone.getSelectionManager().getOnlySelection();
        if (sel == null) {
            sel = baritone.getSelectionManager().getLastSelection();
        }
        if (sel == null) {
            logDirect("No selection. Select the field first with #sel pos1 and #sel pos2.");
            return null;
        }
        return new BlockPos[] { sel.min(), sel.max() };
    }

    /** Accepts the axis the rows run along ("x"/"z", the compass names, or "flip"). */
    private static PlantConfig.RowAxis parseAxis(String value) {
        String v = value.toLowerCase();
        return switch (v) {
            case "x", "ew", "east-west", "eastwest", "east_west" -> PlantConfig.RowAxis.EAST_WEST;
            case "z", "ns", "north-south", "northsouth", "north_south" -> PlantConfig.RowAxis.NORTH_SOUTH;
            case "flip", "toggle", "swap" -> PlantConfig.getRowAxis().other();
            default -> null;
        };
    }

    private static String colours() {
        StringBuilder sb = new StringBuilder();
        for (ApricornType t : ApricornPlanting.types()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.name().toLowerCase());
        }
        return sb.toString();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("start", "stop", "pause", "resume", "gui", "spacing", "clearance",
                            "snap", "rows", "type", "all", "row")
                    .filterPrefix(args.peekString()).stream();
        }
        if (args.hasExactly(2)) {
            String first = args.peekString();
            if (first.equalsIgnoreCase("rows") || first.equalsIgnoreCase("dir")
                    || first.equalsIgnoreCase("direction")) {
                return new TabCompleteHelper().append("x", "z", "flip")
                        .filterPrefix(args.peekString(1)).stream();
            }
            if (first.equalsIgnoreCase("type") || first.equalsIgnoreCase("all")) {
                TabCompleteHelper helper = new TabCompleteHelper();
                for (ApricornType t : ApricornPlanting.types()) {
                    helper.append(t.name().toLowerCase());
                }
                return helper.filterPrefix(args.peekString(1)).stream();
            }
        }
        if (args.hasExactly(3) && args.peekString().equalsIgnoreCase("row")) {
            TabCompleteHelper helper = new TabCompleteHelper();
            for (ApricornType t : ApricornPlanting.types()) {
                helper.append(t.name().toLowerCase());
            }
            return helper.filterPrefix(args.peekString(2)).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Plant apricorns on a grid inside the current Baritone selection";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The plant command makes Baritone walk the current selection and plant",
                "apricorns on a fixed grid: one plant every <spacing> blocks on both axes",
                "(default 3, so two blocks of gap around every tree).",
                "",
                "Rows run along X or along Z (#plant rows x|z) and are numbered from the",
                "selection's minimum corner; each row plants its own apricorn colour.",
                "The grid keeps a clearance from walls and from the selection border (default",
                "2 blocks, so the first plant sits on the 3rd block) and skips any spot with a",
                "wall, fence or other tree inside that radius - the trees need room to grow.",
                "Rows do not have to be perfectly straight: with snap 1 (the default) a plant",
                "may shift a block off its grid cell to find usable soil, so a row that bends",
                "round an obstacle or sits on patchy ground still comes out complete.",
                "Nothing is ever broken: only free, plantable soil inside the selection is",
                "used, and the bot plants from the path beside each spot.",
                "",
                "Usage:",
                "> #plant                  start planting in the current selection",
                "> #plant gui              open the planting GUI (also on its own hotkey)",
                "> #plant stop             stop planting",
                "> #plant pause / resume   pause and continue in place",
                "> #plant spacing 3        set the grid spacing in blocks",
                "> #plant clearance 2      blocks kept free around a plant (walls, borders)",
                "> #plant snap 1           how far a plant may shift off the grid to find soil",
                "> #plant rows x           rows run east-west (numbered by z)",
                "> #plant rows z           rows run north-south (numbered by x)",
                "> #plant rows flip        swap the row direction",
                "> #plant type red         set the colour used by rows with no own setting",
                "> #plant all red          set every row of this selection to one colour",
                "> #plant row              list the rows of this selection and their colours",
                "> #plant row 2 blue       set row 2 to blue",
                "",
                "The GUI has a key binding in Options > Controls > Apricorn Harvester,",
                "which opens the same per-row colour list with a Start button."
        );
    }

    @Override
    public List<String> getNames() {
        return List.of("plant", "plantapricorns");
    }
}
