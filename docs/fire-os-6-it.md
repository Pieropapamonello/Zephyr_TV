# Zephyr su Android 7 / Fire OS 6 — guida in italiano

[Risultati YouTube collegati a SmartTube — Nello.3](youtube-smarttube-it.md).

La PR si basa ora su **Zephyr TV 3.5.3** e contiene due APK separati:

| APK | Funzione | Pacchetto della build di prova |
| --- | --- | --- |
| Zephyr | Launcher compatibile da API 24, ricerca e gestore Home opzionale | `link.mccoy88f.zephyr.debug` |
| Bridge vocale | Inoltra a Zephyr o SmartTube le richieste destinate a YouTube da Alexa | `com.amazon.firetv.youtube` |

La compatibilità è stata provata fisicamente su Amazon AFTMM, Fire OS 6.7.1.1,
Android 7.1.2 / API 25. Android 7.0 / API 24 è il minimo dichiarato ma non è stato
provato su hardware. Non è una release firmata dal developer originale.

## Come funziona la Home

La Home Amazon rimane installata e abilitata. Il servizio opzionale HomeKeeper
osserva localmente gli eventi di utilizzo e riporta davanti Zephyr quando appare
la precisa attività Home Amazon. Non usa root o wake lock e non invia eventi in
rete. Controlla gli eventi ogni 500 ms soltanto con schermo interattivo.

All'avvio del servizio esiste un ripiego più ampio: se manca un evento recente
oppure l'ultimo appartiene al launcher Amazon, apre Zephyr. In quel momento può
coprire anche una ricerca Amazon. Questo comportamento è esplicitato per la
revisione del maintainer; durante il normale controllo ricerca e impostazioni
non vengono reindirizzate.

Il servizio Home è previsto solo per API 24–25 ed è disattivato per impostazione
predefinita. Per la build di prova:

```sh
adb shell appops set link.mccoy88f.zephyr.debug GET_USAGE_STATS allow
adb shell am start -n link.mccoy88f.zephyr.debug/link.mccoy88f.zephyr.HomeControlActivity
```

Nel gestore scegliere **Usa Zephyr come Home**. Il permesso permette di leggere
gli eventi d'uso delle app. **Sospendi e apri la Home Amazon** ferma il servizio
e salva la scelta. Il gestore resta raggiungibile dall'elenco delle app, in un
task separato dalla schermata Zephyr.

Con l'opzione attiva, BOOT_COMPLETED riavvia il servizio. Nella prova iniziale
l'apertura automatica è stata osservata a circa 143 secondi dall'accensione;
altri receiver di Fire OS possono ritardarla. Questa prova di riavvio è stata
fatta sulla build 3.5.1, non ripetuta sulla 3.5.3. Dopo l'aggiornamento 3.5.3
sono stati verificati la preferenza conservata e HomeKeeper attivo.

## Come funziona la ricerca vocale

### Pulsanti di ricerca sulla Fire TV

Sulle Fire TV Amazon (modello AFT) senza Android RecognitionService, le due voci
**Dillo a voce** e **Assistente vocale** sono sostituite da **Voce con telecomando
Fire TV**. Questa voce apre istruzioni leggibili e, se è installato il nostro
companion, il pulsante **Gestisci bridge vocale**. **Torna alla ricerca** conserva
il testo già scritto. Il riconoscimento mancante viene controllato prima di
chiedere il permesso microfono: non si presenta una richiesta inutile.

La registrazione parte dal microfono fisico del telecomando, non dalla voce
di questo menu. La tastiera e il QR dal telefono restano disponibili. Sugli
altri dispositivi o quando esiste un vero servizio Android di riconoscimento,
i normali comandi di dettatura e assistente sono mantenuti.

### Percorso della richiesta

Il percorso provato è:

**Microfono del telecomando → Alexa → richiesta YouTube → bridge → ACTION_SEARCH
di Zephyr → risultati oppure apertura dell'app riconosciuta.**

Alexa riconosce la voce. Il bridge non registra audio, non ha permessi microfono,
Internet, accessibilità o statistiche d'uso e non ha un servizio in background.
I suoi log riportano la destinazione, non il testo pronunciato.

Il bridge deve sostituire l'app YouTube Amazon o il precedente bridge Amazon di
SmartTube, perché Alexa indirizza la richiesta a `com.amazon.firetv.youtube`.
Non può convivere con quel pacchetto. **SmartTube stesso rimane installato.**
Le firme sono diverse: conservare prima l'APK precedente e gli eventuali dati
importanti. Non rimuovere un componente di sistema per usare questo prototipo.
Nel dispositivo provato era presente il piccolo bridge SmartTube, salvato prima
della sostituzione. Installazione e vincoli completi sono nel
[README del bridge](../voice-bridge/README.md).

Aprire **Bridge vocale Zephyr / SmartTube (prova)** e scegliere **Usa Zephyr per
le ricerche**. La destinazione iniziale è SmartTube, quindi questo passaggio è
necessario. Il bridge richiede il supporto ACTION_SEARCH di questa PR. Se sono
installate entrambe le build di Zephyr, dà precedenza al pacchetto `.debug`.

### Frasi in italiano

Tenere premuto il microfono, pronunciare la frase, poi rilasciare.

| Frase | Risultato previsto | Verifica effettuata |
| --- | --- | --- |
| **Cerca Inception su YouTube** | Risultati di Inception dentro Zephyr | Microfono fisico, conferma del tester e log del passaggio Alexa → bridge → Zephyr |
| **Cerca Stremio su YouTube** | Apre Stremio se il nome dell'app installata corrisponde | Microfono fisico; il tester ha confermato che rimane aperto |
| Cerca apri Stremio su YouTube | Il testo `apri Stremio` apre l'app | Test diretto degli intent e instradamento; frase completa non provata al microfono |
| Cerca Zephyr Inception su YouTube | Forza Zephyr per questa richiesta, anche se il selettore è su SmartTube | Prefisso verificato nei test di instradamento, non al microfono |
| Cerca SmartTube musica rilassante su YouTube | Forza SmartTube per questa richiesta, anche se il selettore è su Zephyr | Prefisso verificato nei test di instradamento, non al microfono |
| Cerca Nuvio su YouTube | Può aprire Nuvio se corrisponde esattamente al nome installato | Meccanismo supportato; questa frase non è stata provata |

I prefissi riconosciuti dal bridge sono `Zephyr`, `zephir`, `zefir`, `zefiro` e
`SmartTube`, soltanto come parola iniziale. Il riconoscimento effettivo dipende
anche da come Alexa trascrive il nome. Zephyr confronta i nomi delle app senza
distinguere maiuscole/minuscole e accetta `apri` o `open` prima del nome esatto.
Se non corrisponde a un'app, il testo viene cercato come contenuto; `cerca` o
`search` iniziale viene rimosso per la ricerca. Il testo non diventa un comando
di shell. Le query sono limitate a 500 caratteri.

Formule alternative come **Trova Inception su YouTube**, **Cerca su YouTube
Inception** e **YouTube, cerca Inception** sono solo candidate da verificare con
Alexa: non sono state convalidate sulla Fire TV e non sono promesse della PR.

### Limiti da conoscere

- **Cerca Inception**, senza YouTube, continua a usare i risultati Amazon.
- **Cerca Inception su Zephyr** non è registrato in Alexa da questa installazione.
- **Cerca Inception su Prime Video** usa l'integrazione Prime Video, non il bridge.
- Il tasto microfono non viene rimappato. Il dispositivo provato non espone un
  Android RecognitionService; il supporto ai tasti SEARCH/ASSIST/VOICE_ASSIST
  funziona solo quando Android consegna quei tasti all'app.
- I normali link a video/canali senza testo di ricerca vengono inoltrati a
  SmartTube dal codice. Non è stata effettuata una prova completa di riproduzione
  diretta per ogni formato di link.
- Il prototipo di inoltro generico tramite accessibilità non si è collegato su
  questa Fire OS 6. È stato rimosso dai sorgenti e dall'APK distribuito, con le
  impostazioni ripristinate. Non è una funzione disponibile nella PR.
- Non serve un PC acceso per il bridge YouTube. Non è però garantita la stessa
  compatibilità su tutte le versioni Fire OS o dopo cambiamenti di Alexa.

## Tornare a SmartTube o alla Home Amazon

Per le ricerche, scegliere **Usa SmartTube per le ricerche** nel selettore del
bridge. Per ripristinare esattamente il bridge precedente, disinstallare soltanto
il companion `com.amazon.firetv.youtube`, quindi installare l'APK salvato: una
semplice installazione con `-r` non supera una firma differente.

Per la Home, scegliere **Sospendi e apri la Home Amazon** nel gestore Zephyr.
Ripristino da computer per la build `.debug`:

```sh
adb shell am force-stop link.mccoy88f.zephyr.debug
adb shell appops set link.mccoy88f.zephyr.debug GET_USAGE_STATS default
adb shell input keyevent KEYCODE_HOME
```

Non disinstallare SmartTube o Zephyr per cambiare semplicemente la destinazione
delle ricerche. Finite le prove ADB, disattivare il debug ADB/rete della Fire TV.

## Prove e build 3.5.3

Le verifiche seguenti si riferiscono alla prima release **Nello.1**.

L'aggiornamento dalla build Nello 3.5.1 alla 3.5.3 è stato installato con `adb
install -r`: pacchetto `.debug`, versionCode 65, minSdk 24. Sono stati conservati
i dati e l'opzione Home; il bridge 0.1.0 è un APK separato e non è stato sostituito.

- 106 test JVM Zephyr superati; compilazione e lint completati, con avvisi.
- Un test strumentale selezionato sulla Fire TV superato: sostituzione della
  cronologia di riproduzione in una cartella temporanea isolata e Java time,
  senza modificare la cronologia dell'utente.
- 15 controlli di instradamento del bridge in un test JUnit superati.
- Le due frasi fisiche confermate sopra sono state provate sulla build 3.5.1.
  Sulla 3.5.3 è stato ripetuto l'inoltro diretto dal bridge con risultati Inception
  visibili; non è stato chiesto un nuovo test fisico del microfono.
- Pausa e riattivazione della Home provate sulla 3.5.1. Non sono coperti da queste
  prove ogni player, add-on, modello TV o comportamento di Android più recente.

La [release Nello 3.5.3](https://github.com/Pieropapamonello/Zephyr_TV/releases/tag/v3.5.3-nello.1)
contiene l'APK Zephyr modificato e i sorgenti GPL-3.0 del relativo tag. Le aggiunte
documentali successive al tag sono sul branch della PR. Il bridge va compilato
separatamente; non è incluso in quell'APK Zephyr.

### Correzione menu vocale — Nello.2

109 test JVM superati, inclusi i tre nuovi controlli sulla scelta tra dettatura
nativa e istruzioni Fire TV. Compilazione/lint e verifiche manuali sul dispositivo
coprono la sostituzione delle voci non funzionanti, le istruzioni multilinea,
l'apertura del selettore del bridge e il ritorno alla ricerca. Non è un nuovo
riconoscitore vocale e non introduce il comando Alexa «su Zephyr».

La skill Alexa dedicata non è implementata in questa patch. Richiede prima la
registrazione dell'account Amazon Developer e la successiva configurazione e
verifica dell'infrastruttura; la frase esatta «cerca Inception su Zephyr» non è
garantita da una skill personalizzata. Il programma VSK per l'integrazione video
ufficiale non accetta nuovi partner secondo la documentazione Amazon consultata.

### Coda di riproduzione su Android 7 — Nello.4

La coda legge ora i progressi salvati dal player Zephyr e l'ultimo canale aperto
anche senza il provider Android TV Watch Next (introdotto con Android 8).
Un provider mancante o un permesso negato non nasconde più i dati locali.
Sono inclusi anche i canali delle estensioni native installate e abilitate;
le estensioni rimosse non lasciano collegamenti non funzionanti.

Le sessioni multimediali delle altre app richiedono l'accesso alle notifiche di
Zephyr: il listener dell'app legge i metadati delle sessioni, non il contenuto
delle notifiche. Su Fire OS 6 non è disponibile il comando Android più recente
`cmd notification allow_listener`; va usata la schermata di sistema, quando
presente, oppure una configurazione ADB che preservi gli altri listener.

Questa correzione non importa la cronologia privata di Stremio, Nuvio o SmartTube:
una vecchia visione esterna non pubblicata al sistema non è ricostruibile dal
launcher. Le sessioni visibili possono sparire quando il player esterno le chiude.

Verifica Nello.4: 121 test JVM superati, build e lint completati (con avvisi).
Sulla Fire TV API 25 è stata verificata la presenza dell'ultimo canale nella coda
e la sua riapertura dal relativo collegamento. Le prove di questa sezione non
implicano l'importazione della cronologia delle app esterne.

### Preferenze IPTV — Nello.5

Per i canali live, con lingua preferita italiana, il player sceglie prima una
traccia supportata dichiarata `it`/`ita` o con etichetta italiana; in assenza,
usa una traccia con lingua (ad esempio `qak`) o etichetta che termina in `ak` (convenzione di
alcuni provider, non una garanzia linguistica). L'audio scelto manualmente resta
selezionato per quel flusso. Film e serie mantengono la selezione precedente.

I flussi live dichiarati 1080p vengono provati prima degli altri, che rimangono
nella lista di ripiego. Nei manifest adattivi il player preferisce le varianti
1080p supportate, conservando i bitrate Full HD disponibili nello stesso gruppo.
Se non ci sono varianti Full HD supportate, mantiene la normale selezione del
lettore. Non applica un ingrandimento artificiale ai flussi 720p e non garantisce
la disponibilità di 1080p da parte del provider.

Un'estensione può dichiarare `behaviorHints.refreshOnStartup: true` per far
rileggere i propri cataloghi alla creazione dell'Activity Home di Zephyr. È una
scelta esplicita per estensione, non un download di tutti i cataloghi al ritorno
da ogni app. L'estensione deve a sua volta gestire il rinnovo delle sue sorgenti.

La richiesta M3U `maxAge: 0` è ora rispettata dal bridge JavaScript invece di
essere sostituita erroneamente dal valore predefinito di 24 ore. Regressione
eseguibile senza dipendenze con `node --test scripts/extension-m3u-bridge.test.cjs`.

Verifica Nello.5: 126 test JVM, il test Node del bridge M3U e sette test
separati dell'estensione locale superati; build e lint completati con avvisi.
Sulla Fire TV API 25: 1080p osservato su due canali; italiano selezionato al
posto dell'inglese quando dichiarato; selezione manuale rispettata. Su un altro
manifest con otto audio è stata selezionata la settima traccia (codice `qak`).
Rinnovo della cache M3U verificato all'avvio del launcher. Queste prove non
certificano lingue, qualità o disponibilità di ogni sorgente esterna.

### Copertine delle partite — Nello.6

Le estensioni possono usare `zephyr.has("match-posters")` e
`await zephyr.matchPoster({home, away, time, homeBadge, awayBadge})`.
Zephyr compone localmente un PNG 960×540 con i due stemmi, i nomi e l'orario.
Le immagini dei club vengono scaricate da URL HTTPS, ridimensionate e conservate
nella cartella privata dell'estensione (massimo 120 copertine). Una modifica a
nomi, stemmi o orario produce una nuova immagine. Non viene usato un servizio
esterno per comporre le copertine; nomi e orario non sono inviati a un renderer.
Se uno stemma non è disponibile, compaiono le iniziali della squadra.

L'estensione fornisce nomi, orari e indirizzi degli stemmi: il renderer non
sceglie un fuso orario e non modifica gli orari della sorgente.

### Schermate semplificate — Nello.6

Quando Nuvio TV è installato, la scheda di film e serie e i comandi Spotlight
mostrano un solo «Guarda». Il collegamento `nuvio://detail/<tipo>/<id>` apre quel
titolo in Nuvio; riproduzione, scelta della sorgente e ripresa seguono le opzioni
di Nuvio. Non è una promessa di avvio automatico del film senza una sorgente.

«Continua a guardare» esclude i canali live e le altre app. Conserva localmente
solo filmati non completati di Nuvio TV e SmartTube con durata e posizione
pubblicate nelle sessioni multimediali: almeno 10 secondi, meno del 95%.
Il listener già autorizzato registra i progressi anche mentre il launcher è in
secondo piano, senza leggere il contenuto delle notifiche. Il permesso non viene
concesso dall'app. Dopo un aggiornamento Fire OS può richiedere la riconnessione
del listener: Zephyr la richiede solo se l'utente lo ha già autorizzato.

I dati vengono salvati solo sul dispositivo. Non si importa la cronologia
privata precedente delle app; dati mancanti non vengono inventati. SmartTube
si riapre sul video e sul tempo salvato quando è disponibile il suo identificatore.
Per Nuvio viene usato il collegamento al titolo noto; per sessioni prive di
collegamenti si riapre la sessione ancora attiva o l'app, che gestisce la ripresa.
Le sessioni finite vengono rimosse dalla riga, non dalla cronologia dell'app.

Le estensioni con guida XMLTV possono indicare il programma in onda sulle
anteprime dei canali. Gli aggiornamenti della guida invalidano ora anche la
cache delle sezioni: un nuovo palinsesto compare senza aspettare cinque minuti.
La disponibilità delle partite dipende dai dati della guida del fornitore.

Verifiche Nello.6: build e lint completati con avvisi; 131 test JVM eseguiti,
130 superati e un controllo remoto Nuvio saltato; regressione Node del bridge
M3U superata. Sulla Fire TV API 25: test del renderer a due stemmi e invalidazione
per cambio orario superato; scrittura/sostituzione dei progressi in una cartella
isolata superata. La scheda Inception mostra solo «Guarda» e apre il titolo in
Nuvio. Un video SmartTube realmente interrotto è comparso nella riga ed è stato
riaperto a 457 secondi. La cronologia privata precedente delle app e ogni
possibile flusso/Nuvio player non sono coperti da queste prove.
