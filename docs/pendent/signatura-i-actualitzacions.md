# Pendent — Signatura Android i actualitzacions sense pèrdua de dades

## Problema verificat

Les compilacions `assembleDebug` de GitHub Actions utilitzen una clau de depuració generada a l'entorn de compilació. Els certificats poden variar entre execucions: Android pot rebutjar una actualització amb una altra signatura, tot i que `versionCode` augmenti. Desinstal·lar l'aplicació pot esborrar `purchase_history.sqlite` i `savings.sqlite` perquè `allowBackup=false`.

## Condicions abans d'una distribució estable

- Generar una clau de signatura pròpia i conservar-la fora del repositori públic; custodiar-la a secrets de GitHub Actions i definir còpies de seguretat i rotació.
- Compilar i signar APK o AAB de distribució amb el mateix certificat en totes les revisions; verificar `versionCode` creixent, certificat i `apksigner verify` en CI.
- Crear un mecanisme d'exportació i importació local de l'historial i de la guardiola, amb integritat i consentiment explícit de l'usuari, abans de canviar el certificat de depuració.
- Provar la instal·lació i l'actualització reals en un dispositiu Android, incloses migracions SQLite i retenció del saldo i de les compres.
- No publicar ni emmagatzemar claus privades de producció al codi font, als artefactes ni als registres d'Actions.

**Estat:** pendent. No desinstal·leu cap versió que contingui dades que vulgueu conservar.
