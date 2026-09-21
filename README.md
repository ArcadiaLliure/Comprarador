# Comprarador

**Prototip Android per comparar un tiquet actual amb els preus de Carrefour i Eroski del 6 de gener del 2003.** El reconeixement de text (OCR), les cerques i els càlculs es fan al dispositiu. No cal crear cap compte ni enviar imatges o tiquets a cap servidor. L'aplicació pot funcionar sense connexió un cop instal·lada amb el catàleg incorporat.

**Autoria i manteniment: Manel · [ArcadiaLliure](https://github.com/ArcadiaLliure).**

## Funcionalitats

- Captura d'una fotografia o selecció d'una imatge, OCR local amb un model integrat, correcció manual de les línies i cerca d'equivalències en una base SQLite local amb FTS4.
- Comparació **nominal i orientativa** únicament dels articles que la persona usuària hagi emparellat explícitament amb una referència històrica. Es mostren la procedència, les quantitats i la cobertura de la comparació. Una cistella parcial no es presenta com si fos la compra sencera.
- **Guardiola local amb saldo amb signe**: cada compra confirmada registra `total_històric_comparable - total_actual_comparable`. Una compra actual més barata suma; una compra més cara resta. Si no hi ha diferència, no es genera cap moviment. Els saldos es poden consultar per dia, setmana (de dilluns a diumenge), mes, any i acumulat.
- La guardiola es desa en una base SQLite separada del catàleg. Els registres inclouen un identificador únic, l'empremta SHA-256 del contingut i de les equivalències, les sumes i la cobertura. La mateixa empremta evita comptar dues vegades un tiquet encara que s'escanegi un altre dia; dues compres autèntiques amb el mateix contingut podrien requerir una identificació addicional.
- La interfície selecciona els recursos lingüístics segons la configuració d'Android i fa servir el català com a llengua predeterminada. Hi ha traduccions per a altres llengües, pendents de revisió humana i de proves amb dispositius reals. Les denominacions comercials originals no es tradueixen perquè formen part de les dades de procedència.

## Límits de la comparació

Les dades històriques són una captura d'un sol dia de **dues cadenes comercials**; no representen els preus mitjans de tot l'Estat ni una sèrie temporal. No s'hi aplica l'IPC, ni s'hi reconstrueixen impostos, promocions o disponibilitat històrica. El saldo de la guardiola no és diner dipositat ni estalvi real respecte d'una alternativa de compra actual, i no mesura directament el poder adquisitiu. Les línies sense equivalència no entren en el càlcul; cal revisar manualment els descomptes, els productes a pes, els lots i els preus que l'OCR pugui haver interpretat malament.

## Construcció i verificació

Cal disposar de Python 3, JDK 17, Android SDK Platform 35, Build Tools 35 i Gradle 8.9. Des de l'arrel del repositori:

```bash
python3 tools/build_catalog.py --download --allow-rejects
python3 -m unittest discover -s tests -v
gradle :app:assembleDebug
```

El fitxer resultant, **només si la compilació acaba correctament**, és `app/build/outputs/apk/debug/app-debug.apk`. També podeu consultar [les execucions de GitHub Actions](https://github.com/ArcadiaLliure/Comprarador/actions): una execució correcta adjunta l'artefacte `Comprarador-debug-apk`. És un prototip de depuració, no una versió de producció. La descàrrega dels CSV es fa durant la construcció, mai als telèfons de les persones usuàries. Abans de donar per complet el catàleg, cal revisar el fitxer `data/rejects.jsonl` i l'informe `app/src/main/assets/catalog.report.json`; l'opció `--allow-rejects` no amaga els registres descartats. Si es vol aturar la construcció amb qualsevol registre rebutjat, s'ha d'ometre aquesta opció.

## Ús

Feu una fotografia del tiquet o obriu-ne una imatge. Reviseu i corregiu el text extret; analitzeu el tiquet i verifiqueu, per a cada producte, descripció, preu, quantitat i unitat. Cerqueu una equivalència del 2003, trieu-la explícitament o excloeu l'article. Compareu les cistelles i reviseu-ne la cobertura. Finalment, **confirmeu el moviment** per registrar a la guardiola una diferència positiva o negativa. La comparació sola no modifica el saldo.

Les quantitats històriques han de tenir la mateixa unitat que l'article comparat (quilograms, litres o unitats). La precisió interna és de mil·lèsimes d'euro i la presentació es pot arrodonir a cèntims. Un preu ambigu o una equivalència incorrecta s'ha de corregir abans de confirmar-lo.

## Procedència i llicències

Les dades provenen del repositori [«precios», de Jaime Obregón](https://github.com/JaimeObregon/precios), amb una captura del 6 de gener del 2003 i la revisió fixada `22e172b41503bf77a2fd8d966f9339a33e1b5468`. La llicència indicada a la font és [WTFPL](https://www.wtfpl.net/). L'importador verifica els fitxers originals, en conserva el text literal, la cadena i la línia de procedència i desa els preus en mil·lèsimes d'euro. Les dades originals no inclouen identificadors EAN/GTIN. El codi de Comprarador es distribueix sota la llicència MIT (vegeu `LICENSE`).

## Privadesa i persistència

El prototip no inclou comptes, sincronització ni classificacions en línia. La guardiola és local i es pot perdre si es desinstal·la l'aplicació o se n'esborren les dades; `allowBackup=false`. La fotografia no s'emmagatzema a la base de dades de la guardiola. Una eventual classificació compartida necessitaria una metodologia comparable, consentiment exprés, autenticació i mesures contra la manipulació.

## Versió 0.1 · Top compres i evolució

La portada renovada dona accés a l'escàner i a **Top compres**. Els tiquets confirmats
es desglossen en una base SQLite privada (`purchase_history.sqlite`) amb taules de
tiquets, productes canònics i línies. El rànquing compta tiquets diferents per producte,
amb cercador i filtres de setmana, mes, any i tot el període. Cada targeta mostra un
gràfic del **preu real per unitat** amb els imports pagats a les diferents dates de
registre i, si n'hi ha, la referència nominal de 2003. El gràfic no és una sèrie
de preus de mercat: mostra únicament les compres del dispositiu.

L'historial de la guardiola anterior conserva els seus saldos però no inclou els
articles originals: **no se'n poden reconstruir les línies retroactivament**.
Una mateixa empremta de tiquet no pot duplicar les estadístiques. Dues compres
diferents amb un contingut idèntic poden compartir empremta; caldrà millorar la
identificació abans de publicar un rànquing entre usuaris. Les equivalències
històriques continuen requerint confirmació manual.

**Pendent per a la fase de publicitat:** decidir el model publicitari i implementar
la informació de privadesa i la gestió del consentiment quan es defineixin
els proveïdors i les finalitats de tractament. No s'inclou cap SDK publicitari
ni cap transmissió de tiquets en aquesta versió.
