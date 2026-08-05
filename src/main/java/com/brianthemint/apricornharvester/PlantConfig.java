package com.brianthemint.apricornharvester;

import com.pixelmonmod.pixelmon.enums.items.ApricornType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared planting settings: grid spacing, row direction and the apricorn colour used for each
 * planting row.
 *
 * <p>Rows run along {@link RowAxis#EAST_WEST the X axis} or {@link RowAxis#NORTH_SOUTH the Z axis}
 * and are identified by their coordinate on the other axis, so the same configuration keeps making
 * sense when the selection is re-made over the same farm. Each direction keeps its own row colours.
 * Both the {@code #plant} command and the GUI ({@link ApricornPlantScreen}) read and write this
 * class; the planting process reads it when a run starts.
 */
public final class PlantConfig {

    /** Blocks between two planted apricorns, on both axes. 3 = one plant, two blocks of gap. */
    public static final int DEFAULT_SPACING = 3;
    public static final int MIN_SPACING = 1;
    public static final int MAX_SPACING = 16;

    /** Which way the rows run. A row's colour applies to every plant along that line. */
    public enum RowAxis {
        /** Rows run along X (east-west); a row is one Z coordinate. */
        EAST_WEST("East-West (rows along X)"),
        /** Rows run along Z (north-south); a row is one X coordinate. */
        NORTH_SOUTH("North-South (rows along Z)");

        private final String label;

        RowAxis(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public RowAxis other() {
            return this == EAST_WEST ? NORTH_SOUTH : EAST_WEST;
        }
    }

    /**
     * Blocks of free space kept between a plant and any wall (or the selection border). The
     * default 2 puts the first plant on the 3rd block from a wall, which is the room an apricorn
     * tree needs to grow.
     */
    public static final int DEFAULT_CLEARANCE = 2;
    public static final int MAX_CLEARANCE = 8;

    private static int spacing = DEFAULT_SPACING;
    private static int clearance = DEFAULT_CLEARANCE;
    private static RowAxis rowAxis = RowAxis.EAST_WEST;
    private static ApricornType defaultType = ApricornType.BLACK;
    /**
     * Row coordinate -&gt; colour, one map per direction (the coordinate means Z for east-west rows
     * and X for north-south rows, so the two must not share a map). Rows not listed use
     * {@link #defaultType}.
     */
    private static final Map<RowAxis, Map<Integer, ApricornType>> ROW_TYPES = new HashMap<>();

    static {
        for (RowAxis axis : RowAxis.values()) {
            ROW_TYPES.put(axis, new HashMap<>());
        }
    }

    private PlantConfig() {
    }

    public static int getSpacing() {
        return spacing;
    }

    /** Sets the grid spacing, clamped to [{@value #MIN_SPACING}, {@value #MAX_SPACING}]. */
    public static void setSpacing(int value) {
        setSpacingQuiet(value);
        AddonSettings.notifyPlantSettingsChanged();
    }

    /** Same, without writing the settings file - used while loading it. */
    static void setSpacingQuiet(int value) {
        spacing = Math.max(MIN_SPACING, Math.min(MAX_SPACING, value));
    }

    /** Blocks of clear space kept between a plant and any wall or the selection border. */
    public static int getClearance() {
        return clearance;
    }

    public static void setClearance(int value) {
        setClearanceQuiet(value);
        AddonSettings.notifyPlantSettingsChanged();
    }

    static void setClearanceQuiet(int value) {
        clearance = Math.max(0, Math.min(MAX_CLEARANCE, value));
    }

    public static RowAxis getRowAxis() {
        return rowAxis;
    }

    public static void setRowAxis(RowAxis axis) {
        setRowAxisQuiet(axis);
        AddonSettings.notifyPlantSettingsChanged();
    }

    static void setRowAxisQuiet(RowAxis axis) {
        rowAxis = axis;
    }

    public static ApricornType getDefaultType() {
        return defaultType;
    }

    public static void setDefaultType(ApricornType type) {
        setDefaultTypeQuiet(type);
        AddonSettings.notifyPlantSettingsChanged();
    }

    static void setDefaultTypeQuiet(ApricornType type) {
        defaultType = type;
    }

    /** Colour of the row at the given coordinate (Z for east-west rows, X for north-south). */
    public static ApricornType getRowType(int rowCoord) {
        return ROW_TYPES.get(rowAxis).getOrDefault(rowCoord, defaultType);
    }

    public static void setRowType(int rowCoord, ApricornType type) {
        ROW_TYPES.get(rowAxis).put(rowCoord, type);
    }

    /** Applies one colour to every row of the given selection and makes it the default. */
    public static void setAllRows(BlockPos selMin, BlockPos selMax, ApricornType type) {
        defaultType = type;
        for (int coord : rowsOf(selMin, selMax)) {
            ROW_TYPES.get(rowAxis).put(coord, type);
        }
    }

    /** Forgets the per-row colours of the current direction; every row falls back to the default. */
    public static void clearRows() {
        ROW_TYPES.get(rowAxis).clear();
    }

    /**
     * The coordinates of the planting rows inside a selection: every {@link #getSpacing()} blocks
     * across the rows, starting at the selection's minimum corner. Z values for east-west rows,
     * X values for north-south rows.
     */
    public static List<Integer> rowsOf(BlockPos selMin, BlockPos selMax) {
        return rowAxis == RowAxis.EAST_WEST
                ? steps(selMin.getZ(), selMax.getZ())
                : steps(selMin.getX(), selMax.getX());
    }

    /**
     * The coordinates of the planting spots along a single row (same grid, other axis): X values
     * for east-west rows, Z values for north-south rows.
     */
    public static List<Integer> columnsOf(BlockPos selMin, BlockPos selMax) {
        return rowAxis == RowAxis.EAST_WEST
                ? steps(selMin.getX(), selMax.getX())
                : steps(selMin.getZ(), selMax.getZ());
    }

    /** The letter of the coordinate that identifies a row, for chat and GUI labels. */
    public static String rowCoordName() {
        return rowAxis == RowAxis.EAST_WEST ? "z" : "x";
    }

    /**
     * Grid coordinates between min and max, inset by {@link #getClearance()} on both ends so no
     * plant ends up against the selection border (the trees need room to grow). Returns an empty
     * list when the selection is too narrow for even one plant with that clearance.
     */
    private static List<Integer> steps(int min, int max) {
        List<Integer> values = new ArrayList<>();
        for (int v = min + clearance; v <= max - clearance; v += spacing) {
            values.add(v);
        }
        return values;
    }
}
