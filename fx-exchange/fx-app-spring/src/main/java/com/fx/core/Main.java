package com.fx.core;

/** Katas 03-05 playground — solution. */
public class Main {
    public static void main(String[] args) {
        // Kata 03: references
        Account a = new Account("Amira", Currency.EUR, 1000);
        Account alias = a;                       // same object, two references
        alias.deposit(500);
        System.out.println(a.printStatementLine());        // 1500 — proves no copy

        // Kata 04
        CurrencyConverter conv = new CurrencyConverter(1.0875);
        conv.convert(100);
        conv.convert(100, 1.09);
        conv.convert(new double[]{10, 20});
        System.out.println(conv + " count=" + CurrencyConverter.getConversionCount()); // 4

        // Kata 05
        for (Currency c : Currency.values())
            System.out.println(c + " (" + c.getSymbol() + ") — " + c.getFullName());
        try {
            a.withdraw(999_999);
        } catch (InsufficientFundsException e) {
            System.out.println("Sorry: " + e.getMessage());
        } finally {
            System.out.println("statement printed regardless");
        }
    }
}
