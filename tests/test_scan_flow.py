"""Contractes del flux OCR i consultes SQL; no substitueixen proves instrumentades Android."""
import re
import sqlite3
import unittest
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / 'app/src/main/java/com/comprarador/app'
INSIGHTS = (KOTLIN / 'PurchaseInsights.kt').read_text(encoding='utf-8')
SCAN = (KOTLIN / 'ScanReceiptActivity.kt').read_text(encoding='utf-8')
TOP = (KOTLIN / 'TopPurchasesActivity.kt').read_text(encoding='utf-8')
DASH = (KOTLIN / 'DashboardActivity.kt').read_text(encoding='utf-8')

class ScanFlowTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
          CREATE TABLE products (id INTEGER PRIMARY KEY, product_key TEXT, normalized_name TEXT, unit TEXT,
            display_name TEXT, historical_unit_milli INTEGER);
          CREATE TABLE receipts (fingerprint TEXT PRIMARY KEY, registered_ms INTEGER NOT NULL);
          CREATE TABLE receipt_lines (receipt_fingerprint TEXT, product_id INTEGER, current_milli INTEGER,
            quantity_milli INTEGER);
          INSERT INTO products VALUES (7, 'hist:42:l', 'llet sencera', 'l', 'Llet sencera', 700);
          INSERT INTO receipts VALUES ('primer', 1000), ('segon', 2000);
          INSERT INTO receipt_lines VALUES ('primer', 7, 1200, 1000);
          INSERT INTO receipt_lines VALUES ('segon', 7, 1100, 1000);
          INSERT INTO receipt_lines VALUES ('segon', 7, 1300, 1000);
        ''')

    def tearDown(self):
        self.db.close()

    def sql(self):
        # Executem literalment la consulta de Kotlin, canviant només el predicat variable.
        match = re.search(r'val query = """(SELECT p\.id,r\.registered_ms,SUM\(l\.current_milli\).*?)"""',
                          INSIGHTS, re.S)
        self.assertIsNotNone(match)
        return match.group(1)

    def test_last_receipt_is_used_not_2003(self):
        sql = self.sql().replace('$predicate', 'p.product_key=?')
        row = self.db.execute(sql, ('hist:42:l',)).fetchone()
        self.assertEqual(row, (7, 2000, 2400, 2000))
        # 2,40 euros / 2 litres = 1,20 euros/l; preu de 2003 = 0,70 euros/l.
        self.assertEqual(round(row[2] * 1000 / row[3]), 1200)
        self.assertEqual(1500 - 1200, 300)
        self.assertEqual(900 - 1200, -300)
        self.assertEqual(1200 - 1200, 0)

    def test_unmatched_product_requires_same_normalized_name_and_unit(self):
        sql = self.sql().replace('$predicate', 'p.normalized_name=? AND p.unit=?')
        self.assertEqual(self.db.execute(sql, ('llet sencera', 'l')).fetchone()[0], 7)
        self.assertIsNone(self.db.execute(sql, ('llet sencera', 'kg')).fetchone())

    def test_scan_camera_cards_shared_chart_and_history(self):
        self.assertIn('if (savedInstanceState == null) window.decorView.post { startCamera() }', SCAN)
        self.assertIn('ReceiptParser.parse(ocrText)', SCAN)
        self.assertIn('PurchaseInsights.variation(PurchaseInsights.unitPrice(current, quantity), previous.unitPriceMilli)', SCAN)
        self.assertIn('row.trend.setTextColor(when { difference > 0 -> red; difference < 0 -> downGreen; else -> orange })', SCAN)
        self.assertIn('history.record(key, now, items)', SCAN)
        self.assertIn('ledger.record(SavingEvent(', SCAN)
        self.assertIn('putExtra("product_id", id)', SCAN)
        self.assertIn('PurchaseInsights(history.readableDatabase).item(id)', TOP)
        self.assertIn('canvas.drawText("2003"', TOP)
        self.assertIn('Intent(this, ScanReceiptActivity::class.java)', DASH)

    def test_scan_translations_and_symbolic_trend(self):
        directories = ['values','values-es','values-gl','values-eu','values-oc','values-ast','values-an']
        reference = None
        for directory in directories:
            path = ROOT / 'app/src/main/res' / directory / 'strings_scan.xml'
            self.assertTrue(path.is_file(), path)
            values = {x.get('name'): x.text for x in ElementTree.parse(path).getroot()}
            self.assertTrue(all(values.values()), directory)
            self.assertEqual(len(values), 16)
            if reference is None:
                reference = set(values)
            self.assertEqual(set(values), reference, directory)
            self.assertTrue(values['scan_up'].startswith('↗ +'))
            self.assertTrue(values['scan_down'].startswith('↘ −'))
            self.assertTrue(values['scan_equal'].startswith('= '))

if __name__ == '__main__':
    unittest.main()
