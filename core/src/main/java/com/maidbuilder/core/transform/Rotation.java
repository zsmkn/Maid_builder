package com.maidbuilder.core.transform;

/** Rotation around the vertical axis, clockwise when viewed from above. Mirrors vanilla {@code Rotation}. */
public enum Rotation {
    NONE(0), CLOCKWISE_90(1), CLOCKWISE_180(2), COUNTERCLOCKWISE_90(3);

    /** Number of clockwise quarter turns. */
    public final int quarterTurns;

    Rotation(int quarterTurns) {
        this.quarterTurns = quarterTurns;
    }

    public static Rotation ofQuarterTurns(int turns) {
        return values()[Math.floorMod(turns, 4)];
    }

    public Rotation then(Rotation other) {
        return ofQuarterTurns(quarterTurns + other.quarterTurns);
    }

    public Direction rotate(Direction d) {
        for (int i = 0; i < quarterTurns; i++) d = d.clockwise();
        return d;
    }

    /** Rotates a 16-step rotation value (signs, banners, skulls). */
    public int rotate(int rotation, int steps) {
        return Math.floorMod(rotation + quarterTurns * steps / 4, steps);
    }
}
