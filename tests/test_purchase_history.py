"""Proves SQL de les taules personals i de la integració amb els tiquets."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/java/com/comprarador/app/PurchaseHistory.kt').read_text(encoding='utf-8')
MAIN = (ROOT / 'app/src/main/java/com/comprarador/app/MainActivity.kt').read_text(encoding='utf-8')


class PurchaseHistoryTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        schemas = re.findall(r'"""(CREATE TABLE .*?)"""', SOURCE, re.S)
        self.assertEqual(len(schemas), 3)
        for schema in schemas:
            self.db.execute(schema)

    def tearDown(self):
        self.db.close()

    def test_unicitat_tiquet_i_linies(self):
        self.db.execute("INSERT INTO receipts VALUES ('a', 1000)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO receipts VALUES ('a', 2000)")
        self.db.execute("INSERT INTO products(product_key,display_name,normalized_name,unit) VALUES ('poma:kg','Poma','poma','kg')")
        self.db.execute("INSERT INTO receipt_lines(receipt_fingerprint,line_number,product_id,original_description,current_milli,quantity_milli) VALUES ('a',0,1,'Poma',2000,1000)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO receipt_lines(receipt_fingerprint,line_number,product_id,original_description,current_milli,quantity_milli) VALUES ('a',0,1,'Poma',2000,1000)")

    def test_integritat_i_restriccions(self):
        self.db.execute("INSERT INTO receipts VALUES ('a', 1000)")
        self.db.execute("INSERT INTO products(product_key,display_name,normalized_name,unit) VALUES ('poma:kg','Poma','poma','kg')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO receipt_lines(receipt_fingerprint,line_number,product_id,original_description,current_milli,quantity_milli) VALUES ('a',0,1,'Poma',2000,0)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO receipt_lines(receipt_fingerprint,line_number,product_id,original_description,current_milli,quantity_milli) VALUES ('absent',0,1,'Poma',2000,1000)")

    def test_frequencia_per_tiquet_i_filtre(self):
        self.db.executemany('INSERT INTO receipts VALUES (?,?)', [('a',1000),('b',2000)])
        self.db.execute("INSERT INTO products(product_key,display_name,normalized_name,unit) VALUES ('poma:kg','Poma','poma','kg')")
        self.db.executemany('INSERT INTO receipt_lines(receipt_fingerprint,line_number,product_id,original_description,current_milli,quantity_milli) VALUES (?,?,?,?,?,?)',
            [('a',0,1,'Poma',2000,1000),('a',1,1,'Poma',1100,500),('b',0,1,'Poma',2600,1000)])
        count, quantity, spent = self.db.execute('''SELECT COUNT(DISTINCT receipt_fingerprint), SUM(quantity_milli), SUM(current_milli)
            FROM receipt_lines WHERE product_id=1''').fetchone()
        self.assertEqual((count,quantity,spent), (2,2500,5700))
        series = self.db.execute('''SELECT r.registered_ms,SUM(l.current_milli),SUM(l.quantity_milli)
            FROM receipt_lines l JOIN receipts r ON r.fingerprint=l.receipt_fingerprint
            WHERE l.product_id=1 GROUP BY l.receipt_fingerprint ORDER BY r.registered_ms ASC''').fetchall()
        self.assertEqual(series, [(1000,3100,1500),(2000,2600,1000)])

    def test_integracio_aplicacio_i_versio(self):
        self.assertIn('history.record(fingerprint, now, purchaseLines)', MAIN)
        self.assertIn('saveButton.isEnabled = true', MAIN)
        self.assertIn('BigDecimal(product.priceMilli)', MAIN)
        self.assertIn('ORDER BY COUNT(DISTINCT l.receipt_fingerprint) DESC', SOURCE)
        gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('versionName = "0.1.3"', gradle)
        self.assertIn('versionCode = 6', gradle)


if __name__ == '__main__':
    unittest.main()
