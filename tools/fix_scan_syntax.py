"""Correcció puntual de la sintaxi Kotlin durant la integració de l'escàner."""
from pathlib import Path

path = Path('app/src/main/java/com/comprarador/app/ScanReceiptActivity.kt')
lines = path.read_text(encoding='utf-8').splitlines(keepends=True)
indexes = [i for i, line in enumerate(lines) if 'append(ocrText.trim().replace(' in line]
assert len(indexes) == 1, 'Cal una única línia de normalització del tiquet'
i = indexes[0]
if '" ")))' in lines[i]:
    lines[i] = lines[i].replace('" ")))', '" "))', 1)
    path.write_text(''.join(lines), encoding='utf-8')
else:
    assert '" "))' in lines[i], 'La línia ja no coincideix amb la forma esperada'
