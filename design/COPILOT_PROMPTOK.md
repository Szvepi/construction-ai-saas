# MIRA – IntelliJ Copilot design implementációs promptok

## Használat
Másold az összes PNG-t a projekt `design/` mappájába. A `00-osszefoglalo-moodboard.png` a közös vizuális nyelv, a 01–09 fájlok a képernyők célállapotát jelölik. A képek *tervek*, nem működő UI-k vagy pixelpontos frontend-specifikációk. A nagyobb referenciaképek másik korábbi design-iterációt mutatnak: ezekből csak az elrendezési és stílusötleteket vedd át, ne másold át a nem létező funkciókat.

## 0. Közös alapprompt – EZZEL KEZDD

```text
Senior Next.js/React + TypeScript frontend fejlesztőként dolgozz a jelenlegi MIRA SaaS kódbázisban.
Először vizsgáld meg a package.json-t, az app/pages útvonalstruktúrát, a komponenseket, a CSS/styling rendszert, az API-hívásokat és a jelenleg működő funkciókat. Ne feltételezd, hogy Tailwind vagy shadcn/ui telepítve van; a meglévő megoldásokat használd, és új függőséget csak indoklással javasolj.
A `design/00-osszefoglalo-moodboard.png` alapján hozz létre konzisztens, modern, világos MIRA design systemet: fehér/közel fehér felületek, halványkék fő akcentus, tengerészkék szöveg, puha elválasztók, finom árnyékok, 8–12px lekerekítések, kiegyensúlyozott térközök, következetes ikonok és állapotjelző badge-ek. A meglévő MIRA logót tartsd meg. Egységes desktop oldalsó navigáció és felső sáv, mobilon összehúzható navigáció.
FONTOS: kizárólag frontend megjelenést, komponensstruktúrát és indokolt UI-állapotkezelést módosíts. A backend API-kat, Gmail szinkront és besorolást, AI ajánlatgenerálást, mennyiségszámításokat, árazást, mentést, PDF-generálást, Gmail-piszkozat készítést, jogosultságokat és meglévő útvonalak működését NE törd el. Az API-k adatait használd; ne tegyél be hamis, statikus üzleti adatokat production képernyőbe.
Ne generálj meg egyszerre mindent. Előbb készíts fájl- és komponens-tervet, majd implementáld a közös layoutot, ellenőrizd a buildet és a működő navigációt. A további promptokkal oldalanként haladj.
Az akadálymentességre is figyelj: billentyűzet, megfelelő kontraszt, form label, loading/empty/error states. Minden oldalon legyen reszponzív megjelenés. Minden lépés után add meg, mely fájlokat módosítottad és mit teszteltél.
```

## 1. Áttekintés (`design/01-attekintes.png`)

```text
Alakítsd át a MIRA Áttekintés oldalát a design/01-attekintes.png és a közös moodboard alapján.
Maradjanak láthatók az eddigi működő adatok: Gmail kapcsolat állapota, ajánlatkérések száma, ellenőrzésre váró piszkozatok, kiküldésre kész ajánlatok, árajánlatkészítés előfeltételei (céges adatok és katalógus állapot).
A legutóbbi 5 levél helyett, ha lehetséges, a figyelmet igénylő ajánlatkéréseket emeld ki; legyen könnyen elérhető az összes levél. A státuszszámokat a meglévő API-ból vedd, ne változtasd meg a jelentésüket.
Minden kártya és sor kattintása a meglévő megfelelő nézetre navigáljon. Ne készíts új backend végpontot design miatt.
```

## 2. Beérkezett levelek (`design/02-beerkezett-levelek.png`)

```text
Alakítsd át a Gmailből szinkronizált Beérkezett levelek nézetet a design/02-beerkezett-levelek.png alapján.
Tartsd meg az Összes, Ajánlatkérések, Egyéb, Spam kategóriákat, a meglévő darabszámokat, a levelek frissítését és az Átsorolás funkciót.
Legyen jól olvasható a feladó, tárgy, rövid előnézet, beérkezés ideje és kategória. Adj keresést, szűrőt, rendezést és lapozást csak akkor, ha az adatok/aktuális frontend szerkezet ezt támogatja; ahol új backend kellene, jelezd külön és ne színleld késznek.
A sor megnyitásával a jelenlegi levélrészletező oldal nyíljon. Legyenek üres, betöltési és hibás állapotok.
```

## 3. Levél részlete és AI ajánlatkészítés (`design/03-level-reszlete-es-ajanlatkeszites.png`)

```text
A design/03-level-reszlete-es-ajanlatkeszites.png alapján rendezd át a már működő levélrészletező és ajánlat-előkészítő oldalt 2–3 jól elkülönített panelre.
Balra az eredeti levél (feladó, tárgy, szöveg), középen a meglévő AI által kinyert adatok és megjegyzésmező, jobbra vagy alatta a szerkeszthető árajánlat tételek és összesítés.
Tartsd meg a 'Jelenjen meg ez a kiegészítés az árajánlatban' jelölőt, az Ügyfél neve mezőt, az Újraelemzés a megjegyzésekkel gombot, az Új tétel, Törlés, Mentés, Újragenerálás műveleteket és a PDF előnézetet, letöltést, valamint a Gmail-piszkozat készítést PDF-melléklettel.
A tételeknél őrizd meg a mennyiség × egységár számítást és a jelenlegi áfa-logikát. Egy adatváltoztatás után a megjelenített végösszeg szinkronban maradjon a valódi számítási logikával, ne duplikáld önkényesen a backend kalkulációt.
```

## 4. Ajánlatok listája (`design/04-ajanlatok.png`)

```text
Készíts egységes Ajánlatok listanézetet a design/04-ajanlatok.png alapján. Ez új javasolt funkció: előbb vizsgáld meg, van-e már ajánlatlista-adat és API. Ha nincs, készíts csak újrafelhasználható frontend komponenst vagy tervet, és sorold fel a hiányzó backend adatokat.
Ajánlat száma, ügyfél, projekt neve, összeg, státusz (piszkozat, ellenőrzés alatt, elkészült, elküldött), dátum és műveletek szerepeljenek. Ne állítsd, hogy elküldött egy ajánlat, ha a rendszer csak Gmail piszkozatot készített!
```

## 5. Ajánlat szerkesztése (`design/05-ajanlat-szerkesztes.png`)

```text
A design/05-ajanlat-szerkesztes.png alapján készíts letisztult önálló ajánlat-szerkesztő élményt a meglévő részletoldalból. Egyetlen közös ajánlattétel szerkesztő komponenst használj a levélrészlet és ajánlat szerkesztő között.
Legyen soronként tétel megnevezése, mennyiség, mértékegység, egységár és számított összeg; alul teljes összesítés. Mentés, új tétel hozzáadás, törlés, PDF előnézet és piszkozat készítés maradjon. A számítási szabályok és AI mennyiséglogika változatlanok.
```

## 6. Katalógus (`design/06-katalogus.png`)

```text
Modernizáld az 'Árjegyzék és munkafázisok' oldalt a design/06-katalogus.png alapján, a meglévő árjegyzék-adatmodell megtartásával.
A táblában maradjon tétel neve, egységár, mértékegység, AI számítási szabály, szerkesztés és törlés. A szabályok (pl. közvetlen mennyiség, felületszorzó, kerületszámítás, vastagságból térfogat, veszteségi százalék) legyenek érthetők és a meglévő backend enumokra kötve. Legyen Új munkafázis felvétele.
Ne módosítsd a szabályok numerikus működését. A jelenlegi magyarázó súgó könnyen elérhető maradjon.
```

## 7. Ügyfelek (`design/07-ugyfelek.png`)

```text
Az Ügyfelek új, opcionális képernyő. A design/07-ugyfelek.png alapján csak akkor építsd meg valódi listaként, ha az ügyféladatok tárolása már létezik. Ellenkező esetben írj először technikai tervet és minimális adatmodellt, ne vezess be új backend funkciót jóváhagyás nélkül.
Tervezett mezők: név, e-mail, telefon, település, kapcsolódó ajánlatok; keresés, részletek és kapcsolat az ajánlatokkal. Kezeld az adatvédelmi és jogosultsági kérdéseket.
```

## 8. Céges beállítások (`design/08-ceges-beallitasok.png`)

```text
Rendezd át a jelenlegi Céges beállítások oldalt a design/08-ceges-beallitasok.png alapján.
Külön, jól elkülönülő szekció: Cégadatok (név, forma, adószám, közösségi adószám, kapcsolattartó), Székhely adatok (ország, irányítószám, település, utca, házszám stb.), Ajánlati feltételek és adózás/ÁFA beállítások.
Maradjon az összes meglévő mező és validáció, a 27%-os ÁFA kapcsoló jelenlegi értelmezése és az Ajánlati feltételek szövege. Legyen világos mentési visszajelzés, hibakezelés és helyes fókuszkezelés.
```

## 9. PDF ajánlat előnézet (`design/09-pdf-ajanlat-elonezet.png`)

```text
A design/09-pdf-ajanlat-elonezet.png mutatja a kívánt vizuális irányt. A meglévő PDF-generátor és letöltés tartalmát NE módosítsd automatikusan: előbb vizsgáld meg, milyen sablon és PDF renderelő működik.
A PDF külső frissítésére készíts tervet úgy, hogy minden eddigi kötelező céges/ügyfél adat, a számozás, keltezés, tételtáblázat, árak, áfa, fizetendő összeg és ajánlati feltételek megmaradjanak. Az előnézet és a ténylegesen letöltött PDF egyezzen. A Gmail piszkozat csatolmányának tartalma is ugyanebből a generált PDF-ből jöjjön.
```

## Záró ellenőrző prompt

```text
Ellenőrizd végig a MIRA frontend változtatásokat: TypeScript build, lint, navigáció, betöltés, Gmail-szinkron státusz, kategóriák/átsorolás, ajánlat megnyitása, AI újraelemzés, tételszerkesztés, mennyiség- és árkalkuláció, mentés, PDF letöltés, Gmail-piszkozat, céges beállítások mentése, mobil nézet és billentyűzetes navigáció. Futtasd az elérhető teszteket, és jelöld meg külön, amit nem tudtál tesztelni. Semmi ne használjon productionben hardcode-olt mintaadatokat.
```
