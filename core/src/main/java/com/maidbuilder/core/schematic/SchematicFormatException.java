package com.maidbuilder.core.schematic;

import java.io.IOException;

/** Thrown when a schematic file is malformed or uses an unsupported format version. */
public class SchematicFormatException extends IOException {
    public SchematicFormatException(String message) {
        super(message);
    }
}
