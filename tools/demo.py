#!/usr/bin/env python3
"""Demostració local: mostra equivalències candidates sense confirmar-les automàticament."""
from __future__ import annotations
import argparse
import sqlite3
from pathlib import Path
from build_catalog import normalize

ROOT = Path(__file__).resolve().parents[1]

def candidates(con, text, unit, limit=5):
    words = [w for w in normalize(text).split() if len(w) >= 3 and w.isalpha()]
    if not words:
        return []
    query = ' AND '.join(w + '*' for w in words[:3])
    return con.execute('''SELECT p.retailer, p.name, p.brand, p.price_milli_eur, p.quantity_milli, p.quantity_unit, p.source_line
         FROM products p JOIN products_fts f ON f.rowid=p.id
         WHERE f.normalized_name MATCH ? AND p.quantity_unit=? AND p.quantity_milli IS NOT NULL LIMIT ?''',
         (query,unit,limit)).fetchall()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('query', nargs='?', default='poma golden')
    parser.add_argument('--unit', choices=['kg','l','ud'], default='kg')
    parser.add_argument('--db', type=Path, default=ROOT/'app/src/main/assets/catalog.sqlite')
    args=parser.parse_args()
    with sqlite3.connect(args.db) as con:
        print(f'Consulta: {args.query!r}; unitat {args.unit}. Cal fer la selecció MANUAL.')
        for retailer,name,brand,price,qty,unit,line in candidates(con,args.query,args.unit):
            print(f'- {retailer} #{line}: {name} | {brand} | {price/1000:.3f} € / {qty/1000:g} {unit}')

if __name__=='__main__': main()
