"""Proves SQL del saldo amb signe (no són proves d’execució Android)."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/java/com/comprarador/app/SavingsLedger.kt').read_text()
ACTIVITY = (ROOT / 'app/src/main/java/com/comprarador/app/MainActivity.kt').read_text()

class SignedLedgerContractTests(unittest.TestCase):
    def setUp(self):
        statement = re.search(r'CREATE TABLE saving_events \(.*?\n\s*\)', SOURCE, re.S)
        self.assertIsNotNone(statement)
        self.db = sqlite3.connect(':memory:')
        self.db.execute(statement.group(0))

    def tearDown(self):
        self.db.close()

    def insert(self, key, amount):
        return self.db.execute('''INSERT OR IGNORE INTO saving_events
          (id, receipt_fingerprint, created_at_ms, amount_milli,
           historical_milli, current_milli, matched_lines, total_lines,
           coverage_permille, basis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)''',
            (key, key, 1000000, amount, 10000, 10000 - amount,
             1, 2, 500, 'catalog_2003_nominal')).rowcount

    def test_positive_and_negative_affect_net_balance(self):
        self.assertEqual(self.insert('a', 2500), 1)
        self.assertEqual(self.insert('b', -4200), 1)
        self.assertEqual(self.db.execute('SELECT SUM(amount_milli) FROM saving_events').fetchone()[0], -1700)

    def test_identical_receipt_is_not_counted_twice(self):
        self.assertEqual(self.insert('duplicate', -700), 1)
        self.assertEqual(self.insert('duplicate', -700), 0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM saving_events').fetchone()[0], 1)

    def test_app_accepts_negative_movements(self):
        self.assertIn('saveButton.isEnabled = diff != 0L', ACTIVITY)
        self.assertIn('Math.subtractExact(candidate.historic, candidate.current)', ACTIVITY)
        self.assertIn('val payload = candidate.formSignature', ACTIVITY)
        self.assertIn('Math.subtractExact(event.historicalMilli, event.currentMilli)', SOURCE)

if __name__ == '__main__':
    unittest.main()
