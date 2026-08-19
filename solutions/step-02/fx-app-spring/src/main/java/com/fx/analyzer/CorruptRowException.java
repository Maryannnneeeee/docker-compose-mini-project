package com.fx.analyzer;

/**
 * UNCHECKED — corrupt data at a boundary we already guard;
 * callers of parseRow shouldn't be forced into try/catch — load() handles it
 * once). The cause is chained: our words, the parser's evidence.
 */
public class CorruptRowException extends RuntimeException {

    public CorruptRowException(String line, Throwable cause) {
        super("Corrupt row: '" + line + "'", cause);
    }
}
