package com.fx.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fx.core.Currency;

/**
 * The robust loader. parseRow wraps ANY parse failure into
 * one CorruptRowException (cause chained); load() catches it per row, counts,
 * reports, continues. A corrupt row skips a row; it never kills the batch.
 */
public class CsvLoader {
    public static List<Transfer> load(String path) throws IOException {
        List<Transfer> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(path));
        int skipped = 0;
        for (int i = 1; i < lines.size(); i++) {           // skip header
            try {
                out.add(parseRow(lines.get(i)));
            } catch (CorruptRowException e) {
                skipped++;
                System.err.println("line " + (i + 1) + ": " + e.getMessage()
                        + " | cause: " + e.getCause());
            }
        }
        if (skipped > 0) System.err.println("Skipped " + skipped + " corrupt rows");
        return out;
    }

    static Transfer parseRow(String line) {
        try {
            String[] f = line.split(",");
            return new Transfer(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), Double.parseDouble(f[3]),
                    Currency.valueOf(f[4]), f[5], f[6]);
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            // NumberFormatException IS an IllegalArgumentException (like Currency.valueOf's),
            // so one alternative covers both — multi-catch types must be unrelated.
            throw new CorruptRowException(line, e);
        }
    }
}
