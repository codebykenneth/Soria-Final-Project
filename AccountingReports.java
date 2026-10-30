import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.TableModelEvent;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Accounting Reports - ONE FILE (calculations + window + text mode).
 *
 * The four tasks (same as the picture):
 *   Worksheet            - Generate the worksheet using transactions and adjustments.
 *   Trial Balance        - Generate the unadjusted and adjusted trial balances.
 *   Financial Statements - Generate the income statement and balance sheet.
 *   Report Generation    - Connect accounting calculations to the worksheet and financial statements.
 *
 * Needs Java 17 or newer. No other files, no libraries.
 *
 *   javac AccountingReports.java
 *   java AccountingReports                 opens the window (sample data pre-loaded)
 *   java AccountingReports --console       prints everything as text instead
 *
 * Optional (window or text mode):
 *   --accounts accounts.csv --transactions transactions.csv --adjustments adjustments.csv
 * Text mode only:
 *   --export out_folder      saves the reports as CSV + report.txt
 *
 * CSV formats (first line is a header):
 *   accounts.csv      number,name,type          (Asset|Liability|Equity|Revenue|Expense)
 *   transactions.csv  date,description,account,debit,credit
 *   adjustments.csv   ref,description,account,debit,credit
 */
public class AccountingReports {

    static final BigDecimal ZERO = BigDecimal.ZERO;
    static final List<String> TYPES = List.of("Asset", "Liability", "Equity", "Revenue", "Expense");

    // ==================================================================
    //  DATA MODEL
    // ==================================================================
    record Account(int number, String name, String type) {}

    record Entry(String ref, String description, String account, BigDecimal debit, BigDecimal credit) {}

    record Line(String name, BigDecimal amount) {}

    record TbLine(Account account, BigDecimal debit, BigDecimal credit) {}

    record TrialBalance(List<TbLine> lines, BigDecimal totalDr, BigDecimal totalCr) {
        boolean balanced() { return totalDr.compareTo(totalCr) == 0; }
    }

    record IncomeStatement(List<Line> revenues, List<Line> expenses,
                           BigDecimal totalRevenues, BigDecimal totalExpenses, BigDecimal netIncome) {}

    record BalanceSheet(List<Line> assets, List<Line> liabilities, List<Line> equity,
                        BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal totalEquity) {
        boolean balanced() { return totalAssets.compareTo(totalLiabilities.add(totalEquity)) == 0; }
    }

    record Check(String name, boolean ok) {}

    static class WorksheetRow {
        final Account account;
        BigDecimal unadjDr = ZERO, unadjCr = ZERO;
        BigDecimal adjDr = ZERO, adjCr = ZERO;
        BigDecimal adjTbDr = ZERO, adjTbCr = ZERO;
        BigDecimal isDr = ZERO, isCr = ZERO;
        BigDecimal bsDr = ZERO, bsCr = ZERO;

        WorksheetRow(Account account) { this.account = account; }

        /** Order: unadj Dr/Cr, adj Dr/Cr, adjusted TB Dr/Cr, income stmt Dr/Cr, balance sheet Dr/Cr. */
        BigDecimal[] values() {
            return new BigDecimal[]{unadjDr, unadjCr, adjDr, adjCr, adjTbDr, adjTbCr,
                    isDr, isCr, bsDr, bsCr};
        }
    }

    record Report(List<WorksheetRow> rows, BigDecimal[] totals, BigDecimal[] netIncomeRow,
                  BigDecimal[] finalTotals, TrialBalance unadjusted, TrialBalance adjusted,
                  IncomeStatement incomeStatement, BalanceSheet balanceSheet, List<Check> checks) {}

    // ==================================================================
    //  HELPERS
    // ==================================================================
    /** Turns a net (debit - credit) amount into {debit, credit}. */
    static BigDecimal[] splitBalance(BigDecimal net) {
        return net.signum() >= 0
                ? new BigDecimal[]{net, ZERO}
                : new BigDecimal[]{ZERO, net.negate()};
    }

    /** Always 1,234.56 style, whatever the computer's language/region is. */
    static String money(BigDecimal x) {
        if (x.signum() == 0) return "-";
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return x.signum() < 0 ? "(" + df.format(x.negate()) + ")" : df.format(x);
    }

    static BigDecimal sum(List<Line> lines) {
        BigDecimal t = ZERO;
        for (Line l : lines) t = t.add(l.amount());
        return t;
    }

    static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, ZERO);
        return a;
    }

    static boolean same(BigDecimal a, BigDecimal b) { return a.compareTo(b) == 0; }

    // ==================================================================
    //  VALIDATION (clear messages instead of wrong numbers)
    // ==================================================================
    static void validateAccounts(List<Account> accounts) {
        if (accounts.isEmpty()) throw new IllegalArgumentException("Add at least one account");
        Set<String> names = new HashSet<>();
        Set<Integer> numbers = new HashSet<>();
        for (Account a : accounts) {
            if (a.name().isBlank()) throw new IllegalArgumentException("Every account needs a name");
            if (!TYPES.contains(a.type())) {
                throw new IllegalArgumentException("Account '" + a.name() + "' needs a type (" + String.join("/", TYPES) + ")");
            }
            if (!names.add(a.name())) throw new IllegalArgumentException("Duplicate account name '" + a.name() + "'");
            if (!numbers.add(a.number())) throw new IllegalArgumentException("Duplicate account number " + a.number());
        }
    }

    static void validateEntries(List<Entry> entries, String label, Set<String> known) {
        BigDecimal dr = ZERO, cr = ZERO;
        for (Entry e : entries) {
            String who = label + " entry '" + (e.description().isBlank() ? e.ref() : e.description()) + "'";
            if (e.account().isBlank()) throw new IllegalArgumentException(who + " has no account");
            if (!known.contains(e.account())) {
                throw new IllegalArgumentException("Unknown account '" + e.account() + "' in " + who);
            }
            if (e.debit().signum() < 0 || e.credit().signum() < 0) {
                throw new IllegalArgumentException(who + " has a negative amount");
            }
            if (e.debit().signum() != 0 && e.credit().signum() != 0) {
                throw new IllegalArgumentException(who + " has both a debit and a credit - use two lines");
            }
            dr = dr.add(e.debit());
            cr = cr.add(e.credit());
        }
        if (!same(dr, cr)) {
            throw new IllegalArgumentException(label + " are out of balance: debits " + money(dr)
                    + " but credits " + money(cr));
        }
    }

    // ==================================================================
    //  TASK 1 - WORKSHEET (from transactions and adjustments)
    // ==================================================================
    static List<WorksheetRow> generateWorksheet(List<Account> accounts,
                                                List<Entry> transactions,
                                                List<Entry> adjustments) {
        validateAccounts(accounts);
        Set<String> known = new HashSet<>();
        for (Account a : accounts) known.add(a.name());
        validateEntries(transactions, "Transactions", known);
        validateEntries(adjustments, "Adjustments", known);

        List<WorksheetRow> rows = new ArrayList<>();
        for (Account acct : accounts) {
            WorksheetRow r = new WorksheetRow(acct);

            BigDecimal net = ZERO;
            for (Entry e : transactions) {
                if (e.account().equals(acct.name())) net = net.add(e.debit()).subtract(e.credit());
            }
            BigDecimal[] u = splitBalance(net);
            r.unadjDr = u[0];
            r.unadjCr = u[1];

            for (Entry e : adjustments) {
                if (e.account().equals(acct.name())) {
                    r.adjDr = r.adjDr.add(e.debit());
                    r.adjCr = r.adjCr.add(e.credit());
                }
            }

            BigDecimal[] adj = splitBalance(r.unadjDr.subtract(r.unadjCr).add(r.adjDr).subtract(r.adjCr));
            r.adjTbDr = adj[0];
            r.adjTbCr = adj[1];

            if (acct.type().equals("Revenue") || acct.type().equals("Expense")) {
                r.isDr = r.adjTbDr;
                r.isCr = r.adjTbCr;
            } else {
                r.bsDr = r.adjTbDr;
                r.bsCr = r.adjTbCr;
            }
            rows.add(r);
        }
        return rows;
    }

    static BigDecimal[] worksheetTotals(List<WorksheetRow> rows) {
        BigDecimal[] totals = zeros(10);
        for (WorksheetRow r : rows) {
            BigDecimal[] v = r.values();
            for (int i = 0; i < 10; i++) totals[i] = totals[i].add(v[i]);
        }
        return totals;
    }

    /** The "Net Income (Loss)" line of a worksheet: balances the Income Statement and Balance Sheet columns. */
    static BigDecimal[] netIncomeRow(BigDecimal[] t) {
        BigDecimal[] r = zeros(10);
        BigDecimal income = t[7].subtract(t[6]);             // income-statement credits - debits
        if (income.signum() >= 0) {
            r[6] = income;                                    // debit side of income statement
            r[9] = income;                                    // credit side of balance sheet (equity grows)
        } else {
            r[7] = income.negate();
            r[8] = income.negate();
        }
        return r;
    }

    // ==================================================================
    //  TASK 2 - UNADJUSTED AND ADJUSTED TRIAL BALANCES
    // ==================================================================
    static TrialBalance buildTrialBalance(List<WorksheetRow> rows, boolean adjusted) {
        List<TbLine> lines = new ArrayList<>();
        BigDecimal totalDr = ZERO, totalCr = ZERO;
        for (WorksheetRow r : rows) {
            BigDecimal dr = adjusted ? r.adjTbDr : r.unadjDr;
            BigDecimal cr = adjusted ? r.adjTbCr : r.unadjCr;
            if (dr.signum() != 0 || cr.signum() != 0) {
                lines.add(new TbLine(r.account, dr, cr));
                totalDr = totalDr.add(dr);
                totalCr = totalCr.add(cr);
            }
        }
        return new TrialBalance(lines, totalDr, totalCr);
    }

    // ==================================================================
    //  TASK 3 - INCOME STATEMENT AND BALANCE SHEET
    // ==================================================================
    static IncomeStatement buildIncomeStatement(List<WorksheetRow> rows) {
        List<Line> revenues = new ArrayList<>(), expenses = new ArrayList<>();
        for (WorksheetRow r : rows) {
            if (r.account.type().equals("Revenue")) revenues.add(new Line(r.account.name(), r.isCr.subtract(r.isDr)));
            if (r.account.type().equals("Expense")) expenses.add(new Line(r.account.name(), r.isDr.subtract(r.isCr)));
        }
        BigDecimal rev = sum(revenues), exp = sum(expenses);
        return new IncomeStatement(revenues, expenses, rev, exp, rev.subtract(exp));
    }

    static BalanceSheet buildBalanceSheet(List<WorksheetRow> rows, BigDecimal netIncome) {
        List<Line> assets = new ArrayList<>(), liabilities = new ArrayList<>(), equity = new ArrayList<>();
        for (WorksheetRow r : rows) {
            String n = r.account.name();
            switch (r.account.type()) {
                case "Asset" -> assets.add(new Line(n, r.bsDr.subtract(r.bsCr)));
                case "Liability" -> liabilities.add(new Line(n, r.bsCr.subtract(r.bsDr)));
                case "Equity" -> equity.add(new Line(n, r.bsCr.subtract(r.bsDr)));   // drawings show as negative
                default -> { }
            }
        }
        equity.add(new Line("Net Income (Loss)", netIncome));
        return new BalanceSheet(assets, liabilities, equity, sum(assets), sum(liabilities), sum(equity));
    }

    // ==================================================================
    //  TASK 4 - REPORT GENERATION (connects everything + cross-checks)
    // ==================================================================
    static Report generateReport(List<Account> accounts, List<Entry> transactions, List<Entry> adjustments) {
        List<WorksheetRow> rows = generateWorksheet(accounts, transactions, adjustments);
        BigDecimal[] totals = worksheetTotals(rows);
        BigDecimal[] ni = netIncomeRow(totals);
        BigDecimal[] fin = new BigDecimal[10];
        for (int i = 0; i < 10; i++) fin[i] = totals[i].add(ni[i]);

        TrialBalance unadj = buildTrialBalance(rows, false);
        TrialBalance adjusted = buildTrialBalance(rows, true);
        IncomeStatement is = buildIncomeStatement(rows);
        BalanceSheet bs = buildBalanceSheet(rows, is.netIncome());

        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Worksheet: unadjusted columns balance", same(totals[0], totals[1])));
        checks.add(new Check("Worksheet: adjustments columns balance", same(totals[2], totals[3])));
        checks.add(new Check("Worksheet: adjusted columns balance", same(totals[4], totals[5])));
        checks.add(new Check("Worksheet: income statement columns balance", same(fin[6], fin[7])));
        checks.add(new Check("Worksheet: balance sheet columns balance", same(fin[8], fin[9])));
        checks.add(new Check("Unadjusted trial balance: debits = credits", unadj.balanced()));
        checks.add(new Check("Adjusted trial balance: debits = credits", adjusted.balanced()));
        checks.add(new Check("Income statement net income = worksheet net income",
                same(is.netIncome(), totals[7].subtract(totals[6]))));
        checks.add(new Check("Balance sheet: assets = liabilities + equity", bs.balanced()));

        return new Report(rows, totals, ni, fin, unadj, adjusted, is, bs, checks);
    }

    static boolean allOk(Report rep, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) if (!rep.checks().get(i).ok()) return false;
        return true;
    }

    // ==================================================================
    //  TEXT REPORT (used by text mode, the Report Generation tab and export)
    // ==================================================================
    static void f(StringBuilder sb, String fmt, Object... args) {
        sb.append(String.format(Locale.US, fmt, args));
    }

    static void appendLines(StringBuilder sb, List<Line> lines) {
        for (Line l : lines) f(sb, "  %-38s%14s%n", l.name(), money(l.amount()));
    }

    static void appendTrialBalance(StringBuilder sb, String title, TrialBalance tb) {
        f(sb, "%n%s%n", title);
        f(sb, "%-40s%14s%14s%n", "Account", "Debit", "Credit");
        sb.append("-".repeat(68)).append('\n');
        for (TbLine l : tb.lines()) f(sb, "%-40s%14s%14s%n", l.account().name(), money(l.debit()), money(l.credit()));
        sb.append("-".repeat(68)).append('\n');
        f(sb, "%-40s%14s%14s   %s%n", "Totals", money(tb.totalDr()), money(tb.totalCr()),
                tb.balanced() ? "Balanced" : "OUT OF BALANCE");
    }

    static String buildTextReport(Report rep) {
        StringBuilder sb = new StringBuilder();
        String row = "%-5s%-37s" + "%13s".repeat(10) + "%n";
        String rule = "-".repeat(172);

        sb.append("WORKSHEET\n");
        f(sb, row, "Acct", "Account", "UnadjDr", "UnadjCr", "AdjDr", "AdjCr", "AdjTBDr", "AdjTBCr",
                "ISDr", "ISCr", "BSDr", "BSCr");
        sb.append(rule).append('\n');
        for (WorksheetRow r : rep.rows()) {
            List<Object> c = new ArrayList<>();
            c.add(String.valueOf(r.account.number()));
            c.add(r.account.name().length() > 36 ? r.account.name().substring(0, 36) : r.account.name());
            for (BigDecimal v : r.values()) c.add(money(v));
            f(sb, row, c.toArray());
        }
        sb.append(rule).append('\n');
        String[][] foot = {{"Totals"}, {"Net Income (Loss)"}, {"Final Totals"}};
        BigDecimal[][] vals = {rep.totals(), rep.netIncomeRow(), rep.finalTotals()};
        for (int k = 0; k < 3; k++) {
            List<Object> c = new ArrayList<>();
            c.add("");
            c.add(foot[k][0]);
            for (BigDecimal v : vals[k]) c.add(money(v));
            f(sb, row, c.toArray());
            if (k == 1) sb.append(rule).append('\n');
        }

        appendTrialBalance(sb, "UNADJUSTED TRIAL BALANCE", rep.unadjusted());
        appendTrialBalance(sb, "ADJUSTED TRIAL BALANCE", rep.adjusted());

        IncomeStatement is = rep.incomeStatement();
        BalanceSheet bs = rep.balanceSheet();
        sb.append("\nINCOME STATEMENT\nRevenues\n");
        appendLines(sb, is.revenues());
        f(sb, "%-40s%14s%n", "Total Revenues", money(is.totalRevenues()));
        sb.append("Expenses\n");
        appendLines(sb, is.expenses());
        f(sb, "%-40s%14s%n", "Total Expenses", money(is.totalExpenses()));
        f(sb, "%-40s%14s%n", "Net Income (Loss)", money(is.netIncome()));

        sb.append("\nBALANCE SHEET\nAssets\n");
        appendLines(sb, bs.assets());
        f(sb, "%-40s%14s%n", "Total Assets", money(bs.totalAssets()));
        sb.append("Liabilities\n");
        appendLines(sb, bs.liabilities());
        f(sb, "%-40s%14s%n", "Total Liabilities", money(bs.totalLiabilities()));
        sb.append("Owner's Equity\n");
        appendLines(sb, bs.equity());
        f(sb, "%-40s%14s%n", "Total Owner's Equity", money(bs.totalEquity()));
        f(sb, "%-40s%14s   %s%n", "Total Liabilities + Equity", money(bs.totalLiabilities().add(bs.totalEquity())),
                bs.balanced() ? "Balanced" : "OUT OF BALANCE");

        sb.append("\nREPORT GENERATION - CHECKS\n");
        int pass = 0;
        for (Check c : rep.checks()) {
            f(sb, "  [%s] %s%n", c.ok() ? "PASS" : "FAIL", c.name());
            if (c.ok()) pass++;
        }
        f(sb, "%d of %d checks passed.%n", pass, rep.checks().size());
        return sb.toString();
    }

    // ==================================================================
    //  CSV EXPORT / IMPORT
    // ==================================================================
    static String csv(String s) {
        return (s.contains(",") || s.contains("\"") || s.contains("\n"))
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static PrintWriter writer(Path file) throws IOException {
        return new PrintWriter(file.toFile(), StandardCharsets.UTF_8);
    }

    static void exportCsv(Report rep, String folder) throws IOException {
        Path dir = Paths.get(folder);
        Files.createDirectories(dir);

        try (PrintWriter w = writer(dir.resolve("worksheet.csv"))) {
            w.println("number,account,type,unadj_dr,unadj_cr,adj_dr,adj_cr,adjtb_dr,adjtb_cr,is_dr,is_cr,bs_dr,bs_cr");
            for (WorksheetRow r : rep.rows()) {
                StringBuilder sb = new StringBuilder();
                sb.append(r.account.number()).append(',').append(csv(r.account.name())).append(',').append(r.account.type());
                for (BigDecimal v : r.values()) sb.append(',').append(v.toPlainString());
                w.println(sb);
            }
            String[] names = {"", "Totals", "", "Net Income (Loss)", "", "Final Totals"};
            BigDecimal[][] vals = {rep.totals(), rep.netIncomeRow(), rep.finalTotals()};
            for (int k = 0; k < 3; k++) {
                StringBuilder sb = new StringBuilder(",").append(names[k * 2 + 1]).append(',');
                for (BigDecimal v : vals[k]) sb.append(',').append(v.toPlainString());
                w.println(sb);
            }
        }
        for (boolean adjusted : new boolean[]{false, true}) {
            TrialBalance tb = adjusted ? rep.adjusted() : rep.unadjusted();
            try (PrintWriter w = writer(dir.resolve(adjusted ? "trial_balance_adjusted.csv" : "trial_balance_unadjusted.csv"))) {
                w.println("number,account,debit,credit");
                for (TbLine l : tb.lines()) {
                    w.println(l.account().number() + "," + csv(l.account().name()) + ","
                            + l.debit().toPlainString() + "," + l.credit().toPlainString());
                }
            }
        }
        try (PrintWriter w = writer(dir.resolve("financial_statements.csv"))) {
            w.println("statement,section,line,amount");
            IncomeStatement is = rep.incomeStatement();
            BalanceSheet bs = rep.balanceSheet();
            for (Line l : is.revenues()) w.println("Income Statement,Revenues," + csv(l.name()) + "," + l.amount().toPlainString());
            for (Line l : is.expenses()) w.println("Income Statement,Expenses," + csv(l.name()) + "," + l.amount().toPlainString());
            w.println("Income Statement,,Net Income (Loss)," + is.netIncome().toPlainString());
            for (Line l : bs.assets()) w.println("Balance Sheet,Assets," + csv(l.name()) + "," + l.amount().toPlainString());
            for (Line l : bs.liabilities()) w.println("Balance Sheet,Liabilities," + csv(l.name()) + "," + l.amount().toPlainString());
            for (Line l : bs.equity()) w.println("Balance Sheet,Equity," + csv(l.name()) + "," + l.amount().toPlainString());
        }
        try (PrintWriter w = writer(dir.resolve("report.txt"))) {
            w.print(buildTextReport(rep));
        }
    }

    /** Minimal CSV line parser that supports quoted fields. */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** Reads a CSV file (UTF-8 or Excel's older encoding), skips the header, pads short rows. */
    static List<List<String>> readCsv(String path, int columns) throws IOException {
        Path p = Paths.get(path);
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            lines = Files.readAllLines(p, StandardCharsets.ISO_8859_1);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {                 // line 0 is the header
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> fields = new ArrayList<>(parseCsvLine(line));
            while (fields.size() < columns) fields.add("");
            fields.add(String.valueOf(i + 1));                   // keep the line number for error messages
            rows.add(fields);
        }
        return rows;
    }

    static BigDecimal amount(String s, String file, String line) {
        String t = s == null ? "" : s.replace(",", "").trim();
        if (t.isEmpty()) return ZERO;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(file + " line " + line + ": '" + s + "' is not a valid amount");
        }
    }

    static List<Account> loadAccounts(String path) throws IOException {
        String file = Paths.get(path).getFileName().toString();
        List<Account> out = new ArrayList<>();
        for (List<String> f : readCsv(path, 3)) {
            String line = f.get(3);
            String type = null;
            for (String t : TYPES) if (t.equalsIgnoreCase(f.get(2).trim())) type = t;
            if (type == null) {
                throw new IllegalArgumentException(file + " line " + line + ": type '" + f.get(2).trim()
                        + "' must be one of " + String.join(", ", TYPES));
            }
            int number;
            try {
                number = Integer.parseInt(f.get(0).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(file + " line " + line + ": '" + f.get(0).trim() + "' is not a valid account number");
            }
            out.add(new Account(number, f.get(1).trim(), type));
        }
        return out;
    }

    static List<Entry> loadEntries(String path) throws IOException {
        String file = Paths.get(path).getFileName().toString();
        List<Entry> out = new ArrayList<>();
        for (List<String> f : readCsv(path, 5)) {
            String line = f.get(5);
            out.add(new Entry(f.get(0).trim(), f.get(1).trim(), f.get(2).trim(),
                    amount(f.get(3), file, line), amount(f.get(4), file, line)));
        }
        return out;
    }

    // ==================================================================
    //  SAMPLE DATA
    // ==================================================================
    static void pair(List<Entry> list, String ref, String desc, String drAcct, String crAcct, String amt) {
        BigDecimal a = new BigDecimal(amt);
        list.add(new Entry(ref, desc, drAcct, a, ZERO));
        list.add(new Entry(ref, desc, crAcct, ZERO, a));
    }

    static List<Account> sampleAccounts() {
        return List.of(
                new Account(1010, "Cash", "Asset"),
                new Account(1100, "Accounts Receivable", "Asset"),
                new Account(1200, "Supplies", "Asset"),
                new Account(1300, "Prepaid Insurance", "Asset"),
                new Account(1500, "Equipment", "Asset"),
                new Account(1510, "Accumulated Depreciation - Equipment", "Asset"),
                new Account(2010, "Accounts Payable", "Liability"),
                new Account(2100, "Unearned Revenue", "Liability"),
                new Account(2200, "Salaries Payable", "Liability"),
                new Account(3010, "Owner's Capital", "Equity"),
                new Account(3020, "Owner's Drawings", "Equity"),
                new Account(4010, "Service Revenue", "Revenue"),
                new Account(5010, "Salaries Expense", "Expense"),
                new Account(5020, "Rent Expense", "Expense"),
                new Account(5030, "Supplies Expense", "Expense"),
                new Account(5040, "Insurance Expense", "Expense"),
                new Account(5050, "Depreciation Expense", "Expense"),
                new Account(5060, "Utilities Expense", "Expense"));
    }

    static List<Entry> sampleTransactions() {
        List<Entry> t = new ArrayList<>();
        pair(t, "2026-01-01", "Owner invested cash", "Cash", "Owner's Capital", "50000");
        pair(t, "2026-01-02", "Paid 12-month insurance", "Prepaid Insurance", "Cash", "2400");
        pair(t, "2026-01-03", "Bought supplies on account", "Supplies", "Accounts Payable", "1500");
        pair(t, "2026-01-05", "Bought equipment for cash", "Equipment", "Cash", "24000");
        pair(t, "2026-01-08", "Services on account", "Accounts Receivable", "Service Revenue", "9000");
        pair(t, "2026-01-10", "Services for cash", "Cash", "Service Revenue", "6500");
        pair(t, "2026-01-12", "Customer advance payment", "Cash", "Unearned Revenue", "3000");
        pair(t, "2026-01-15", "Paid office rent", "Rent Expense", "Cash", "2000");
        pair(t, "2026-01-20", "Collected from customers", "Cash", "Accounts Receivable", "4000");
        pair(t, "2026-01-25", "Paid salaries", "Salaries Expense", "Cash", "3500");
        pair(t, "2026-01-28", "Paid utilities", "Utilities Expense", "Cash", "650");
        pair(t, "2026-01-31", "Owner withdrawal", "Owner's Drawings", "Cash", "1000");
        return t;
    }

    static List<Entry> sampleAdjustments() {
        List<Entry> a = new ArrayList<>();
        pair(a, "A1", "Supplies used", "Supplies Expense", "Supplies", "900");
        pair(a, "A2", "Insurance expired", "Insurance Expense", "Prepaid Insurance", "200");
        pair(a, "A3", "Depreciation", "Depreciation Expense", "Accumulated Depreciation - Equipment", "400");
        pair(a, "A4", "Advance now earned", "Unearned Revenue", "Service Revenue", "1200");
        pair(a, "A5", "Accrued salaries", "Salaries Expense", "Salaries Payable", "800");
        return a;
    }

    // ==================================================================
    //  WINDOW  (look copied from the pictures: green bars, orange header,
    //           white rows with thin grey lines)
    // ==================================================================
    static class AppPanel extends JPanel {

        // ---- colours / fonts ------------------------------------------------
        static final Color GREEN = new Color(0xB6, 0xD7, 0xA8);
        static final Color ORANGE = new Color(0xFF, 0x99, 0x00);
        static final Color ORANGE_HOVER = new Color(0xE6, 0x8A, 0x00);
        static final Color GRID = new Color(0xD9, 0xD9, 0xD9);
        static final Color TOTAL_BG = new Color(0xF3, 0xF3, 0xF3);
        static final Color SELECT = new Color(0xFF, 0xE5, 0xB4);
        static final Color TEXT = Color.BLACK;
        static final Color MUTED = new Color(0x99, 0x99, 0x99);
        static final Color GOOD = new Color(0x1B, 0x5E, 0x20);
        static final Color BAD = new Color(0xB7, 0x1C, 0x1C);

        static final String FAMILY = pickFamily("Roboto", "Segoe UI", "Helvetica Neue", "Arial", Font.SANS_SERIF);
        static final Font FONT = new Font(FAMILY, Font.PLAIN, 14);
        static final Font FONT_BOLD = new Font(FAMILY, Font.BOLD, 14);

        static String pickFamily(String... wanted) {
            Set<String> have = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String w : wanted) if (have.contains(w)) return w;
            return Font.SANS_SERIF;
        }

        // ---- tab names (first four = the four rows of the picture) ----------
        static final String T_HOME = "Accounting Reports";
        static final String T_ACCOUNTS = "Accounts";
        static final String T_TX = "Transactions";
        static final String T_ADJ = "Adjustments";
        static final String T_WS = "Worksheet";
        static final String T_TB = "Trial Balance";
        static final String T_FS = "Financial Statements";
        static final String T_RG = "Report Generation";

        static final String[][] HOME_ROWS = {
                {T_WS, "Generate the worksheet using transactions and adjustments."},
                {T_TB, "Generate the unadjusted and adjusted trial balances."},
                {T_FS, "Generate the income statement and balance sheet."},
                {T_RG, "Connect accounting calculations to the worksheet and financial statements."}};

        // ---- state ----------------------------------------------------------
        private final DefaultTableModel accountsModel =
                new DefaultTableModel(new String[]{"Acct No.", "Account Name", "Type"}, 0);
        private final DefaultTableModel txModel =
                new DefaultTableModel(new String[]{"Date", "Description", "Account", "Debit", "Credit"}, 0);
        private final DefaultTableModel adjModel =
                new DefaultTableModel(new String[]{"Ref", "Description", "Account", "Debit", "Credit"}, 0);
        private final DefaultTableModel homeModel = new DefaultTableModel(
                new String[]{"Accounting Reports", "Task", "Status"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        private final DefaultComboBoxModel<String> accountNames = new DefaultComboBoxModel<>();
        private final List<String> lastNames = new ArrayList<>();

        private final JTabbedPane tabs = new JTabbedPane();
        private final JLabel status = new JLabel(" ");
        private final JPanel statusBar = new JPanel(new BorderLayout());
        private final JTable accountsTable, txTable, adjTable;
        private final JPanel worksheetBox = box(), trialBox = box(), statementsBox = box(), reportBox = box();

        private Report current;
        private boolean loading = false;
        private boolean recalcPending = false;

        AppPanel() {
            super(new BorderLayout());
            setBackground(Color.WHITE);

            accountsTable = styledTable(accountsModel, 99, null);
            txTable = styledTable(txModel, 3, null);
            adjTable = styledTable(adjModel, 3, null);
            setUpEditors();

            // keep names / drop-downs / reports in sync with every edit
            accountsModel.addTableModelListener(e -> {
                if (loading) return;
                if (e.getType() == TableModelEvent.UPDATE && e.getColumn() == 1
                        && e.getFirstRow() >= 0 && e.getFirstRow() == e.getLastRow()
                        && accountsModel.getRowCount() == lastNames.size()) {
                    String oldName = lastNames.get(e.getFirstRow());
                    String newName = str(accountsModel.getValueAt(e.getFirstRow(), 1)).trim();
                    if (!oldName.isBlank() && !newName.isBlank() && !oldName.equals(newName)) {
                        renameAccount(oldName, newName);
                    }
                }
                syncAccountNames();
                scheduleRecalc();
            });
            txModel.addTableModelListener(e -> { if (!loading) scheduleRecalc(); });
            adjModel.addTableModelListener(e -> { if (!loading) scheduleRecalc(); });

            add(buildTopBar(), BorderLayout.NORTH);

            tabs.setUI(new FlatTabsUI());
            tabs.setFont(FONT_BOLD);
            tabs.setForeground(TEXT);
            tabs.setBackground(Color.WHITE);
            tabs.addTab(T_HOME, homeTab());
            tabs.addTab(T_ACCOUNTS, inputTab("Rename accounts or change numbers/types. Renaming updates every entry automatically.",
                    accountsModel, accountsTable, new Object[]{"", "", "Asset"}));
            tabs.addTab(T_TX, inputTab("Double-click a cell (or just type). Every debit needs a matching credit.",
                    txModel, txTable, new Object[]{"", "", "", "", ""}));
            tabs.addTab(T_ADJ, inputTab("Adjusting entries. Debits and credits must balance here too.",
                    adjModel, adjTable, new Object[]{"", "", "", "", ""}));
            tabs.addTab(T_WS, worksheetBox);
            tabs.addTab(T_TB, trialBox);
            tabs.addTab(T_FS, statementsBox);
            tabs.addTab(T_RG, reportBox);
            add(tabs, BorderLayout.CENTER);

            status.setFont(FONT);
            status.setBorder(BorderFactory.createEmptyBorder(9, 20, 9, 20));
            statusBar.setBackground(GREEN);
            statusBar.add(status, BorderLayout.CENTER);
            add(statusBar, BorderLayout.SOUTH);
        }

        private static JPanel box() {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            return p;
        }

        private static void setBox(JPanel box, JComponent content) {
            box.removeAll();
            box.add(content, BorderLayout.CENTER);
            box.revalidate();
            box.repaint();
        }

        // ================================================================
        //  Top bar (green, like the picture) + buttons
        // ================================================================
        private JComponent buildTopBar() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(GREEN);
            bar.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

            JLabel title = new JLabel("Accounting Reports");
            title.setFont(new Font(FAMILY, Font.BOLD, 18));
            title.setForeground(TEXT);

            FlatButton sample = new FlatButton("Load Sample Data", Color.WHITE, new Color(0xF1, 0xF8, 0xE9), TEXT);
            FlatButton load = new FlatButton("Load CSV Files...", Color.WHITE, new Color(0xF1, 0xF8, 0xE9), TEXT);
            FlatButton export = new FlatButton("Export...", ORANGE, ORANGE_HOVER, TEXT);
            sample.addActionListener(e -> fill(sampleAccounts(), sampleTransactions(), sampleAdjustments()));
            load.addActionListener(e -> loadFromCsv());
            export.addActionListener(e -> export());

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(sample);
            right.add(load);
            right.add(export);

            bar.add(title, BorderLayout.WEST);
            bar.add(right, BorderLayout.EAST);
            return bar;
        }

        // ================================================================
        //  Home tab = the table from the pictures
        // ================================================================
        private JComponent homeTab() {
            for (String[] r : HOME_ROWS) homeModel.addRow(new Object[]{r[0], r[1], ""});
            JTable table = styledTable(homeModel, 99, null);
            table.setRowHeight(46);
            table.setFont(new Font(FAMILY, Font.PLAIN, 16));
            table.getTableHeader().setFont(new Font(FAMILY, Font.BOLD, 16));
            table.getTableHeader().setPreferredSize(new Dimension(100, 44));
            table.getColumnModel().getColumn(0).setPreferredWidth(240);
            table.getColumnModel().getColumn(1).setPreferredWidth(720);
            table.getColumnModel().getColumn(2).setPreferredWidth(180);
            table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) tabs.setSelectedIndex(tabs.indexOfTab(HOME_ROWS[row][0]));
                }
            });

            JPanel grid = new JPanel(new BorderLayout());
            grid.setBorder(BorderFactory.createLineBorder(GRID));
            grid.add(table.getTableHeader(), BorderLayout.NORTH);
            grid.add(table, BorderLayout.CENTER);

            JLabel hint = new JLabel("Click a row to open it.  Edit the Accounts, Transactions and Adjustments tabs - "
                    + "every report updates by itself.");
            hint.setFont(FONT);
            hint.setForeground(new Color(0x66, 0x66, 0x66));
            hint.setBorder(BorderFactory.createEmptyBorder(14, 2, 0, 0));

            JPanel stack = new JPanel(new BorderLayout());
            stack.setOpaque(false);
            stack.add(grid, BorderLayout.NORTH);
            stack.add(hint, BorderLayout.CENTER);

            JPanel page = box();
            page.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            page.add(stack, BorderLayout.NORTH);
            return page;
        }

        private void updateHome(Report rep) {
            String[] s = new String[4];
            if (rep == null) {
                Arrays.fill(s, "Fix inputs");
            } else {
                s[0] = allOk(rep, 0, 5) ? "Balanced" : "Check";
                s[1] = allOk(rep, 5, 7) ? "Balanced" : "Check";
                s[2] = allOk(rep, 7, 9) ? "Balanced" : "Check";
                int pass = 0;
                for (Check c : rep.checks()) if (c.ok()) pass++;
                s[3] = pass == rep.checks().size() ? "All " + pass + " checks passed"
                        : pass + " of " + rep.checks().size() + " checks passed";
            }
            for (int i = 0; i < 4; i++) homeModel.setValueAt(s[i], i, 2);
        }

        // ================================================================
        //  Input tabs (editable)
        // ================================================================
        private JComponent inputTab(String hint, DefaultTableModel model, JTable table, Object[] blankRow) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setFont(FONT);
            hintLabel.setForeground(new Color(0x66, 0x66, 0x66));

            FlatButton addBtn = new FlatButton("+ Add Row", ORANGE, ORANGE_HOVER, TEXT);
            FlatButton delBtn = new FlatButton("Delete Selected", TOTAL_BG, GRID, TEXT);
            addBtn.addActionListener(e -> {
                stopEditing(table);
                model.addRow(blankRow.clone());
                int r = model.getRowCount() - 1;
                table.setRowSelectionInterval(r, r);
                table.scrollRectToVisible(table.getCellRect(r, 0, true));
            });
            delBtn.addActionListener(e -> {
                stopEditing(table);
                int[] rows = table.getSelectedRows();
                if (rows.length == 0) {
                    JOptionPane.showMessageDialog(this, "Click a row first, then press Delete Selected.");
                    return;
                }
                for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
            });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(addBtn);
            buttons.add(delBtn);

            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(Color.WHITE);
            bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            bar.add(hintLabel, BorderLayout.WEST);
            bar.add(buttons, BorderLayout.EAST);

            JScrollPane sp = new JScrollPane(table);
            sp.setColumnHeaderView(table.getTableHeader());
            sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID));
            sp.getViewport().setBackground(Color.WHITE);

            JPanel card = new JPanel(new BorderLayout());
            card.setBackground(Color.WHITE);
            card.setBorder(BorderFactory.createLineBorder(GRID));
            card.add(bar, BorderLayout.NORTH);
            card.add(sp, BorderLayout.CENTER);

            JPanel page = box();
            page.add(card, BorderLayout.CENTER);
            return page;
        }

        private void setUpEditors() {
            for (JTable t : new JTable[]{accountsTable, txTable, adjTable}) {
                int numericStart = (t == accountsTable) ? 99 : 3;
                for (int i = 0; i < t.getColumnCount(); i++) {
                    t.getColumnModel().getColumn(i).setCellEditor(textEditor(i >= numericStart));
                }
            }
            JComboBox<String> typeBox = new JComboBox<>(TYPES.toArray(new String[0]));
            typeBox.setFont(FONT);
            typeBox.setBackground(Color.WHITE);
            accountsTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(typeBox));
            accountsTable.getColumnModel().getColumn(0).setPreferredWidth(90);
            accountsTable.getColumnModel().getColumn(1).setPreferredWidth(420);
            accountsTable.getColumnModel().getColumn(2).setPreferredWidth(160);

            for (JTable t : new JTable[]{txTable, adjTable}) {
                JComboBox<String> box = new JComboBox<>(accountNames);
                box.setFont(FONT);
                box.setBackground(Color.WHITE);
                t.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(box));
                t.getColumnModel().getColumn(0).setPreferredWidth(110);
                t.getColumnModel().getColumn(1).setPreferredWidth(340);
                t.getColumnModel().getColumn(2).setPreferredWidth(260);
                t.getColumnModel().getColumn(3).setPreferredWidth(130);
                t.getColumnModel().getColumn(4).setPreferredWidth(130);
            }
        }

        private static DefaultCellEditor textEditor(boolean rightAligned) {
            JTextField tf = new JTextField();
            tf.setFont(FONT);
            tf.setHorizontalAlignment(rightAligned ? SwingConstants.RIGHT : SwingConstants.LEFT);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ORANGE, 2),
                    BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            return new DefaultCellEditor(tf);
        }

        private static void stopEditing(JTable t) {
            if (t.isEditing()) t.getCellEditor().stopCellEditing();
        }

        // ================================================================
        //  Keeping account names in sync
        // ================================================================
        private static String str(Object o) { return o == null ? "" : o.toString(); }

        private void renameAccount(String oldName, String newName) {
            for (DefaultTableModel m : new DefaultTableModel[]{txModel, adjModel}) {
                for (int r = 0; r < m.getRowCount(); r++) {
                    if (oldName.equals(str(m.getValueAt(r, 2)).trim())) m.setValueAt(newName, r, 2);
                }
            }
        }

        private void syncAccountNames() {
            int n = accountsModel.getRowCount();
            if (lastNames.size() != n) {
                lastNames.clear();
                for (int i = 0; i < n; i++) lastNames.add(str(accountsModel.getValueAt(i, 1)).trim());
            } else {
                for (int i = 0; i < n; i++) {
                    String s = str(accountsModel.getValueAt(i, 1)).trim();
                    if (!s.isBlank()) lastNames.set(i, s);
                }
            }
            accountNames.removeAllElements();
            for (int i = 0; i < n; i++) {
                String s = str(accountsModel.getValueAt(i, 1)).trim();
                if (!s.isBlank()) accountNames.addElement(s);
            }
        }

        // ================================================================
        //  Filling / loading
        // ================================================================
        private static String amt(BigDecimal v) { return v.signum() == 0 ? "" : v.toPlainString(); }

        void fill(List<Account> accounts, List<Entry> tx, List<Entry> adj) {
            stopEditing(accountsTable);
            stopEditing(txTable);
            stopEditing(adjTable);
            loading = true;
            try {
                accountsModel.setRowCount(0);
                for (Account a : accounts) accountsModel.addRow(new Object[]{String.valueOf(a.number()), a.name(), a.type()});
                txModel.setRowCount(0);
                for (Entry e : tx) txModel.addRow(new Object[]{e.ref(), e.description(), e.account(), amt(e.debit()), amt(e.credit())});
                adjModel.setRowCount(0);
                for (Entry e : adj) adjModel.addRow(new Object[]{e.ref(), e.description(), e.account(), amt(e.debit()), amt(e.credit())});
            } finally {
                loading = false;
            }
            syncAccountNames();
            recalcNow();
        }

        private void loadFromCsv() {
            File accounts = pick("Select accounts.csv");
            if (accounts == null) return;
            File tx = pick("Select transactions.csv");
            if (tx == null) return;
            File adj = pick("Select adjustments.csv");
            if (adj == null) return;
            try {
                fill(loadAccounts(accounts.getPath()), loadEntries(tx.getPath()), loadEntries(adj.getPath()));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, String.valueOf(ex.getMessage()), "Can't load files", JOptionPane.ERROR_MESSAGE);
            }
        }

        private File pick(String title) {
            JFileChooser fc = new JFileChooser(".");
            fc.setDialogTitle(title);
            return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
        }

        // ================================================================
        //  Reading the (possibly edited) tables
        // ================================================================
        private List<Account> readAccounts() {
            List<Account> out = new ArrayList<>();
            for (int r = 0; r < accountsModel.getRowCount(); r++) {
                String num = str(accountsModel.getValueAt(r, 0)).trim();
                String name = str(accountsModel.getValueAt(r, 1)).trim();
                String type = str(accountsModel.getValueAt(r, 2)).trim();
                if (num.isBlank() && name.isBlank()) continue;
                if (name.isBlank()) throw new IllegalArgumentException("Accounts row " + (r + 1) + " needs a name");
                int number;
                try {
                    number = Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Accounts row " + (r + 1) + ": '" + num + "' is not a valid account number");
                }
                out.add(new Account(number, name, type));
            }
            return out;
        }

        private BigDecimal parseAmount(Object v, String label, int row, String column) {
            String s = str(v).replace(",", "").trim();
            if (s.isEmpty()) return ZERO;
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " row " + (row + 1) + ": '" + s + "' is not a valid " + column + " amount");
            }
        }

        private List<Entry> readEntries(DefaultTableModel m, String label) {
            List<Entry> out = new ArrayList<>();
            for (int r = 0; r < m.getRowCount(); r++) {
                String ref = str(m.getValueAt(r, 0)).trim();
                String desc = str(m.getValueAt(r, 1)).trim();
                String acct = str(m.getValueAt(r, 2)).trim();
                BigDecimal dr = parseAmount(m.getValueAt(r, 3), label, r, "debit");
                BigDecimal cr = parseAmount(m.getValueAt(r, 4), label, r, "credit");
                if (acct.isBlank() && dr.signum() == 0 && cr.signum() == 0) continue;
                out.add(new Entry(ref, desc, acct, dr, cr));
            }
            return out;
        }

        // ================================================================
        //  Recalculation
        // ================================================================
        private void scheduleRecalc() {
            if (recalcPending) return;
            recalcPending = true;
            SwingUtilities.invokeLater(() -> {
                recalcPending = false;
                recalcNow();
            });
        }

        boolean recalcNow() {
            if (loading) return true;
            try {
                Report rep = generateReport(readAccounts(), readEntries(txModel, "Transactions"),
                        readEntries(adjModel, "Adjustments"));
                current = rep;
                setBox(worksheetBox, worksheetView(rep));
                setBox(trialBox, trialView(rep));
                setBox(statementsBox, statementsView(rep));
                setBox(reportBox, reportView(rep));
                updateHome(rep);
                boolean allGood = allOk(rep, 0, rep.checks().size());
                statusBar.setBackground(allGood ? GREEN : new Color(0xFF, 0xE0, 0xB2));
                status.setForeground(allGood ? GOOD : BAD);
                status.setText((allGood ? "Reports updated - all checks passed." : "Reports updated - some checks FAILED (see Report Generation).")
                        + "   Net income (loss): " + money(rep.incomeStatement().netIncome()));
                return true;
            } catch (RuntimeException ex) {
                updateHome(null);
                statusBar.setBackground(new Color(0xFF, 0xCD, 0xD2));
                status.setForeground(BAD);
                status.setText("Can't update the reports yet: " + ex.getMessage()
                        + "   (report tabs still show the last valid numbers)");
                return false;
            }
        }

        // ================================================================
        //  Report views (read-only)
        // ================================================================
        private static DefaultTableModel readOnlyModel(String[] cols, List<Object[]> rows) {
            DefaultTableModel model = new DefaultTableModel(cols, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            for (Object[] r : rows) model.addRow(r);
            return model;
        }

        private static String html(String top, String bottom) {
            return "<html><div style='text-align:center'>" + top.replace(" ", "&nbsp;") + "<br>" + bottom + "</div></html>";
        }

        private JComponent worksheetView(Report rep) {
            String[] cols = {"Acct", "Account",
                    html("Unadjusted", "Debit"), html("Unadjusted", "Credit"),
                    html("Adjustments", "Debit"), html("Adjustments", "Credit"),
                    html("Adjusted TB", "Debit"), html("Adjusted TB", "Credit"),
                    html("Income Stmt", "Debit"), html("Income Stmt", "Credit"),
                    html("Bal. Sheet", "Debit"), html("Bal. Sheet", "Credit")};
            List<Object[]> rows = new ArrayList<>();
            for (WorksheetRow r : rep.rows()) {
                Object[] row = new Object[12];
                row[0] = r.account.number();
                row[1] = r.account.name();
                BigDecimal[] v = r.values();
                for (int i = 0; i < 10; i++) row[i + 2] = money(v[i]);
                rows.add(row);
            }
            Set<Integer> bold = new HashSet<>();
            String[] labels = {"Totals", "Net Income (Loss)", "Final Totals"};
            BigDecimal[][] vals = {rep.totals(), rep.netIncomeRow(), rep.finalTotals()};
            for (int k = 0; k < 3; k++) {
                Object[] row = new Object[12];
                row[0] = "";
                row[1] = labels[k];
                for (int i = 0; i < 10; i++) row[i + 2] = money(vals[k][i]);
                bold.add(rows.size());
                rows.add(row);
            }
            JTable table = styledTable(readOnlyModel(cols, rows), 2, bold, 24, 6);
            table.setFont(new Font(FAMILY, Font.PLAIN, 13));
            table.getTableHeader().setFont(new Font(FAMILY, Font.BOLD, 12));
            table.getTableHeader().setPreferredSize(new Dimension(100, 44));
            table.getColumnModel().getColumn(0).setPreferredWidth(44);
            table.getColumnModel().getColumn(1).setPreferredWidth(250);
            for (int i = 2; i < 12; i++) table.getColumnModel().getColumn(i).setPreferredWidth(90);
            boolean ok = allOk(rep, 0, 5);
            return card(null, table, ok ? "Balanced" : "OUT OF BALANCE", ok);
        }

        private JComponent trialView(Report rep) {
            JPanel both = new JPanel(new GridLayout(1, 2, 16, 0));
            both.setOpaque(false);
            both.add(trialCard("Unadjusted Trial Balance", rep.unadjusted()));
            both.add(trialCard("Adjusted Trial Balance", rep.adjusted()));
            return both;
        }

        private JComponent trialCard(String title, TrialBalance tb) {
            List<Object[]> rows = new ArrayList<>();
            for (TbLine l : tb.lines()) rows.add(new Object[]{l.account().name(), money(l.debit()), money(l.credit())});
            rows.add(new Object[]{"Totals", money(tb.totalDr()), money(tb.totalCr())});
            Set<Integer> bold = new HashSet<>();
            bold.add(rows.size() - 1);
            JTable table = styledTable(readOnlyModel(new String[]{"Account", "Debit", "Credit"}, rows), 1, bold, 25, 10);
            table.getTableHeader().setPreferredSize(new Dimension(100, 32));
            table.getColumnModel().getColumn(0).setPreferredWidth(300);
            table.getColumnModel().getColumn(1).setPreferredWidth(110);
            table.getColumnModel().getColumn(2).setPreferredWidth(110);
            return card(title, table, tb.balanced() ? "Balanced" : "OUT OF BALANCE", tb.balanced());
        }

        private JComponent statementsView(Report rep) {
            JPanel both = new JPanel(new GridLayout(1, 2, 16, 0));
            both.setOpaque(false);

            IncomeStatement is = rep.incomeStatement();
            List<Object[]> r1 = new ArrayList<>();
            Set<Integer> b1 = new HashSet<>();
            addHeader(r1, b1, "Revenues");
            addLines(r1, is.revenues());
            addTotal(r1, b1, "Total Revenues", is.totalRevenues());
            addHeader(r1, b1, "Expenses");
            addLines(r1, is.expenses());
            addTotal(r1, b1, "Total Expenses", is.totalExpenses());
            addTotal(r1, b1, "Net Income (Loss)", is.netIncome());
            JTable t1 = styledTable(readOnlyModel(new String[]{"Item", "Amount"}, r1), 1, b1, 25, 10);
            t1.getTableHeader().setPreferredSize(new Dimension(100, 32));
            t1.getColumnModel().getColumn(0).setPreferredWidth(380);
            t1.getColumnModel().getColumn(1).setPreferredWidth(140);
            both.add(card("Income Statement", t1, null, true));

            BalanceSheet bs = rep.balanceSheet();
            List<Object[]> r2 = new ArrayList<>();
            Set<Integer> b2 = new HashSet<>();
            addHeader(r2, b2, "Assets");
            addLines(r2, bs.assets());
            addTotal(r2, b2, "Total Assets", bs.totalAssets());
            addHeader(r2, b2, "Liabilities");
            addLines(r2, bs.liabilities());
            addTotal(r2, b2, "Total Liabilities", bs.totalLiabilities());
            addHeader(r2, b2, "Owner's Equity");
            addLines(r2, bs.equity());
            addTotal(r2, b2, "Total Owner's Equity", bs.totalEquity());
            addTotal(r2, b2, "Total Liabilities + Equity", bs.totalLiabilities().add(bs.totalEquity()));
            JTable t2 = styledTable(readOnlyModel(new String[]{"Item", "Amount"}, r2), 1, b2, 25, 10);
            t2.getTableHeader().setPreferredSize(new Dimension(100, 32));
            t2.getColumnModel().getColumn(0).setPreferredWidth(380);
            t2.getColumnModel().getColumn(1).setPreferredWidth(140);
            both.add(card("Balance Sheet", t2, bs.balanced() ? "Balanced" : "OUT OF BALANCE", bs.balanced()));
            return both;
        }

        private static void addHeader(List<Object[]> rows, Set<Integer> bold, String text) {
            bold.add(rows.size());
            rows.add(new Object[]{text, ""});
        }

        private static void addTotal(List<Object[]> rows, Set<Integer> bold, String text, BigDecimal amount) {
            bold.add(rows.size());
            rows.add(new Object[]{text, money(amount)});
        }

        private static void addLines(List<Object[]> rows, List<Line> lines) {
            for (Line l : lines) rows.add(new Object[]{"      " + l.name(), money(l.amount())});
        }

        private JComponent reportView(Report rep) {
            List<Object[]> rows = new ArrayList<>();
            for (Check c : rep.checks()) rows.add(new Object[]{c.name(), c.ok() ? "Pass" : "FAIL"});
            JTable checks = styledTable(readOnlyModel(new String[]{"Check", "Result"}, rows), 99, null, 24, 12);
            checks.getTableHeader().setPreferredSize(new Dimension(100, 30));
            checks.getColumnModel().getColumn(0).setPreferredWidth(800);
            checks.getColumnModel().getColumn(1).setPreferredWidth(100);
            int pass = 0;
            for (Check c : rep.checks()) if (c.ok()) pass++;
            JComponent checksCard = card("Connect accounting calculations to the worksheet and financial statements",
                    checks, pass + " of " + rep.checks().size() + " checks passed", pass == rep.checks().size());
            checksCard.setPreferredSize(new Dimension(100, 36 + 30 + 24 * rep.checks().size() + 40));

            String text = buildTextReport(rep);
            JTextArea area = new JTextArea(text);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setCaretPosition(0);
            JScrollPane sp = new JScrollPane(area);
            sp.setBorder(BorderFactory.createEmptyBorder());

            FlatButton copy = new FlatButton("Copy Report", TOTAL_BG, GRID, TEXT);
            FlatButton save = new FlatButton("Save Report (.txt)...", ORANGE, ORANGE_HOVER, TEXT);
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                JOptionPane.showMessageDialog(this, "Report copied to the clipboard.");
            });
            save.addActionListener(e -> saveText(text));
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            btns.setBackground(Color.WHITE);
            btns.add(copy);
            btns.add(save);

            JPanel textCard = new JPanel(new BorderLayout());
            textCard.setBackground(Color.WHITE);
            textCard.setBorder(BorderFactory.createLineBorder(GRID));
            textCard.add(titleBar("Full report"), BorderLayout.NORTH);
            textCard.add(sp, BorderLayout.CENTER);
            textCard.add(btns, BorderLayout.SOUTH);

            JPanel page = new JPanel(new BorderLayout(0, 14));
            page.setOpaque(false);
            page.add(checksCard, BorderLayout.NORTH);
            page.add(textCard, BorderLayout.CENTER);
            return page;
        }

        private void saveText(String text) {
            JFileChooser fc = new JFileChooser(".");
            fc.setDialogTitle("Save report");
            fc.setSelectedFile(new File("report.txt"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try (PrintWriter w = writer(fc.getSelectedFile().toPath())) {
                w.print(text);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, String.valueOf(ex.getMessage()), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private static JComponent titleBar(String text) {
            JLabel l = new JLabel(text);
            l.setFont(FONT_BOLD);
            l.setForeground(TEXT);
            l.setOpaque(true);
            l.setBackground(GREEN);
            l.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            return l;
        }

        /** White card: optional green title bar, table, optional Balanced / OUT OF BALANCE footer. */
        private static JComponent card(String title, JTable table, String footer, boolean ok) {
            JScrollPane sp = new JScrollPane(table);
            sp.setColumnHeaderView(table.getTableHeader());
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.getViewport().setBackground(Color.WHITE);

            JPanel card = new JPanel(new BorderLayout());
            card.setBackground(Color.WHITE);
            card.setBorder(BorderFactory.createLineBorder(GRID));
            if (title != null) card.add(titleBar(title), BorderLayout.NORTH);
            card.add(sp, BorderLayout.CENTER);
            if (footer != null) {
                JLabel l = new JLabel(footer);
                l.setFont(FONT_BOLD);
                l.setForeground(ok ? GOOD : BAD);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, GRID),
                        BorderFactory.createEmptyBorder(9, 14, 9, 14)));
                card.add(l, BorderLayout.SOUTH);
            }
            return card;
        }

        // ================================================================
        //  Export (inputs + reports)
        // ================================================================
        private void export() {
            stopEditing(accountsTable);
            stopEditing(txTable);
            stopEditing(adjTable);
            if (!recalcNow()) {
                JOptionPane.showMessageDialog(this, "Please fix the problem shown at the bottom first.",
                        "Can't export yet", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JFileChooser fc = new JFileChooser(".");
            fc.setDialogTitle("Choose export folder");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File dir = fc.getSelectedFile();
            try {
                Files.createDirectories(dir.toPath());
                writeInputs(dir);
                exportCsv(current, dir.getPath());
                JOptionPane.showMessageDialog(this, "Saved your inputs and the reports (CSV + report.txt) to:\n" + dir);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, String.valueOf(ex.getMessage()), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void writeInputs(File dir) throws IOException {
            try (PrintWriter w = writer(new File(dir, "accounts.csv").toPath())) {
                w.println("number,name,type");
                for (Account a : readAccounts()) w.println(a.number() + "," + csv(a.name()) + "," + a.type());
            }
            writeEntries(new File(dir, "transactions.csv"), "date,description,account,debit,credit",
                    readEntries(txModel, "Transactions"));
            writeEntries(new File(dir, "adjustments.csv"), "ref,description,account,debit,credit",
                    readEntries(adjModel, "Adjustments"));
        }

        private void writeEntries(File file, String header, List<Entry> entries) throws IOException {
            try (PrintWriter w = writer(file.toPath())) {
                w.println(header);
                for (Entry e : entries) {
                    w.println(csv(e.ref()) + "," + csv(e.description()) + "," + csv(e.account()) + ","
                            + e.debit().toPlainString() + "," + e.credit().toPlainString());
                }
            }
        }

        // ================================================================
        //  Styling helpers
        // ================================================================
        static JTable styledTable(DefaultTableModel model, int numericStart, Set<Integer> boldRows) {
            return styledTable(model, numericStart, boldRows, 30, 12);
        }

        static JTable styledTable(DefaultTableModel model, int numericStart, Set<Integer> boldRows,
                                  int rowHeight, int pad) {
            JTable table = new JTable(model);
            table.setRowHeight(rowHeight);
            table.setFont(FONT);
            table.setShowGrid(true);
            table.setGridColor(GRID);
            table.setIntercellSpacing(new Dimension(1, 1));
            table.setFillsViewportHeight(true);
            table.setSelectionBackground(SELECT);
            table.setSelectionForeground(TEXT);
            table.setSurrendersFocusOnKeystroke(true);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

            JTableHeader header = table.getTableHeader();
            header.setReorderingAllowed(false);
            header.setDefaultRenderer(new HeaderRenderer());
            header.setPreferredSize(new Dimension(100, 38));

            CellRenderer renderer = new CellRenderer(numericStart, boldRows, pad);
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setCellRenderer(renderer);
            }
            return table;
        }

        static class CellRenderer extends DefaultTableCellRenderer {
            private final int numericStart;
            private final Set<Integer> bold;
            private final int pad;

            CellRenderer(int numericStart, Set<Integer> bold, int pad) {
                this.numericStart = numericStart;
                this.bold = bold;
                this.pad = pad;
            }

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, false, row, col);
                boolean isBold = bold != null && bold.contains(row);
                setHorizontalAlignment(col >= numericStart ? SwingConstants.RIGHT : SwingConstants.LEFT);
                setFont(isBold ? t.getFont().deriveFont(Font.BOLD) : t.getFont());

                String s = value == null ? "" : value.toString();
                Color fg = TEXT;
                if (s.equals("-")) fg = MUTED;
                else if (s.startsWith("(") && s.endsWith(")") && s.length() > 3 && Character.isDigit(s.charAt(1))) fg = BAD;
                else if (s.equals("Pass") || s.equals("Balanced") || s.endsWith("checks passed") && s.startsWith("All ")) fg = GOOD;
                else if (s.equals("FAIL") || s.equals("Check") || s.equals("Fix inputs") || s.matches("\\d+ of \\d+ checks passed")) fg = BAD;

                if (sel) {
                    setBackground(SELECT);
                    setForeground(TEXT);
                } else {
                    setBackground(isBold ? TOTAL_BG : Color.WHITE);
                    setForeground(fg);
                }
                Border border = BorderFactory.createEmptyBorder(0, pad, 0, pad);
                setBorder(border);
                return this;
            }
        }

        /** Orange header cell with bold centred text - same as "Accounting Reports" in the picture. */
        static class HeaderRenderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, false, false, row, col);
                setOpaque(true);
                setBackground(ORANGE);
                setForeground(TEXT);
                setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD));
                setHorizontalAlignment(SwingConstants.CENTER);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0xE0, 0x8A, 0x00)),
                        BorderFactory.createEmptyBorder(0, 3, 0, 3)));
                return this;
            }
        }

        /** Flat rounded button that looks the same on every platform. */
        static class FlatButton extends JButton {
            private final Color base, hover;
            private boolean over = false;

            FlatButton(String text, Color base, Color hover, Color fg) {
                super(text);
                this.base = base;
                this.hover = hover;
                setForeground(fg);
                setFont(FONT_BOLD);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { over = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { over = false; repaint(); }
                });
            }

            @Override
            public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                return new Dimension(fm.stringWidth(getText()) + 34, fm.getHeight() + 14);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(over ? hover : base);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(getForeground());
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        }

        /** Flat tabs: selected tab is orange (like the header cell in the picture). */
        static class FlatTabsUI extends BasicTabbedPaneUI {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                tabInsets = new Insets(10, 18, 10, 18);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                tabAreaInsets = new Insets(0, 0, 0, 0);
                contentBorderInsets = new Insets(0, 0, 0, 0);
            }

            @Override
            protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                              int x, int y, int w, int h, boolean isSelected) {
                g.setColor(isSelected ? ORANGE : Color.WHITE);
                g.fillRect(x, y, w, h);
            }

            @Override
            protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                          int x, int y, int w, int h, boolean isSelected) {
                g.setColor(isSelected ? new Color(0xE0, 0x8A, 0x00) : GRID);
                g.drawRect(x, y, w - 1, h - 1);
            }

            @Override
            protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) { }

            @Override
            protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                               int tabIndex, Rectangle iconRect, Rectangle textRect,
                                               boolean isSelected) { }
        }
    }

    // ==================================================================
    //  MAIN
    // ==================================================================
    static void usage() {
        System.err.println("Usage: java AccountingReports [--console] [--accounts a.csv --transactions t.csv"
                + " --adjustments j.csv] [--export folder]");
    }

    public static void main(String[] args) {
        String accountsPath = null, txPath = null, adjPath = null, exportDir = null;
        boolean console = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--console")) { console = true; continue; }
            if (i + 1 >= args.length) { System.err.println("Missing value for " + a); usage(); System.exit(2); }
            switch (a) {
                case "--accounts" -> accountsPath = args[++i];
                case "--transactions" -> txPath = args[++i];
                case "--adjustments" -> adjPath = args[++i];
                case "--export" -> exportDir = args[++i];
                default -> { System.err.println("Unknown option: " + a); usage(); System.exit(2); }
            }
        }

        try {
            List<Account> accounts;
            List<Entry> transactions, adjustments;
            if (accountsPath != null && txPath != null && adjPath != null) {
                accounts = loadAccounts(accountsPath);
                transactions = loadEntries(txPath);
                adjustments = loadEntries(adjPath);
            } else if (accountsPath == null && txPath == null && adjPath == null) {
                accounts = sampleAccounts();
                transactions = sampleTransactions();
                adjustments = sampleAdjustments();
            } else {
                System.err.println("Give all three of --accounts, --transactions, --adjustments (or none for sample data).");
                usage();
                System.exit(2);
                return;
            }

            if (console || exportDir != null || GraphicsEnvironment.isHeadless()) {
                Report rep = generateReport(accounts, transactions, adjustments);
                System.out.print(buildTextReport(rep));
                if (exportDir != null) {
                    exportCsv(rep, exportDir);
                    System.out.println("\nSaved CSV files and report.txt to: " + exportDir);
                }
                return;
            }

            final List<Account> fa = accounts;
            final List<Entry> ft = transactions, fj = adjustments;
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) { }
                AppPanel panel = new AppPanel();
                JFrame frame = new JFrame("Accounting Reports");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setContentPane(panel);
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                frame.setSize(Math.min(1280, screen.width - 60), Math.min(820, screen.height - 80));
                frame.setMinimumSize(new Dimension(800, 520));
                frame.setLocationRelativeTo(null);
                panel.fill(fa, ft, fj);
                frame.setVisible(true);
            });
        } catch (java.nio.file.NoSuchFileException e) {
            System.err.println("Error: can't find the file '" + e.getFile() + "'");
            System.exit(1);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}