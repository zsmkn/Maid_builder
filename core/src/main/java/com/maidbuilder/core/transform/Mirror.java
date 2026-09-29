package com.maidbuilder.core.transform;

/**
 * Mirror, named as in vanilla: {@code LEFT_RIGHT} flips the z axis (north/south swap),
 * {@code FRONT_BACK} flips the x axis (east/west swap).
 */
public enum Mirror {
    NONE, LEFT_RIGHT, FRONT_BACK;

    /** Whether this mirror flips the given horizontal direction's axis. */
    public boolean flips(Direction d) {
        return (this == LEFT_RIGHT && d.dz != 0) || (this == FRONT_BACK && d.dx != 0);
    }

    public Direction mirror(Direction d) {
        return flips(d) ? d.opposite() : d;
    }

    /** Rotation that vanilla applies to a block facing {@code d} to emulate this mirror. */
    public Rotation rotationFor(Direction d) {
        return flips(d) ? Rotation.CLOCKWISE_180 : Rotation.NONE;
    }

    /** Mirrors a 16-step rotation value; 0 means facing south. Same result as vanilla. */
    public int mirror(int rotation, int steps) {
        int half = steps / 2;
        int signed = rotation > half ? rotation - steps : rotation;
        return switch (this) {
            case LEFT_RIGHT -> Math.floorMod(half - signed, steps);
            case FRONT_BACK -> Math.floorMod(-signed, steps);
            case NONE -> rotation;
        };
    }
}
