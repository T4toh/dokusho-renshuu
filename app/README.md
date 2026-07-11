# app/ — Dokusho Renshū (Android)

Lector de japonés: historias de `catalogo/` con furigana, diccionario offline
(`diccionario-v2.db`) y detalle de kanji. Kotlin + Jetpack Compose, minSdk 26.

## Build

```bash
./gradlew assembleDebug     # baja el db del release + copia historias (tasks de assets)
./gradlew test              # tests JVM/Robolectric
./gradlew installDebug      # instalar en dispositivo
```

Requiere JDK 17+ (probado con JDK 21) y Android SDK 36. Los assets NO se commitean:
`descargarDiccionario` baja `diccionario-v2.db` del release `db-v2` (una vez);
`copiarHistorias` copia `../catalogo/` en cada build.

## Arquitectura

- `datos/` — DiccionarioSqlite (readonly, copiado de assets al primer arranque),
  HistoriasRepo (assets + descargas + catálogo remoto), Room (progreso, palabras
  tocadas — insumo del Plan 4 —, prefs).
- `dominio/` — Tokenizador (Kuromoji IPADIC) y BuscadorPalabras (contrato db:
  `oracion_palabra` 2-6 chars, fallback `oracion_kanji`). JVM puro.
- `ui/` — Compose M3: Biblioteca, Lector (furigana pre-alineada del JSON,
  fin exclusivo), Detalle kanji, Acerca de (atribuciones).

## Actualizar datos

- Nuevo db: publicar release `db-vN`, actualizar URL/`VERSION_ESPERADA`, borrar
  `app/src/main/assets/diccionario-v2.db` y rebuildear.
- Nuevas historias: regenerar `catalogo/` (ver `historias/README.md`) — el build
  las re-empaqueta solo.

## Importar texto propio

Library → Import permite agregar tus propias historias, además de las de
`catalogo/`:

- Dos vías de entrada: pegar el texto directamente o "Open .txt" (se espera
  UTF-8; un `.txt` en Shift-JIS —típico de Windows— hay que convertirlo antes
  a UTF-8, límite conocido v1).
- Metadata: título obligatorio, dificultad manual (easy/medium/hard), autor
  opcional. Si el texto no parece japonés se muestra un aviso; "Cancel" no
  guarda nada.
- La furigana se genera automáticamente con Kuromoji (sin el alineador de
  Aozora del catálogo) y puede errar lecturas de nombres propios — límite
  conocido.
- Las historias importadas aparecen en Library con badge "Imported" y se
  guardan en `filesDir/importadas/`, separadas de `catalogo/`. Se pueden
  borrar con el ícono de la tarjeta (con confirmación); el progreso de
  lectura se conserva aunque se borre la historia.
- Entran también al mazo Anki "Dokusho — Stories", elegibles junto con las
  del catálogo por checkbox al exportar.

## Ícono

`app/arte/logo.jpg` es arte de Saitama (ONE / Yusuke Murata, One Punch Man) —
**uso personal únicamente**. No redistribuir ni publicar esta app en stores
con este ícono: el arte tiene copyright de sus autores.
