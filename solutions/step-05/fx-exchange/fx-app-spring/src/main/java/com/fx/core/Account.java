package com.fx.core;

/**
 * The Java shape of the `account` table: id / owner_name / currency_code / balance map
 * to the fields below. The DB assigns ids in production; a static counter stands in for
 * the in-memory domain model.
 */
public class Account {
    private static int nextId = 1;       // stand-in for AUTO_INCREMENT

    private final int id;                // mirrors account.id
    private final String owner;          // mirrors account.owner_name — no setter
    private final Currency currency;     // mirrors account.currency_code
    private double balance;              // mirrors account.balance

    public Account(String owner, Currency currency, double openingBalance) {
        this.id = nextId++;
        this.owner = owner;
        this.currency = currency;
        this.balance = Math.max(openingBalance, 0);   // clamp rather than throw
    }

    public int getId() { return id; }
    public String getOwner() { return owner; }
    public Currency getCurrency() { return currency; }
    public double getBalance() { return balance; }

    public void deposit(double amount) { if (amount > 0) balance += amount; }

    public void withdraw(double amount) throws InsufficientFundsException {
        if (amount > balance) throw new InsufficientFundsException(amount, balance);
        balance -= amount;
    }

    public String printStatementLine() {
        return String.format("#%d | %s | %s | %.2f", id, owner, currency, balance);
    }
}
