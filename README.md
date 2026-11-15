# Lieferschein unterschreiben – Android-App

Ablauf in der App:
1. **Ordner wählen** (einmalig): den Hauptordner mit den Lieferscheinen. Unterordner werden automatisch mit durchsucht.
2. **Nummer scannen** (Barcode oder QR-Code per Kamera) oder von Hand eingeben.
3. Die App sucht eine PDF, deren Dateiname die Nummer enthält. Bei genau einem Treffer öffnet sie sich direkt, bei mehreren Treffern wählst du aus einer Liste.
4. Mit dem Stift direkt auf das Dokument unterschreiben (mit ◀ ▶ zwischen den Seiten wechseln).
5. **Speichern**: Die Unterschrift wird als Vektorgrafik in die PDF geschrieben und die **Originaldatei überschrieben**.

## APK über GitHub (ohne Android Studio)
Bei jedem Push auf `main` baut GitHub Actions die App automatisch:
**Actions** → letzter Lauf „APK bauen“ → unten bei *Artifacts* **Lieferschein-Sign-APK** herunterladen → entpacken → APK auf dem Tablet installieren (Installation aus unbekannten Quellen erlauben).
Ein Build lässt sich auch von Hand starten: Actions → „APK bauen“ → *Run workflow*.

## Bauen (Android Studio)
1. Android Studio (aktuelle Version) installieren.
2. `File > Open` → den Ordner `LieferscheinSigner` öffnen. Gradle-Sync abwarten. Wenn Studio ein Upgrade des Gradle-Plugins vorschlägt, kannst du es annehmen.
3. Tablet per USB verbinden (Entwickleroptionen + USB-Debugging aktivieren) → ▶ Run.
   Oder `Build > Build App Bundle(s)/APK(s) > Build APK(s)` → APK liegt unter `app/build/outputs/apk/debug/`.

Hinweis: Die Datei `gradle-wrapper.jar` liegt nicht bei. Android Studio kommt ohne sie aus. Für Builds per Kommandozeile: einmal `gradle wrapper` ausführen.

## Voraussetzungen und Hinweise
- **Google-Play-Dienste** müssen auf dem Tablet vorhanden sein (für den Scanner). Eine Kamera-Berechtigung braucht die App nicht.
- **„Nur Stift“** (Standard: an): Nur der Stift zeichnet, Finger und Handballen werden ignoriert. Wenn dein Stift nicht als Stift erkannt wird (z. B. bei einfachen kapazitiven Stiften), schalte die Option aus.
- **Suchregel:** Die Nummer muss im Dateinamen vorkommen und darf nicht direkt an weitere Ziffern grenzen. Die Suche nach `4711` findet also `LS_4711.pdf` und `4711 Müller.pdf`, aber nicht `47110.pdf`. Ein exakter Treffer (`4711.pdf`) steht immer oben.
- **Netzlaufwerke:** Funktioniert mit allem, was in der Android-Dateiauswahl als Ordner erscheint (interner Speicher, SD-Karte, USB). Für SMB-/NAS-Freigaben brauchst du eine App, die die Freigabe als Dokumentanbieter einbindet (z. B. „CIFS Documents Provider“).
- Zuerst wird die unterschriebene PDF komplett im App-Cache erzeugt und erst danach über das Original geschrieben. So bleibt das Original erhalten, wenn beim Erzeugen etwas schiefgeht.

## Aufbau
| Datei | Zweck |
|---|---|
| `MainActivity.kt` | Ordnerwahl, Scanner, Suche, Trefferliste |
| `FileSearch.kt` | Rekursive, schnelle Suche über das Storage Access Framework |
| `SignActivity.kt` | PDF anzeigen, Seiten blättern, speichern und überschreiben |
| `SignatureCanvasView.kt` | Zeichenfläche für den Stift (mit Palm Rejection) |
| `PdfSigner.kt` | Schreibt die Striche mit PdfBox-Android in die PDF (Seitendrehung wird berücksichtigt) |
