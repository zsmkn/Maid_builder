package com.maidbuilder.core.transform;

import java.util.Locale;

/** The six block faces, named as in Minecraft block state properties. */
public enum Direction {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    public final int dx, dy, dz;
    private final String serialized;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.serialized = name().toLowerCase(Locale.ROOT);
    }

    public String serialized() {
        return serialized;
    }

    public boolean isHorizontal() {
        return dy == 0;
    }

    public Direction opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /** Clockwise when viewed from above. Vertical directions are unchanged. */
    public Direction clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public static Direction byName(String name) {
        for (Direction d : values()) {
            if (d.serialized.equals(name)) return d;
        }
        return null;
    }
}
