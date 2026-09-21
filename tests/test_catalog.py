import sqlite3
import tempfile
import unittest
from pathlib import Path
from tools.build_catalog import build, euro_milli, normalize, parse, quantity_from


class ProvesCataleg(unittest.TestCase):
    def test_precisio_dels_imports(self):
        self.assertEqual(euro_milli('2,355'), 2355)
        self.assertEqual(euro_milli('0,688'), 688)

    def test_quantitats(self):
        self.assertEqual(quantity_from('Poma Golden, aprox. 1,00kg (1,75 euros/kg)'), (1000, 'kg'))
        self.assertEqual(quantity_from('ampolla de 75 cl.'), (750, 'l'))
        self.assertEqual(quantity_from('bossa 500g'), (500, 'kg'))
        self.assertEqual(quantity_from('paquet 6 unid.'), (6000, 'ud'))
        self.assertEqual(quantity_from('Pera Conferència, calibre 60/70'), (None, None))

    def test_exemples_de_registre(self):
        e = parse('Alvocat, aprox. 500g (4,71 euros/kg);2,355;392', 'eroski')
        self.assertEqual((e[4], e[6], e[7]), (2355, 500, 'kg'))
        c = parse('Vi negre;Protos;ampolla de 75 cl.;14,39', 'carrefour')
        self.assertEqual((c[4], c[6], c[7]), (14390, 750, 'l'))

    def test_normalitzacio_i_equivalencies(self):
        self.assertIn('manzana', normalize('POMA GOLDEN'))
        self.assertIn('leche', normalize('llet semi'))

    def test_fidelitat_i_indexacio(self):
        with tempfile.TemporaryDirectory() as temporal:
            arrel = Path(temporal)
            (arrel / 'carrefour.csv').write_text('Vi negre;Protos;ampolla de 75 cl.;14,39\n', encoding='utf-8')
            (arrel / 'eroski.csv').write_text('Poma Golden, aprox. 1,00kg (1,75 euros/kg);1,75;291\nCondicionador amb ; text;1,85;308\n', encoding='utf-8')
            base = arrel / 'catalog.sqlite'
            informe = build(arrel, base, arrel / 'rejects.jsonl')
            self.assertEqual(informe['products_total'], 3)
            self.assertEqual(informe['indexed_total'], 3)
            self.assertEqual(informe['integrity'], 'ok')
            self.assertEqual((arrel / 'rejects.jsonl').read_text(), '')
            with sqlite3.connect(base) as connexio:
                self.assertEqual(connexio.execute("SELECT count(*) FROM products_fts WHERE normalized_name MATCH 'manzana'").fetchone()[0], 1)
                self.assertEqual(connexio.execute('SELECT source_text FROM products WHERE retailer=? AND source_line=?', ('eroski', 2)).fetchone()[0], 'Condicionador amb ; text;1,85;308')


if __name__ == '__main__':
    unittest.main()
