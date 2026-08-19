package com.fx.analyzer;

import java.util.Objects;

import com.fx.core.Currency;

/**
 * equals/hashCode on business fields (id excluded) so a Set can recognise
 * "same money, different receipt number". One row of the `transfer` table.
 */
public class Transfer {
    private final int id;
    private final int fromAccount;
    private final int toAccount;
    private final double amount;
    private final Currency currency;
    private final String executedAt;
    private final String status;

    public Transfer(int id, int fromAccount, int toAccount, double amount,
                    Currency currency, String executedAt, String status) {
        this.id = id; this.fromAccount = fromAccount; this.toAccount = toAccount;
        this.amount = amount; this.currency = currency;
        this.executedAt = executedAt; this.status = status;
    }
    public int getId() { return id; }
    public int getFromAccount() { return fromAccount; }
    public int getToAccount() { return toAccount; }
    public double getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public String getExecutedAt() { return executedAt; }
    public String getStatus() { return status; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transfer t)) return false;
        return fromAccount == t.fromAccount && toAccount == t.toAccount
            && Double.compare(amount, t.amount) == 0 && currency == t.currency
            && executedAt.equals(t.executedAt) && status.equals(t.status);
    }
    @Override public int hashCode() {
        return Objects.hash(fromAccount, toAccount, amount, currency, executedAt, status);
    }

    @Override public String toString() {
        return "#" + id + " " + fromAccount + "->" + toAccount + " "
                + amount + " " + currency + " " + status;
    }
}
