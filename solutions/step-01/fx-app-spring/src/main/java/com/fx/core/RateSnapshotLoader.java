package com.fx.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

/**
 * try-with-resources + exception chaining at a boundary.
 * Low-level failures (IOException, NumberFormatException) are wrapped into ONE
 * checked domain exception (FxException) with the original kept as the cause,
 * so callers deal with "the snapshot is unusable" and debuggers still see why.
 */
public class RateSnapshotLoader {

    /** Reads the rate column of a rates-snapshot CSV (header skipped). */
    public static double[] loadRates(String path) throws FxException {
        try (Scanner in = new Scanner(Path.of(path))) {       // auto-closed on EVERY exit path
            if (in.hasNextLine()) in.nextLine();              // header row
            double[] rates = new double[64];
            int count = 0;
            int lineNo = 1;
            while (in.hasNextLine()) {
                String line = in.nextLine();
                lineNo++;
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                try {
                    rates[count++] = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    // chaining: our words, their evidence — never swallow the cause
                    throw new FxException("Corrupt rate on line " + lineNo + ": '" + parts[1] + "'", e);
                }
            }
            return Arrays.copyOf(rates, count);
        } catch (IOException e) {
            throw new FxException("Cannot read snapshot " + path, e);
        }
    }
}
