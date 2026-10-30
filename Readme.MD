# Accounting Reports

This project produces a complete set of accounting reports from a list of accounts, transactions and adjusting entries. It covers four reports:

| Report | Task |
|---|---|
| **Worksheet** | Generate the worksheet using transactions and adjustments. |
| **Trial Balance** | Generate the unadjusted and adjusted trial balances. |
| **Financial Statements** | Generate the income statement and balance sheet. |
| **Report Generation** | Connect accounting calculations to the worksheet and financial statements. |

All four reports are linked. You enter the accounts, transactions and adjustments once, and every report is calculated from them and updates by itself when something changes.

---

## What you enter

**Accounts.** The chart of accounts: an account number, a name and a type (Asset, Liability, Equity, Revenue or Expense).

**Transactions.** The journal entries for the period. Each line has a date, a description, an account, and either a debit or a credit. Total debits must equal total credits.

**Adjustments.** The adjusting entries made at the end of the period (supplies used, insurance expired, depreciation, accrued salaries and so on). Same layout as transactions, and they must balance too.

---

## The four reports

### 1. Worksheet

The worksheet shows every account across five pairs of Debit and Credit columns:

| Columns | What they show |
|---|---|
| Unadjusted | Each account's balance from the transactions alone |
| Adjustments | The totals of the adjusting entries for each account |
| Adjusted TB | The unadjusted balance combined with the adjustments |
| Income Stmt | Revenue and expense accounts, carried across from the adjusted balance |
| Bal. Sheet | Asset, liability and equity accounts, carried across from the adjusted balance |

Below the accounts are three rows:

- **Totals** - the total of each column.
- **Net Income (Loss)** - the difference between the Income Statement columns. It is added to the smaller side of the Income Statement columns and to the opposite side of the Balance Sheet columns.
- **Final Totals** - after net income is added, each pair of columns is equal.

### 2. Trial Balance

Two trial balances are produced side by side:

- **Unadjusted trial balance** - account balances after the transactions, before any adjustments.
- **Adjusted trial balance** - account balances after the adjusting entries.

Each lists only the accounts that have a balance, with total debits and total credits. The two totals must match, and the report says **Balanced** when they do.

### 3. Financial Statements

**Income Statement**

- Revenues, listed by account, with total revenues
- Expenses, listed by account, with total expenses
- **Net Income (Loss)** = total revenues - total expenses

**Balance Sheet**

- **Assets** - listed by account, with total assets. Accounts that reduce an asset, such as accumulated depreciation, appear as negative amounts.
- **Liabilities** - listed by account, with total liabilities.
- **Owner's Equity** - owner's capital, plus net income, less owner's drawings, with total owner's equity.
- **Total Liabilities + Equity**, which must equal total assets.

### 4. Report Generation

This report connects the other three and proves they agree. It runs nine checks:

1. Worksheet: unadjusted columns balance
2. Worksheet: adjustments columns balance
3. Worksheet: adjusted columns balance
4. Worksheet: income statement columns balance (after net income)
5. Worksheet: balance sheet columns balance (after net income)
6. Unadjusted trial balance: debits = credits
7. Adjusted trial balance: debits = credits
8. Income statement net income = worksheet net income
9. Balance sheet: assets = liabilities + equity

It also shows the full set of reports as one text report that you can copy or save.

---

## How the reports are connected

```
Accounts + Transactions + Adjustments
                 |
                 v
            Worksheet  --->  Unadjusted Trial Balance
                 |           Adjusted Trial Balance
                 v
        Income Statement  --->  Net Income
                 |                  |
                 v                  v
           Balance Sheet  <---  Owner's Equity
                 |
                 v
        Report Generation (9 checks)
```

Net income calculated on the Income Statement is the same figure that appears on the Worksheet and in the Owner's Equity section of the Balance Sheet. If any of the reports disagree, the matching check in Report Generation shows **FAIL**.

---

## Example (sample data)

The built-in example is a small service business for January 2026, with 18 accounts, 12 transactions and 5 adjusting entries.

| Item | Amount |
|---|---|
| Unadjusted trial balance total | 70,000.00 |
| Adjusting entries total | 3,500.00 |
| Adjusted trial balance total | 71,200.00 |
| Total revenues | 16,700.00 |
| Total expenses | 8,450.00 |
| **Net income** | **8,250.00** |
| Total assets | 61,350.00 |
| Total liabilities | 4,100.00 |
| Total owner's equity (50,000 capital + 8,250 net income - 1,000 drawings) | 57,250.00 |

All nine checks pass. The figures are made up for illustration and are not real accounting records.

---

## Using the program

The program has one tab for each of the four reports, plus the three input tabs (Accounts, Transactions, Adjustments) and a home tab listing the four reports with a status for each. Edit any cell in an input tab and all reports update immediately. If an entry is wrong (for example debits do not equal credits), a message at the bottom of the window explains the problem.

Your data can be loaded from CSV files, and the reports can be exported to CSV and text files for use elsewhere.

---

## Notes

- Each report covers one accounting period.
- Closing entries and a statement of owner's equity are not produced.
- Account names in the entries must match the Accounts list exactly.