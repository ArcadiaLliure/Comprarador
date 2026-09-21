"""Actualització idempotent del prototip a la versió 0.1."""
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text, old, new, label):
    if new in text:
        return text
    if text.count(old) != 1:
        raise RuntimeError(f"No s'ha trobat una única àncora per a {label}: {text.count(old)}")
    return text.replace(old, new, 1)


activity = ROOT / 'app/src/main/java/com/comprarador/app/MainActivity.kt'
s = activity.read_text(encoding='utf-8')
s = replace_once(s, '    private lateinit var ledger: SavingsLedger\n',
    '    private lateinit var ledger: SavingsLedger\n    private lateinit var history: PurchaseHistory\n', 'historial')
s = replace_once(s, '            ledger = SavingsLedger(this)\n',
    '            ledger = SavingsLedger(this)\n            history = PurchaseHistory(this)\n', 'inicialització')
s = replace_once(s, '        main.addView(button(getString(R.string.piggy_refresh)) { updatePiggyBank() })\n',
    '        main.addView(button(getString(R.string.piggy_refresh)) { updatePiggyBank() })\n'
    '        main.addView(button(getString(R.string.top_title)) {\n'
    '            startActivity(android.content.Intent(this, TopPurchasesActivity::class.java))\n'
    '        })\n', 'accés a Top compres')
s = replace_once(s, '            val selected = row.matches.selectedItemPosition\n            if (selected <= 0 || selected > row.candidates.size) continue\n',
    '            val checkedQuantity = try { ReceiptParser.moneyMilli(row.quantity.text.toString()) }\n'
    '                                  catch (_: Exception) {\n'
    '                                      summary.text = getString(R.string.invalid_quantity, row.name.text.toString())\n'
    '                                      return\n'
    '                                  }\n'
    '            if (checkedQuantity <= 0L) {\n'
    '                summary.text = getString(R.string.invalid_quantity, row.name.text.toString())\n'
    '                return\n'
    '            }\n'
    '            val selected = row.matches.selectedItemPosition\n'
    '            if (selected <= 0 || selected > row.candidates.size) continue\n', 'quantitats de totes les línies')
s = replace_once(s, '        saveButton.isEnabled = diff != 0L\n',
    '        saveButton.isEnabled = true // També conservem les compres sense diferència de preu.\n',
    'desament de compres amb saldo zero')
s = replace_once(s,
    '        if (saving == 0L) { saveButton.isEnabled = false; status.text = getString(R.string.not_saving); return }\n',
    '', 'moviments amb saldo zero')
s = replace_once(s,
    '            ))\n            status.text = if (inserted) getString(R.string.saving_recorded, euro(saving))',
    '''            ))
            // El registre personal és idempotent: també repara un desament anterior incomplet.
            val purchaseLines = rows.map { row ->
                val selected = row.candidates.getOrNull(row.matches.selectedItemPosition - 1)
                val unitPrice = selected?.takeIf { it.quantityMilli > 0L }?.let { product ->
                    BigDecimal(product.priceMilli).multiply(BigDecimal(1000))
                        .divide(BigDecimal(product.quantityMilli), 0, RoundingMode.HALF_UP).longValueExact()
                }
                PurchasedItem(
                    description = row.name.text.toString(),
                    currentMilli = ReceiptParser.moneyMilli(row.amount.text.toString()),
                    quantityMilli = ReceiptParser.moneyMilli(row.quantity.text.toString()),
                    unit = row.unit.selectedItem.toString(),
                    historicalId = selected?.id,
                    historicalUnitMilli = unitPrice
                )
            }
            history.record(fingerprint, now, purchaseLines)
            status.text = if (inserted) getString(R.string.saving_recorded, euro(saving))''',
    'desament íntegre del tiquet')
s = replace_once(s, '        if (::catalog.isInitialized) catalog.close()\n',
    '        if (::catalog.isInitialized) catalog.close()\n'
    '        if (::history.isInitialized) history.close()\n'
    '        if (::ledger.isInitialized) ledger.close()\n', 'tancament SQLite')
# Refinament de les targetes del formulari existent.
s = s.replace('setBackgroundColor(Color.rgb(248, 250, 248))',
              'setBackgroundColor(Color.rgb(245, 248, 245))')
s = s.replace('setBackgroundColor(Color.WHITE)\n        }\n        group.addView',
              'background = android.graphics.drawable.GradientDrawable().apply {\n'
              '                setColor(Color.WHITE)\n'
              '                cornerRadius = dp(18).toFloat()\n'
              '            }\n            elevation = dp(2).toFloat()\n'
              '        }\n        group.addView')
activity.write_text(s, encoding='utf-8')

manifest = ROOT / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text(encoding='utf-8')
old = '''        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="unspecified">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>'''
new = '''        <activity android:name=".DashboardActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".TopPurchasesActivity" android:exported="false" />
        <activity android:name=".MainActivity" android:exported="false"
            android:screenOrientation="unspecified" />'''
s = replace_once(s, old, new, 'portada del manifest')
manifest.write_text(s, encoding='utf-8')

gradle = ROOT / 'app/build.gradle.kts'
s = gradle.read_text(encoding='utf-8')
s = replace_once(s, 'versionCode = 2', 'versionCode = 3', 'codi de versió')
s = replace_once(s, 'versionName = "0.2.0"', 'versionName = "0.1.0"', 'versió 0.1')
gradle.write_text(s, encoding='utf-8')

chart = ROOT / 'app/src/main/java/com/comprarador/app/TopPurchasesActivity.kt'
s = chart.read_text(encoding='utf-8').replace('fillViewport = true', 'isFillViewport = true')
chart.write_text(s, encoding='utf-8')

contract = ROOT / 'tests/test_ledger_contract.py'
s = contract.read_text(encoding='utf-8').replace(
    "self.assertIn('saveButton.isEnabled = diff != 0L', ACTIVITY)",
    "self.assertIn('saveButton.isEnabled = true', ACTIVITY)\n"
    "        self.assertIn('history.record(fingerprint, now, purchaseLines)', ACTIVITY)")
contract.write_text(s, encoding='utf-8')

# Les cadenes originals del catàleg no es tradueixen: són dades de procedència.
keys = ('top_title top_subtitle top_search top_period_all top_period_week top_period_month '
        'top_period_year top_empty top_purchases top_spent top_quantity top_detail top_back '
        'top_price_unit top_graph_note top_one_point top_historic top_graph_accessibility '
        'home_subtitle home_piggy_note home_scan home_scan_desc home_top_desc home_privacy '
        'home_balance_accessibility home_balance_unavailable').split()
translations = {
'ca': [
 'Top compres','Els vostres articles més freqüents','Cerca un producte','Sempre','Setmana','Mes','Any',
 'Encara no hi ha compres registrades amb aquest filtre.','%1$d compres','Despesa','Quantitat',
 'Evolució del preu registrat','← Torna al rànquing','Preu per %1$s',
 'Cada punt és un tiquet; la línia discontínua és la referència del 2003.',
 'Només hi ha un preu registrat: calen més compres per veure una evolució.',
 'Referència del 2003','Gràfic amb %1$d preus registrats',
 'Les vostres compres, amb perspectiva.','Balanç nominal de les compres comparades amb el 2003.',
 'Escaneja un tiquet','Compareu els preus i registreu els articles.',
 'Consulteu els articles freqüents i la seva evolució.',
 'Tot es desa al dispositiu. Sense anuncis ni enviament de tiquets.',
 'Saldo de la guardiola: %1$s','Saldo no disponible'],
'es': [
 'Top compras','Tus artículos más frecuentes','Buscar un producto','Siempre','Semana','Mes','Año',
 'Todavía no hay compras registradas con este filtro.','%1$d compras','Gasto','Cantidad',
 'Evolución del precio registrado','← Volver al ranking','Precio por %1$s',
 'Cada punto es un tique; la línea discontinua es la referencia de 2003.',
 'Solo hay un precio registrado: hacen falta más compras para ver su evolución.',
 'Referencia de 2003','Gráfico con %1$d precios registrados',
 'Tus compras, con perspectiva.','Balance nominal de las compras comparadas con 2003.',
 'Escanear un tique','Compara precios y registra los artículos.',
 'Consulta los artículos frecuentes y su evolución.',
 'Todo se guarda en el dispositivo. Sin anuncios ni envío de tiques.',
 'Saldo de la hucha: %1$s','Saldo no disponible'],
'gl': [
 'Top compras','Os teus artigos máis frecuentes','Buscar un produto','Sempre','Semana','Mes','Ano',
 'Aínda non hai compras rexistradas con este filtro.','%1$d compras','Gasto','Cantidade',
 'Evolución do prezo rexistrado','← Volver á clasificación','Prezo por %1$s',
 'Cada punto é un tícket; a liña descontinua é a referencia de 2003.',
 'Só hai un prezo rexistrado: fan falta máis compras para ver a evolución.',
 'Referencia de 2003','Gráfico con %1$d prezos rexistrados',
 'As túas compras, con perspectiva.','Balance nominal das compras comparadas con 2003.',
 'Escanear un tícket','Compara prezos e rexistra os artigos.',
 'Consulta os artigos frecuentes e a súa evolución.',
 'Todo se garda no dispositivo. Sen anuncios nin envío de tíckets.',
 'Saldo da hucha: %1$s','Saldo non dispoñible'],
'eu': [
 'Erosketa nagusiak','Gehien erosten dituzun produktuak','Bilatu produktua','Beti','Astea','Hilabetea','Urtea',
 'Ez dago iragazki honekin erregistratutako erosketarik.','%1$d erosketa','Gastua','Kopurua',
 'Erregistratutako prezioaren bilakaera','← Itzuli sailkapenera','Prezioa %1$s bakoitzeko',
 'Puntu bakoitza tiketa da; marra etena 2003ko erreferentzia da.',
 'Prezio bakarra dago erregistratuta: erosketa gehiago behar dira bilakaera ikusteko.',
 '2003ko erreferentzia','%1$d prezioren grafikoa',
 'Zure erosketak, ikuspegi zabalagoarekin.','2003arekin alderatutako erosketen saldo nominala.',
 'Eskaneatu tiketa','Alderatu prezioak eta erregistratu produktuak.',
 'Ikusi ohiko produktuak eta haien bilakaera.',
 'Dena gailuan gordetzen da. Iragarkirik eta tiket-bidalketarik gabe.',
 'Itsulapikoaren saldoa: %1$s','Saldoa ez dago erabilgarri'],
'oc': [
 'Top crompes','Vòstres articles mai crompats','Cercar un produit','Totjorn','Setmana','Mes','An',
 'I a pas encara de crompas enregistradas amb aquel filtre.','%1$d crompas','Despensa','Quantitat',
 'Evolucion del prètz enregistrat','← Tornar al classament','Prètz per %1$s',
 'Cada punt es un tiquet; la linha discontinua es la referéncia de 2003.',
 'I a un sol prètz enregistrat: cal mai de crompas per veire una evolucion.',
 'Referéncia de 2003','Grafic amb %1$d prètzes enregistrats',
 'Vòstres crompas, amb perspectiva.','Balanç nominal de las crompas comparadas amb 2003.',
 'Numerizar un tiquet','Comparatz los prètzes e enregistratz los articles.',
 'Consultatz los articles frequents e lor evolucion.',
 'Tot es enregistrat dins lo dispositiu. Sens publicitat ni mandadís de tiquets.',
 'Sòlde de la tireta: %1$s','Sòlde indisponible'],
'ast': [
 'Top compres','Los artículos que más compres','Buscar un productu','Siempres','Selmana','Mes','Añu',
 'Entá nun hai compres rexistraes con esti filtru.','%1$d compres','Gastu','Cantidá',
 'Evolución del preciu rexistráu','← Volver a la clasificación','Preciu por %1$s',
 'Cada puntu ye un tique; la llinia discontinua ye la referencia de 2003.',
 'Namás hai un preciu rexistráu: faen falta más compres pa ver la evolución.',
 'Referencia de 2003','Gráficu con %1$d precios rexistraos',
 'Les tos compres, con perspectiva.','Balance nominal de les compres comparaes con 2003.',
 'Escaniar un tique','Compara precios y rexistra los artículos.',
 'Consulta los artículos frecuentes y la so evolución.',
 'Too se guarda nel dispositivu. Ensin anuncios nin unviar tiques.',
 'Saldo de la hucha: %1$s','Saldo non disponible'],
'an': [
 'Top compras','Os articlos que más compras','Buscar un producto','Siempre','Semana','Mes','Any',
 'Encara no bi ha compras rechistradas con iste filtro.','%1$d compras','Gasto','Cantidat',
 'Evolución d\'o pre precio rechistrau','← Tornar ta la clasificación','Precio por %1$s',
 'Cada punto ye un tiquet; a linia discontinua ye a referencia de 2003.',
 'Nomás bi ha un precio rechistrau: calen más compras ta veyer a evolución.',
 'Referencia de 2003','Grafico con %1$d precios rechistraus',
 'As tuyas compras, con perspectiva.','Balanç nominal d\'as compras comparadas con 2003.',
 'Escaniar un tiquet','Compara precios y rechistra os articlos.',
 'Consulta os articlos freqüents y a suya evolución.',
 'Tot se alza en o dispositivo. Sin anuncios ni ninviar tiquets.',
 'Saldo d\'a guardiola: %1$s','Saldo no disponible']
}
# Els recursos predeterminats i el català compartixen la mateixa traducció.
for locale, values in [('values', translations['ca'])] + [(f'values-{k}', v) for k, v in translations.items()]:
    assert len(values) == len(keys), (locale, len(values), len(keys))
    folder = ROOT / 'app/src/main/res' / locale
    folder.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for key, value in zip(keys, values):
        safe = escape(value.replace("'", "\\'"))
        lines.append(f'    <string name="{key}">{safe}</string>')
    lines.append('</resources>')
    (folder / 'strings_top.xml').write_text('\n'.join(lines) + '\n', encoding='utf-8')

readme = ROOT / 'README.md'
s = readme.read_text(encoding='utf-8')
section = '''\n## Versió 0.1 · Top compres i evolució\n\nLa portada renovada dona accés a l'escàner i a **Top compres**. Els tiquets confirmats\nes desglossen en una base SQLite privada (`purchase_history.sqlite`) amb taules de\ntiquets, productes canònics i línies. El rànquing compta tiquets diferents per producte,\namb cercador i filtres de setmana, mes, any i tot el període. Cada targeta mostra un\ngràfic del **preu real per unitat** amb els imports pagats a les diferents dates de\nregistre i, si n'hi ha, la referència nominal de 2003. El gràfic no és una sèrie\nde preus de mercat: mostra únicament les compres del dispositiu.\n\nL'historial de la guardiola anterior conserva els seus saldos però no inclou els\narticles originals: **no se'n poden reconstruir les línies retroactivament**.\nUna mateixa empremta de tiquet no pot duplicar les estadístiques. Dues compres\ndiferents amb un contingut idèntic poden compartir empremta; caldrà millorar la\nidentificació abans de publicar un rànquing entre usuaris. Les equivalències\nhistòriques continuen requerint confirmació manual.\n\n**Pendent per a la fase de publicitat:** decidir el model publicitari i implementar\nla informació de privadesa i la gestió del consentiment quan es defineixin\nels proveïdors i les finalitats de tractament. No s'inclou cap SDK publicitari\nni cap transmissió de tiquets en aquesta versió.\n'''
if '## Versió 0.1 · Top compres i evolució' not in s:
    readme.write_text(s + section, encoding='utf-8')

print('Versió 0.1, historial, navegació i traduccions preparats.')
