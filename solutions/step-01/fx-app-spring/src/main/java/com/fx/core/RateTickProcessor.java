package com.fx.core;

/** Kata 02 solution. */
public class RateTickProcessor {
    static final double[] RATES = {1.0812, 1.0845, 1.0871, 1.0899, 1.0845, 1.0698, 1.0912, 1.1042, 1.0917};

    public static void main(String[] args) {
        for (int i = 0; i < RATES.length; i++)                       // 1 (index needed -> classic for)
            System.out.println(i + ": " + RATES[i]);

        double[] samples = {500, 5_000, 50_000};                     // 2
        for (double a : samples) {
            double pct;
            if (a < 1_000) pct = 1.0;
            else if (a < 10_000) pct = 0.5;
            else pct = 0.25;
            System.out.printf("amount %.0f -> fee %.2f%n", a, a * pct / 100);
        }

        double min = RATES[0], max = RATES[0];                       // 3
        for (double r : RATES) { if (r < min) min = r; if (r > max) max = r; }
        System.out.println((max - min > 0.05) ? "volatile" : "stable");

        String code = "GBP";                                          // 4
        switch (code) {
            case "EUR" -> System.out.println("Eurozone");
            case "GBP" -> System.out.println("United Kingdom");
            case "JPY" -> System.out.println("Japan");
            default -> System.out.println("Other region");
        }

        int ticks = 0;                                                // 5
        while (ticks < RATES.length && RATES[ticks] <= 1.09) ticks++;
        System.out.println("ticks until >1.09: " + (ticks + 1));      // first exceeding tick counts

        double sum = 0;                                               // 6
        for (double r : RATES) {
            if (r < 1.07) break;
            if (r <= 1.08) continue;
            sum += r;
        }
        System.out.printf("sum: %.4f%n", sum);

        double[] mult = {1, 10, 100};                                 // 7
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++)
                System.out.printf("%10.3f", RATES[i] * mult[j]);
            System.out.println();
        }
    }
}
