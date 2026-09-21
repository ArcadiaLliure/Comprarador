#!/usr/bin/env python3
"""Normalitza els preus del 2003 de Jaime Obregón en un catàleg SQLite local.

Només cal la biblioteca estàndard de Python 3. `--download` baixa els CSV
durant la construcció, mai durant l’ús. Conserva cada línia o en registra el rebuig.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
import urllib.request
from collections import Counter
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = '22e172b41503bf77a2fd8d966f9339a33e1b5468'
URL = 'https://raw.githubusercontent.com/JaimeObregon/precios/' + SOURCE_COMMIT + '/{name}.csv'
EXPECTED_GIT_BLOBS = {
    'carrefour': 'be8707a4233a23b1d89d3d2fa0bd8a082f98ec52',
    'eroski': 'e033e51347f15e32c526ff9b4f972b933aeeac52',
}

def verify_upstream_blob(data: bytes, retailer: str):
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()
    if actual != EXPECTED_GIT_BLOBS[retailer]:
        raise RuntimeError(f'{retailer}: La suma SHA de Git no coincideix amb la revisió fixada: {actual}')

DATE = '2003-01-06'
ALIASES = {
    'ecologica': 'ecologico', 'eco': 'ecologico', 'manz': 'manzana', 'manzanas':'manzana', 'poma':'manzana', 'pomes':'manzana',
    'platano':'platano', 'platanos':'platano', 'platan':'platano', 'bananas':'platano',
    'patatas':'patata', 'patates':'patata', 'llet':'leche', 'semi':'semidesnatada',
    'semides':'semidesnatada', 'semi-desnatada':'semidesnatada', 'ent':'entera',
    'desnat':'desnatada', 'tomates':'tomate', 'tomaquet':'tomate',
    'aceit':'aceite', 'oliva':'oliva', 'ous':'huevos', 'huevos':'huevo',
    'kg':'kg', 'gr':'g', 'grs':'g', 'litro':'l', 'litros':'l',
}
STOP = {'de','del','la','el','en','para','y','con','sin','aprox','aproximado',
        'unidad','unidades','unid','ud','u','bolsa','malla','botella','bandeja',
        'pack','envase','cal','calibre','unos','una','un','los','las','por','tipo'}
PACK = re.compile(r'(?<![\w/])(?P<number>\d{1,4}(?:[.,]\d{1,3})?)\s*(?P<unit>kg|kilogramos?|g|gr|gramos?|litros?|l|cl|ml|uds?\.?|unidades?|unid\.?)(?![\w/])', re.I)
UNIT_PRICE = re.compile(r'\b(?:euros?|€)\s*/\s*(?:kg|l|litro|unidad|ud)\b', re.I)


def normalize(s: str) -> str:
    s = unicodedata.normalize('NFKD', s.casefold())
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r'(?<=\d)[.,](?=\d)', ' ', s)
    words = re.findall(r'[a-z0-9]+', s)
    return ' '.join(ALIASES.get(w, w) for w in words if w not in STOP)


def quantity_from(label: str):
    """Retorna mil·lèsimes de kg/l/unitats; no dedueix el pes del preu per kg."""
    scrubbed = re.sub(r'\([^)]*(?:euros?\s*/|€\s*/)[^)]*\)', '', label, flags=re.I)
    matches = list(PACK.finditer(scrubbed))
    if not matches:
        return None, None
    # La primera massa o volum sol indicar el contingut; altrament, fem servir unitats.
    selected = next((m for m in matches if m.group('unit').lower().strip('.') not in ('ud','uds','unid','unidad','unidades')), matches[0])
    n = Decimal(selected.group('number').replace(',', '.'))
    unit = selected.group('unit').lower().rstrip('.')
    if unit in ('kg','kilogramo','kilogramos'):
        q, canonical = n * 1000, 'kg'
    elif unit in ('g','gr','gramo','gramos'):
        q, canonical = n, 'kg'
    elif unit in ('l','litro','litros'):
        q, canonical = n * 1000, 'l'
    elif unit == 'cl':
        q, canonical = n * 10, 'l'
    elif unit == 'ml':
        q, canonical = n, 'l'
    else:
        q, canonical = n * 1000, 'ud'
    if q <= 0:
        return None, None
    return int(q.to_integral_value(rounding=ROUND_HALF_UP)), canonical


def euro_milli(s: str) -> int:
    v = Decimal(s.strip().replace(',', '.'))
    if not v.is_finite() or v < 0:
        raise ValueError('Preu no vàlid')
    return int((v * 1000).to_integral_value(rounding=ROUND_HALF_UP))


def parse(line: str, retailer: str):
    if retailer == 'eroski':
        # La separació per la dreta tolera punts i comes dins les descripcions.
        fields = line.rsplit(';', 2)
        if len(fields) != 3:
            raise ValueError('S’esperaven descripció;euros;pessetes')
        label, price, pesetas = fields
        pesetas = pesetas.strip()
        if pesetas and not re.fullmatch(r'\d+', pesetas):
            raise ValueError('Pessetes no numèriques')
        brand, package = '', ''
    else:
        fields = line.rsplit(';', 3)
        if len(fields) != 4:
            raise ValueError('S’esperaven descripció;marca;format;euros')
        label, brand, package, price = fields
        pesetas = None
    label = label.strip().strip('"').strip()
    brand = brand.strip().strip('"').strip()
    package = package.strip().strip('"').strip()
    if not label:
        raise ValueError('Descripció buida')
    amount = euro_milli(price)
    qty, unit = quantity_from(f'{label} {package}')
    searchable = normalize(f'{label} {brand}')
    if not searchable:
        raise ValueError('Sense termes indexables')
    return label, brand, package, searchable, amount, pesetas, qty, unit


def download(directory: Path):
    for retailer in ('carrefour', 'eroski'):
        p = directory / f'{retailer}.csv'
        if p.exists():
            verify_upstream_blob(p.read_bytes(), retailer)
            print(f'Es reutilitza el fitxer verificat {p}', file=sys.stderr)
            continue
        request = urllib.request.Request(URL.format(name=retailer), headers={'User-Agent':'Comprarador-catalog-builder/0.1'})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read()
        if len(data) < 100_000:
            raise RuntimeError(f'{retailer}: descàrrega inesperadament petita ({len(data)} bytes)')
        verify_upstream_blob(data, retailer)
        p.write_bytes(data)
        print(f'S’ha baixat {p} ({len(data)} bytes)', file=sys.stderr)


def build(directory: Path, output: Path, rejects_file: Path, allow_rejects: bool = False):
    output.parent.mkdir(parents=True, exist_ok=True)
    rejects_file.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    con = sqlite3.connect(output)
    con.executescript('''
      PRAGMA page_size=4096;
      CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE products(
        id INTEGER PRIMARY KEY,
        retailer TEXT NOT NULL CHECK(retailer IN ('eroski','carrefour')),
        source_line INTEGER NOT NULL,
        source_text TEXT NOT NULL,
        name TEXT NOT NULL,
        brand TEXT NOT NULL,
        packaging TEXT NOT NULL,
        normalized_name TEXT NOT NULL,
        price_milli_eur INTEGER NOT NULL CHECK(price_milli_eur>=0),
        pesetas INTEGER,
        quantity_milli INTEGER CHECK(quantity_milli>0),
        quantity_unit TEXT CHECK(quantity_unit IN ('kg','l','ud')),
        observed_on TEXT NOT NULL,
        UNIQUE(retailer,source_line)
      );
      CREATE INDEX idx_products_name ON products(normalized_name);
      CREATE VIRTUAL TABLE products_fts USING fts4(normalized_name, tokenize=unicode61);
    ''')
    stats = Counter()
    provenance = {}
    with rejects_file.open('w', encoding='utf-8') as rejects:
        for retailer in ('carrefour', 'eroski'):
            file = directory / f'{retailer}.csv'
            if not file.is_file():
                raise FileNotFoundError(f'Manca {file}. Feu servir --download per obtenir els CSV originals.')
            data = file.read_bytes()
            provenance[f'{retailer}_sha256'] = hashlib.sha256(data).hexdigest()
            text = data.decode('utf-8-sig')
            # Cada registre ocupa una línia física. Llegim des de la dreta
            # per evitar desplaçaments dels preus si hi ha separadors interns.
            for n, line in enumerate(text.splitlines(), 1):
                stats[f'{retailer}_input'] += 1
                if not line.strip():
                    rejects.write(json.dumps({'file':retailer,'line':n,'reason':'Línia buida','raw':line},ensure_ascii=False)+'\n')
                    stats[f'{retailer}_rejected'] += 1
                    continue
                try:
                    label, brand, package, norm, price, pesetas, qty, unit = parse(line, retailer)
                    cur = con.execute('''INSERT INTO products
                      (retailer,source_line,source_text,name,brand,packaging,normalized_name,
                       price_milli_eur,pesetas,quantity_milli,quantity_unit,observed_on)
                      VALUES (?,?,?,?,?,?,?,?,?,?,?,?)''',
                      (retailer,n,line,label,brand,package,norm,price,int(pesetas) if pesetas else None,qty,unit,DATE))
                    con.execute('INSERT INTO products_fts(rowid,normalized_name) VALUES(?,?)', (cur.lastrowid,norm))
                    stats[f'{retailer}_imported'] += 1
                    if qty is None:
                        stats[f'{retailer}_unknown_quantity'] += 1
                except (ValueError, InvalidOperation, sqlite3.DatabaseError) as ex:
                    rejects.write(json.dumps({'file':retailer,'line':n,'reason':str(ex),'raw':line},ensure_ascii=False)+'\n')
                    stats[f'{retailer}_rejected'] += 1
    for key, value in {'snapshot_date':DATE,'source_repo':'https://github.com/JaimeObregon/precios',
                       'source_license':'WTFPL','source_commit':SOURCE_COMMIT,'schema_version':'1',**provenance}.items():
        con.execute('INSERT INTO metadata VALUES(?,?)',(key,value))
    integrity = con.execute('PRAGMA integrity_check').fetchone()[0]
    indexed = con.execute('SELECT count(*) FROM products_fts').fetchone()[0]
    total = con.execute('SELECT count(*) FROM products').fetchone()[0]
    if integrity != 'ok' or indexed != total:
        raise RuntimeError(f'La comprovació d’integritat ha fallat: {integrity}, indexed {indexed}, total {total}')
    con.commit()
    con.execute('VACUUM')
    con.close()
    report = dict(stats)
    report.update({'products_total':total,'indexed_total':indexed,'integrity':integrity,
                   'output':str(output),'rejects_file':str(rejects_file), **provenance})
    report_path = output.with_suffix('.report.json')
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if any(v for k,v in stats.items() if k.endswith('_rejected')) and not allow_rejects:
        raise RuntimeError('Hi ha registres rebutjats. Reviseu rejects.jsonl i feu servir --allow-rejects de manera conscient.')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, default=ROOT/'data'/'original')
    parser.add_argument('--output', type=Path, default=ROOT/'app'/'src'/'main'/'assets'/'catalog.sqlite')
    parser.add_argument('--rejects', type=Path, default=ROOT/'data'/'rejects.jsonl')
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--allow-rejects', action='store_true')
    args = parser.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    if args.download:
        download(args.data_dir)
    build(args.data_dir,args.output,args.rejects,args.allow_rejects)

if __name__ == '__main__':
    main()
