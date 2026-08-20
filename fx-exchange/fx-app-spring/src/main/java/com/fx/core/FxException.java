package com.fx.core;

/**
 * The root of the FX exception family. Checked on purpose:
 * anything that extends this is a business condition the caller must face.
 * The (message, cause) constructor is what makes exception CHAINING possible —
 * wrap a low-level failure (NumberFormatException, IOException) without losing it.
 */
public class FxException extends Exception {

    public FxException(String message) {
        super(message);
    }

    public FxException(String message, Throwable cause) {
        super(message, cause);
    }
}
