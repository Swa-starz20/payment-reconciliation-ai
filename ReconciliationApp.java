import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Month-end reconciliation between platform transactions and bank settlements.
 * - Uses integer cents for safe amount comparisons.
 * - Detects missing settlements, duplicates, mismatches, delayed settlements,
 *   settlements without transactions, and refund-without-original.
 */
public class ReconciliationApp {

    // ---------- Models ----------
    static class Transaction {
        final String transactionId;
        final LocalDate transactionDate;
        final String type; // SALE / REFUND
        final long amountCents; // can be negative for refunds
        final String originalTransactionId; // for refunds

        Transaction(String transactionId, LocalDate transactionDate, String type, long amountCents, String originalTransactionId) {
            this.transactionId = transactionId;
            this.transactionDate = transactionDate;
            this.type = type;
            this.amountCents = amountCents;
            this.originalTransactionId = (originalTransactionId == null || originalTransactionId.isBlank()) ? null : originalTransactionId;
        }
    }

    static class Settlement {
        final String settlementId;
        final String transactionId;
        final LocalDate settlementDate;
        final long amountCents;

        Settlement(String settlementId, String transactionId, LocalDate settlementDate, long amountCents) {
            this.settlementId = settlementId;
            this.transactionId = transactionId;
            this.settlementDate = settlementDate;
            this.amountCents = amountCents;
        }
    }

    // ---------- Discrepancy ----------
    enum DiscrepancyType {
        MISSING_SETTLEMENT,
        DELAYED_SETTLEMENT_NEXT_MONTH,
        DUPLICATE_SETTLEMENT_ID,
        DUPLICATE_SETTLEMENT_FOR_TRANSACTION,
        AMOUNT_MISMATCH,
        SETTLEMENT_WITHOUT_TRANSACTION,
        REFUND_WITHOUT_ORIGINAL_TRANSACTION,
        TOTAL_ROUNDING_DISCREPANCY
    }

    static class Discrepancy {
        final DiscrepancyType type;
        final String key;
        final String message;

        Discrepancy(DiscrepancyType type, String key, String message) {
            this.type = type;
            this.key = key;
            this.message = message;
        }

        @Override
        public String toString() {
            return "[" + type + "] " + key + " - " + message;
        }
    }

    // ---------- Parsing helpers ----------
    static long parseCents(String amount) {
        // amount like "100.00" or "-15.00" or "0.01"
        String s = amount.trim();
        boolean neg = s.startsWith("-");
        if (neg) s = s.substring(1);

        String[] parts = s.split("\\.");
        long dollars = Long.parseLong(parts[0]);
        long cents = 0;

        if (parts.length == 2) {
            String frac = parts[1];
            if (frac.length() == 1) frac = frac + "0";
            if (frac.length() > 2) frac = frac.substring(0, 2); // defensive
            cents = Long.parseLong(frac);
        }
        long total = dollars * 100 + cents;
        return neg ? -total : total;
    }

    static String formatCents(long cents) {
        long abs = Math.abs(cents);
        long dollars = abs / 100;
        long rem = abs % 100;
        String s = dollars + "." + (rem < 10 ? "0" + rem : "" + rem);
        return cents < 0 ? "-" + s : s;
    }

    // ---------- Core reconciliation ----------
    static List<Discrepancy> reconcile(List<Transaction> txns, List<Settlement> sets, YearMonth targetMonth) {
        List<Discrepancy> out = new ArrayList<>();

        Map<String, Transaction> txnById = new HashMap<>();
        for (Transaction t : txns) txnById.put(t.transactionId, t);

        Map<String, List<Settlement>> settlementsByTxnId = new HashMap<>();
        Map<String, Integer> settlementIdCounts = new HashMap<>();

        for (Settlement s : sets) {
            settlementsByTxnId.computeIfAbsent(s.transactionId, k -> new ArrayList<>()).add(s);
            settlementIdCounts.put(s.settlementId, settlementIdCounts.getOrDefault(s.settlementId, 0) + 1);
        }

        // Duplicate settlement IDs
        for (Map.Entry<String, Integer> e : settlementIdCounts.entrySet()) {
            if (e.getValue() > 1) {
                out.add(new Discrepancy(
                        DiscrepancyType.DUPLICATE_SETTLEMENT_ID,
                        e.getKey(),
                        "SettlementId appears " + e.getValue() + " times"
                ));
            }
        }

        // Refund integrity
        for (Transaction t : txns) {
            if ("REFUND".equalsIgnoreCase(t.type)) {
                if (t.originalTransactionId == null || !txnById.containsKey(t.originalTransactionId)) {
                    out.add(new Discrepancy(
                            DiscrepancyType.REFUND_WITHOUT_ORIGINAL_TRANSACTION,
                            t.transactionId,
                            "Refund references missing originalTransactionId=" + t.originalTransactionId
                    ));
                }
            }
        }

        // Settlements without transactions
        for (Settlement s : sets) {
            if (!txnById.containsKey(s.transactionId)) {
                out.add(new Discrepancy(
                        DiscrepancyType.SETTLEMENT_WITHOUT_TRANSACTION,
                        s.settlementId,
                        "Settlement references unknown transactionId=" + s.transactionId + " amount=" + formatCents(s.amountCents)
                ));
            }
        }

        // Month cohort: transactions in target month
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate monthEnd = targetMonth.atEndOfMonth();

        long txnTotalCents = 0;
        long settlementTotalCentsPostedInMonthForCohort = 0;

        for (Transaction t : txns) {
            if (t.transactionDate.isBefore(monthStart) || t.transactionDate.isAfter(monthEnd)) continue;

            txnTotalCents += t.amountCents;

            List<Settlement> slist = settlementsByTxnId.getOrDefault(t.transactionId, Collections.emptyList());
            if (slist.isEmpty()) {
                out.add(new Discrepancy(
                        DiscrepancyType.MISSING_SETTLEMENT,
                        t.transactionId,
                        "No settlement found for transaction in month. amount=" + formatCents(t.amountCents) + " date=" + t.transactionDate
                ));
                continue;
            }

            if (slist.size() > 1) {
                out.add(new Discrepancy(
                        DiscrepancyType.DUPLICATE_SETTLEMENT_FOR_TRANSACTION,
                        t.transactionId,
                        "Found " + slist.size() + " settlements for same transactionId"
                ));
            }

            // Pick the earliest settlement for amount comparison (simple heuristic)
            slist.sort(Comparator.comparing(a -> a.settlementDate));
            Settlement chosen = slist.get(0);

            // Delayed settlement
            if (chosen.settlementDate.isAfter(monthEnd)) {
                out.add(new Discrepancy(
                        DiscrepancyType.DELAYED_SETTLEMENT_NEXT_MONTH,
                        t.transactionId,
                        "Transaction date=" + t.transactionDate + " settled on " + chosen.settlementDate
                ));
            } else if (!chosen.settlementDate.isBefore(monthStart) && !chosen.settlementDate.isAfter(monthEnd)) {
                settlementTotalCentsPostedInMonthForCohort += chosen.amountCents;
            }

            // Amount mismatch
            if (t.amountCents != chosen.amountCents) {
                out.add(new Discrepancy(
                        DiscrepancyType.AMOUNT_MISMATCH,
                        t.transactionId,
                        "Txn amount=" + formatCents(t.amountCents) + " vs Settlement(" + chosen.settlementId + ") amount=" + formatCents(chosen.amountCents)
                ));
            }
        }

        long diff = txnTotalCents - settlementTotalCentsPostedInMonthForCohort;
        if (diff != 0) {
            // If small difference, label as possible rounding/aggregation issue at month level.
            if (Math.abs(diff) <= 2) {
                out.add(new Discrepancy(
                        DiscrepancyType.TOTAL_ROUNDING_DISCREPANCY,
                        targetMonth.toString(),
                        "TxnTotal=" + formatCents(txnTotalCents) +
                                " vs SettledInMonthTotal=" + formatCents(settlementTotalCentsPostedInMonthForCohort) +
                                " diff=" + formatCents(diff)
                ));
            }
        }

        return out;
    }

    // ---------- Demo data loading ----------
    static List<Transaction> sampleTransactions() {
        DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE;
        return Arrays.asList(
                new Transaction("T1001", LocalDate.parse("2026-03-30", f), "SALE", parseCents("100.00"), null),
                new Transaction("T1002", LocalDate.parse("2026-03-31", f), "SALE", parseCents("50.00"), null),
                new Transaction("T1003", LocalDate.parse("2026-03-31", f), "SALE", parseCents("10.00"), null),
                new Transaction("T1004", LocalDate.parse("2026-03-15", f), "SALE", parseCents("20.00"), null),
                new Transaction("T2001", LocalDate.parse("2026-03-20", f), "REFUND", parseCents("-15.00"), "T9999"),
                new Transaction("T3001", LocalDate.parse("2026-03-31", f), "SALE", parseCents("0.01"), null),
                new Transaction("T3002", LocalDate.parse("2026-03-31", f), "SALE", parseCents("0.01"), null),
                new Transaction("T3003", LocalDate.parse("2026-03-31", f), "SALE", parseCents("0.01"), null),
                new Transaction("T3004", LocalDate.parse("2026-03-31", f), "SALE", parseCents("0.01"), null),
                new Transaction("T3005", LocalDate.parse("2026-03-31", f), "SALE", parseCents("0.01"), null)
        );
    }

    static List<Settlement> sampleSettlements() {
        DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE;
        return Arrays.asList(
                new Settlement("S9001", "T1001", LocalDate.parse("2026-03-31", f), parseCents("100.00")),
                new Settlement("S9002", "T1002", LocalDate.parse("2026-04-01", f), parseCents("50.00")), // delayed next month
                new Settlement("S9003", "T1003", LocalDate.parse("2026-03-31", f), parseCents("9.99")),  // amount mismatch
                new Settlement("S9004", "T1004", LocalDate.parse("2026-03-16", f), parseCents("20.00")),
                new Settlement("S9004", "T1004", LocalDate.parse("2026-03-16", f), parseCents("20.00")), // duplicate settlementId
                new Settlement("S9999", "T9998", LocalDate.parse("2026-03-31", f), parseCents("12.00")),  // settlement without txn
                new Settlement("S9101", "T3001", LocalDate.parse("2026-03-31", f), parseCents("0.01")),
                new Settlement("S9102", "T3002", LocalDate.parse("2026-03-31", f), parseCents("0.01")),
                new Settlement("S9103", "T3003", LocalDate.parse("2026-03-31", f), parseCents("0.01")),
                new Settlement("S9104", "T3004", LocalDate.parse("2026-03-31", f), parseCents("0.01")),
                new Settlement("S9105", "T3005", LocalDate.parse("2026-03-31", f), parseCents("0.00"))   // totals drift + line mismatch
        );
    }

    public static void main(String[] args) {
        YearMonth target = YearMonth.of(2026, 3);
        List<Discrepancy> d = reconcile(sampleTransactions(), sampleSettlements(), target);

        System.out.println("=== Reconciliation Report for " + target + " ===");
        if (d.isEmpty()) {
            System.out.println("No discrepancies found.");
            return;
        }

        // Group by type for readable output
        Map<DiscrepancyType, List<Discrepancy>> byType = new LinkedHashMap<>();
        for (Discrepancy x : d) byType.computeIfAbsent(x.type, k -> new ArrayList<>()).add(x);

        for (Map.Entry<DiscrepancyType, List<Discrepancy>> e : byType.entrySet()) {
            System.out.println("\n-- " + e.getKey() + " (" + e.getValue().size() + ") --");
            for (Discrepancy x : e.getValue()) System.out.println(x);
        }
    }
}