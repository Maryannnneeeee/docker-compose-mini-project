package com.fx.core;

/** Kata 01 solution. */
public class ConversionCalculator {
    public static void main(String[] args) {
        final double FEE_PERCENT = 0.5;                 // constant: final
        double amount = 1250.50;
        double rate = 1.0875;
        char symbol = '$';
        boolean isBusinessCustomer = false;
        int transferCount = 0;

        double converted = amount * rate;
        System.out.printf("%.2f EUR -> %.2f USD%n", amount, converted);

        double fee = converted * FEE_PERCENT / 100;
        System.out.printf("Fee (%.1f%%): %.2f USD%n", FEE_PERCENT, fee);
        System.out.printf("Net: %.2f USD%n", converted - fee);

        System.out.println("post: " + transferCount++ + " now " + transferCount); // 0 then 1
        System.out.println("pre : " + ++transferCount);                            // 2
        int oneMillion = 1_000_000;
        if (isBusinessCustomer) System.out.println(symbol + " business, limit " + oneMillion);
    }
}
