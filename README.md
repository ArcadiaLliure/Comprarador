# Comprarador

**Aplicació Android experimental per escanejar tiquets, consultar el vostre historial de compra i comparar imports amb les referències de Carrefour i Eroski del 6 de gener del 2003.** L'OCR, les cerques, els gràfics i les dades personals funcionen localment. No cal cap compte, cap servidor ni enviar fotografies a tercers.

**Autoria i manteniment: Manel · [ArcadiaLliure](https://github.com/ArcadiaLliure).** Versió actual: **0.1.1**.

## Instal·lació i versions

- [Descarregueu directament l'APK de prova 0.1.1](https://github.com/ArcadiaLliure/Comprarador/releases/download/v0.1.1/Comprarador-v0.1.1.apk), sense extreure cap ZIP.
- [Versions publicades](https://github.com/ArcadiaLliure/Comprarador/releases) i [compilacions de GitHub Actions](https://github.com/ArcadiaLliure/Comprarador/actions). Una compilació correcta genera també l'artefacte `Comprarador-v0.1.1-debug`.
- **Avís de signatura:** els APK de depuració poden tenir certificats diferents entre execucions. Android pot rebutjar l'actualització d'una instal·lació anterior; no desinstal·leu l'aplicació si voleu conservar l'historial i la guardiola. Abans de distribuir actualitzacions estables cal una clau de signatura persistent, protegida als secrets del repositori, i una estratègia de còpia de seguretat i migració. Aquesta APK no és una versió de botiga.

## Escaneig i indicadors de preu

1. A la portada, toqueu **Escaneja un tiquet**: la càmera del dispositiu s'obre directament. Si cancel·leu la fotografia, podeu tornar a obrir-la o triar una imatge.
2. El reconeixement de text s'executa al telèfon i presenta els articles detectats en **targetes**; no cal passar per un editor gegant del text del tiquet. Reviseu o corregiu el nom, el preu, la quantitat i la unitat de cada article.
3. Cada targeta compara el **preu per unitat amb la darrera compra registrada del mateix article**, sempre amb unitats iguals. **↗ vermell**: ha pujat; **↘ verd**: ha baixat; **= taronja**: no ha canviat. La quantitat monetària de la diferència té el mateix color. Si encara no existeix una compra comparable, la targeta ho indica i no inventa cap variació.
4. La **referència de 2003 és independent** de les fletxes: cerqueu una equivalència i seleccioneu-la explícitament. Les línies sense equivalència es poden desar a l'historial, però no s'afegeixen a la comparació nominal amb 2003.
5. Toqueu **Veure l'evolució del preu** per obrir el mateix detall que a «Top compres». El gràfic mostra el punt de referència de 2003, si hi ha equivalència, seguit dels preus unitaris reals de les compres registrades. La separació horitzontal entre 2003 i la primera compra **no és a escala temporal**. El detall s'habilita quan hi ha historial; després de desar una primera compra, ja es pot consultar.
6. **Desa la compra** confirma les línies a l'SQLite privat. Les targetes sense equivalència històrica també s'hi registren. La guardiola només rep la diferència nominal dels articles amb equivalència confirmada; un import actual superior resta saldo, un d'inferior suma saldo, i la diferència nul·la té saldo zero.

La variació de les fletxes és `preu unitari actual − preu unitari de la darrera compra`. El saldo nominal de la guardiola és `preu de 2003 equivalent − preu actual dels articles comparables`. **No són la mateixa magnitud.** Els càlculs utilitzen mil·lèsimes d'euro internament i presenten imports arrodonits. El reconeixement i les equivalències s'han de revisar abans de confirmar-los; preus promocionals, lots i descomptes poden requerir correcció manual.

## Top compres i dades locals

«Top compres» ordena els articles per nombre de tiquets diferents, amb cercador i filtres de setmana, mes, any i tot el període. El detall mostra la quantitat acumulada, la despesa i una gràfica dels imports efectivament pagats per unitat, juntament amb la referència nominal de 2003 quan existeix.

- `catalog.sqlite`: catàleg històric incorporat, de només lectura, amb indexació FTS4 i procedència de cada registre.
- `purchase_history.sqlite`: base SQLite privada amb **tiquets, productes normalitzats i línies**, sense publicar les dades ni crear comptes. L'historial anterior de la guardiola no conservava les línies; no es poden reconstruir retroactivament.
- `savings.sqlite`: guardiola privada amb saldo positiu i negatiu, totals diari, setmanal, mensual, anual i acumulat. La setmana comença dilluns. Els identificadors i l'empremta SHA-256 eviten duplicats exactes; dues compres diferents amb el mateix contingut poden compartir empremta i requeriran un identificador millor en fases posteriors.

## Límits de les referències de 2003

Són preus d'un únic dia de **dues cadenes**, no una sèrie de preus de mercat ni una mostra representativa de tot l'Estat. La comparació és nominal, sense IPC, i no reconstrueix impostos, promocions o disponibilitat de productes. Una cistella parcial no representa la compra sencera; la guardiola no conté diners dipositats ni mesura l'estalvi real respecte d'una alternativa actual.

## Construcció i verificació

Cal Python 3, JDK 17, Android SDK Platform 35, Build Tools 35 i Gradle 8.9. Des de l'arrel:

```bash
python3 tools/build_catalog.py --download --allow-rejects
python3 -m unittest discover -s tests -v
gradle :app:assembleDebug
```

Després d'una compilació correcta, el fitxer és `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions automatitza les proves, el muntatge del catàleg, la compilació, l'artefacte ZIP i la publicació de l'APK a GitHub Releases quan es crea una versió. Els CSV es descarreguen durant la construcció, mai al telèfon. Cal revisar `data/rejects.jsonl` i `app/src/main/assets/catalog.report.json`; l'opció `--allow-rejects` no amaga els registres rebutjats.

## Llengües, procedència i privadesa

L'aplicació segueix la llengua configurada a Android, amb el català com a llengua predeterminada i una sola localització `ca`. Inclou traduccions per a altres llengües, pendents de revisió lingüística humana. Es conserven sense traduir les denominacions comercials originals de les dades.

Els preus de 2003 provenen del repositori [«precios», de Jaime Obregón](https://github.com/JaimeObregon/precios), revisió fixada `22e172b41503bf77a2fd8d966f9339a33e1b5468` i llicència [WTFPL](https://www.wtfpl.net/). L'importador conserva text original, cadena, línia i import exacte. Els originals no inclouen codis EAN/GTIN. El codi propi de Comprarador té llicència MIT (`LICENSE`).

No hi ha SDK publicitari, comptes, sincronització ni servidor de classificacions en aquesta versió. Les dades locals es poden perdre si es desinstal·la l'app o se n'esborren les dades; `allowBackup=false`. Abans de la fase d'anuncis, cal **definir el model publicitari i resoldre la privadesa i el consentiment**; queda registrat a la [incidència #1](https://github.com/ArcadiaLliure/Comprarador/issues/1).
