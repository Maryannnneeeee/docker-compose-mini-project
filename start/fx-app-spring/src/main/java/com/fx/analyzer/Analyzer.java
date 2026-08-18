package com.fx.analyzer;

import java.util.*;

import com.fx.core.Currency;

/** Aggregations over a batch of transfers, written with plain loops rather than streams. */
public class Analyzer {

    @FunctionalInterface
    interface TransferFilter { boolean test(Transfer t); }

    static List<Transfer> filter(List<Transfer> in, TransferFilter f) {
        List<Transfer> out = new ArrayList<>();
        for (Transfer t : in) if (f.test(t)) out.add(t);
        return out;
    }

    static boolean isFailed(Transfer t) { return "FAILED".equals(t.getStatus()); }  // method-ref target

    public static void main(String[] args) throws Exception {
        List<Transfer> transfers = CsvLoader.load("ops/transactions.csv");
        System.out.println("Loaded: " + transfers.size());               // 1005 (3 corrupt skipped)

        // dedupe — the Set uses OUR equals/hashCode (id excluded)
        Set<Transfer> unique = new LinkedHashSet<>(transfers);
        System.out.println("After dedupe: " + unique.size() + " (removed "
                + (transfers.size() - unique.size()) + ")");             // 1000, removed 5
        List<Transfer> list = new ArrayList<>(unique);

        // group by currency — the busiest lane
        Map<Currency, List<Transfer>> byCurrency = new HashMap<>();
        for (Transfer t : list)
            byCurrency.computeIfAbsent(t.getCurrency(), k -> new ArrayList<>()).add(t);
        Currency busiest = null; int max = -1;
        for (Map.Entry<Currency, List<Transfer>> e : byCurrency.entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue().size());
            if (e.getValue().size() > max) { max = e.getValue().size(); busiest = e.getKey(); }
        }
        System.out.println("Busiest currency: " + busiest + " (" + max + ")");   // SGD (154)

        // sort — the same comparator three ways; only the last survives
        list.sort(Comparator.comparing(Transfer::getAmount).reversed());
        System.out.println("== Top 5 by amount ==");
        for (int i = 0; i < 5; i++) {
            Transfer t = list.get(i);
            System.out.println(String.format("#%-5d | %2d -> %2d | %10.2f %s | %s",
                    t.getId(), t.getFromAccount(), t.getToAccount(),
                    t.getAmount(), t.getCurrency(), t.getStatus()));     // top: #115 19991.47
        }
        StringBuilder header = new StringBuilder();
        String title = "FX Daily Report (" + list.size() + " transfers)";
        header.append(title).append('\n').append("=".repeat(title.length()));
        System.out.println(header);

        // lambdas
        list.removeIf(t -> t.getAmount() < 100.00);
        System.out.println("After removeIf(<100): " + list.size());     // 998
        List<Transfer> failed = filter(list, Analyzer::isFailed);
        System.out.println("FAILED transfers: " + failed.size());       // 88
        Map<String, Double> volume = new TreeMap<>();
        for (Transfer t : list) volume.merge(t.getStatus(), t.getAmount(), Double::sum);
        volume.forEach((status, v) -> System.out.println(status + " -> " + String.format("%.2f", v)));
    }
}
