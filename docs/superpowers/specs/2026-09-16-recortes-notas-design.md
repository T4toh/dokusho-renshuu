# Recortes (notas de captura) — diseño

> Depende de: `docs/superpowers/specs/2026-09-16-captura-ocr-overlay-design.md` (Plan E).
> Ese plan dejó la captura funcionando; este cambia **dónde aterriza** lo capturado.

## Problema

Hoy cada captura se convierte en una `Historia` y aparece en la Biblioteca al lado
de Momotaro y del resto del catálogo de Aozora. Pero una captura no es una historia:
es un recorte de tres líneas de un manga, un tweet, una pantalla de juego. El propio
spec del Plan E ya anotaba el riesgo ("Una `Historia` por captura ensucia la
biblioteca") y lo aceptaba de entrada.

Las consecuencias concretas: la Biblioteca se llena de entradas `Scan 2026-09-16 14:32`,
el lector paginado con portada y número de oración no tiene sentido para tres líneas,
y el vocabulario de escaneos se mezcla con el de lectura en los mazos de Anki.

## Decisión

Un tipo de contenido propio, **Recorte**, con su almacenamiento, su lista, su vista
y su mazo de Anki. Reusa el modelo de texto que ya existe (`Parrafo`/`Oracion`/`Furigana`)
y toda la maquinaria de diccionario; no reusa `Historia` ni el lector.

## Modelo y almacenamiento

```kotlin
data class Recorte(
    val id: String,               // timestamp en millis, como string
    val texto: String,            // crudo: la fuente de verdad
    val parrafos: List<Parrafo>,  // derivado: furigana ya generada
    val timestamp: Long,
    val tieneImagen: Boolean,     // el archivo es <id>.jpg junto al JSON
)
```

`texto` manda; `parrafos` es caché derivada. Al editar se regeneran los párrafos desde
`texto` — la misma relación que `ImportadorHistoria` ya tiene entre el texto pegado y
la `Historia` que emite.

Se descartan de `Historia` los campos que no significan nada para un recorte: `autor`,
`licencia`, `dificultad`, `version`, `fuente`.

**Persistencia**: `filesDir/recortes/<id>.json` (el `id` es el timestamp en millis;
si el archivo ya existe se incrementa hasta encontrar uno libre, para que dos capturas
en el mismo milisegundo no se pisen), escritura atómica `tmp` → `rename`,
mismo criterio que `HistoriasRepo.guardarImportada()`. Serializador propio y chico:
el de historias arrastra el schema v2 del catálogo, que acá no aplica.

**Repo nuevo**: `RecortesRepo` con `listar()`, `cargar(id)`, `guardar(recorte)`,
`borrar(id)`. No va dentro de `HistoriasRepo`: ese archivo ya maneja assets, descargas,
catálogo remoto e importadas, y un quinto origen lo empuja a hacer demasiado.

### Imagen de la captura

Cada recorte guarda la imagen original en `filesDir/recortes/<id>.jpg`, para cuando
el OCR quedó ilegible y hay que ver el original.

**JPEG calidad 90, no PNG.** El Service ya comprime a PNG para pasarle el bitmap a
ML Kit, y un PNG de captura de pantalla pesa 2-5 MB: guardarlo por nota convertiría
"no pesan nada" en decenas de GB. En JPEG 90 un panel recortado (~1000x600) queda en
150-300 KB y una pantalla entera (1220x2712) en ~700 KB — cien notas, 20-40 MB.

WebP comprime ~30% mejor, pero al minSdk 26 el enum sin deprecar exige API 30 y por
lo tanto una rama por versión. No vale 10 MB en el peor caso; queda como upgrade
obvio si el volumen crece.

`tieneImagen` es derivable de si el archivo existe, pero se guarda para no hacer un
`File.exists()` por fila al pintar la lista.

Una nota sin imagen —borrada a mano, o porque falló el guardado— es un **estado
normal**, no un error: la miniatura simplemente no aparece.

**Volumen**: se guardan todos, sin límite ni TTL. Son texto plano; la app vieja
guardaba historial y nunca fue un problema de espacio. Si algún día molesta, se agrega
borrado por antigüedad — pero no se diseña para un problema que todavía no existe.

## Pipeline de creación

```
texto OCR → SegmentadorTexto.segmentar() → GeneradorFurigana.generar() → Recorte → RecortesRepo.guardar()
```

Es el pipeline de import menos el formulario. Corre en `ioDispatcher` con indicador de
progreso: `SegmentadorTexto` + Kuromoji tardan en textos largos, igual que en
`ImportViewModel.importar()`.

## Cambio en el flujo de captura

| Antes | Ahora |
|---|---|
| Service → `Intent` → `MainActivity` → `ImportScreen` precargada → `Historia` | Service → `Intent` → `MainActivity` → crea `Recorte` → abre la nota |

**El contrato del Service se amplía** en un punto: además del texto, manda la ruta de
la imagen. La imagen no puede viajar dentro del `Intent` —el límite de una transacción
binder es ~500 KB y una captura lo excede—, así que el Service la escribe a disco y
pasa la ruta.

**Slot fijo, no temporal único**: el Service escribe siempre a
`filesDir/captura-pendiente.jpg`, y `RecortesRepo.guardar()` lo mueve a
`recortes/<id>.jpg`. Un solo archivo, sobrescrito en cada captura, porque el flag
`isCapturing` ya garantiza que hay una sola captura en vuelo. La alternativa —un
temporal con nombre único por captura— deja huérfanos cada vez que el usuario cancela
a mitad de camino, y obligaría a barrerlos al listar. Con slot fijo el huérfano se
pisa solo.

El resto del contrato no cambia: `ACTION_TEXTO_OCR` con el texto como extra, igual
que hoy.

Se conserva intacta toda la máquina de consumo-única del Plan E (limpiar el `Intent`
al consumirlo, `launchSingleTop`, el guard `rememberSaveable` del diálogo de permiso).
Esa lógica costó tres rondas de review; lo único que cambia es el destino de la
navegación.

`ImportScreen` queda sin tocar, para el import manual de textos largos.

## UI

### Biblioteca con pestañas

Dos pestañas: **`Stories`** | **`Notes`**. `Scan` se muda al top bar de la pestaña
Notes, que es donde pertenece — es la acción que crea notas.

Efecto colateral buscado: el top bar de Biblioteca vuelve a tres acciones
(`Import` `Export` `About`) en vez de cuatro. El paso 1 del smoke del Plan E marcaba
justamente el riesgo de que cuatro labels no entraran en un teléfono en vertical.

### Lista de notas

Primeros ~40 caracteres del texto + fecha relativa. Orden por `timestamp` descendente.
Borrado con long-press → confirmación.

### Vista de nota

Bloque continuo, sin paginar, sin portada y sin número de oración: nada de eso
significa algo en tres líneas.

**Miniatura de la imagen**: arriba del texto, **colapsada por defecto** — el punto de
la nota es el texto; la imagen es la válvula para cuando el OCR quedó ilegible. Tap
para expandir.

**Botón `Remove image`** en el top bar: borra el `.jpg`, pone `tieneImagen` en false y
deja la nota andando con su texto. Irreversible, con confirmación.

Reusa `TextoConFurigana`, `ItemOracion`, `BarraSeleccion` y `PalabraSheet`, así que
tap-a-palabra, selección libre, Search web y copiar funcionan igual que en el lector
sin reimplementarlos.

**Refactor requerido**: `ItemOracion` y la lista de oraciones son `private` dentro de
`LectorScreen.kt` (442 líneas). Se promueven a un archivo compartido
(`ui/comun/`). Es un refactor acotado sobre código que hoy funciona, y la alternativa
—duplicar el renderizado de oraciones— garantiza que un arreglo futuro del ruby se
aplique en un lado y no en el otro.

**Modo edición**: botón `Edit` que cambia el bloque por un `TextField` con el `texto`
crudo. Al guardar se regeneran párrafos y furigana. Es la válvula para cuando el OCR
de texto vertical desordena las columnas — límite conocido de ML Kit ya documentado
en el Plan E.

## Anki

Las palabras tocadas en recortes van a un mazo propio, **"Dokusho — Scans"**, separado
de "Dokusho — Words".

**Discriminador**: `palabras_tocadas.idHistoria = "recorte:" + id`. Es una columna TEXT,
así que no hace falta migración de Room.

Los dos puntos no son decorativos: `ImportadorHistoria.generarId()` sanea los títulos
con `[\/:*?"<>|.\s　]+` → `_`, o sea que **un id de historia nunca puede contener `:`**.
Un separador como `-` sí colisionaría: una historia titulada "recorte-123" produce el
id `recorte-123`, porque el guion no está en ese set de saneo.

**El punto donde esto puede romperse en silencio**: `armarWords()` hoy usa
`progresoDao.todasPalabras()`, sin filtrar. Con el prefijo, las palabras de recortes se
colarían en el mazo Words sin que nadie se entere. Se agregan dos queries que hacen la
separación explícita:

```kotlin
@Query("SELECT * FROM palabras_tocadas WHERE idHistoria NOT LIKE 'recorte:%'")
suspend fun palabrasDeHistorias(): List<PalabraTocada>

@Query("SELECT * FROM palabras_tocadas WHERE idHistoria LIKE 'recorte:%'")
suspend fun palabrasDeRecortes(): List<PalabraTocada>
```

`armarWords()` pasa a usar `palabrasDeHistorias()`.

El prefijo es una convención de string que no está tipada en ningún lado, así que **la
garantía real es un test** que fija la separación en ambas direcciones. Si algún día
molesta, se asciende a una columna `tipo` con migración 2→3, el mismo patrón no
destructivo que ya se usó en 1→2.

**Oraciones de ejemplo**: `armarNotaWords(termino, historias)` busca la oración en las
historias. Como `Historia.parrafos` y `Recorte.parrafos` son ambos `List<Parrafo>`, el
helper pasa a recibir `List<Parrafo>`. Si el recorte no aporta oración usable, cae a
Tatoeba como ya hace.

**Kanji**: `kanjis_tocados` tiene PK solo `kanji`, sin `idHistoria`. Tocar un kanji en
un recorte alimenta el mazo Kanji existente sin cambiar nada. Se deja así: separarlo
sería inventar un problema.

## Errores

- OCR vacío: no crea recorte (ya lo maneja el Service del Plan E).
- Recorte con JSON corrupto: se saltea al listar, no tumba la lista — mismo criterio
  que `historiasLocales()`.
- Fallo al guardar: aviso visible y el texto queda en pantalla. Nunca se pierde en
  silencio.
- Fallo al guardar la imagen (disco lleno, permisos): el recorte se crea igual con
  `tieneImagen = false`. Perder la imagen nunca puede costar el texto.
- `<id>.jpg` ausente con `tieneImagen = true` (borrado externo): la miniatura no se
  muestra. No es un error.

## Testing

**JVM puro, sin dispositivo:**

- Serialización round-trip de `Recorte`, incluido `tieneImagen`.
- Pipeline texto → párrafos con furigana.
- Generación de ids sin colisión.
- **Separación de mazos**: palabras tocadas en historias y en recortes, verificando que
  cada mazo contenga solo las suyas.
- Listado salteando un archivo corrupto.
- `borrar(id)` elimina el JSON **y** el `.jpg`; `quitarImagen(id)` borra solo el `.jpg`
  y conserva el recorte.

**Requiere dispositivo** (va al checklist de smoke): las pestañas, la vista de nota,
el modo edición y el flujo captura → nota.

## Alcance

**Entra**: modelo `Recorte`, `RecortesRepo`, pipeline de creación, cambio del destino
de la captura, pestañas en Biblioteca, lista de notas, vista de nota con edición,
promoción de `ItemOracion` a componente compartido, mazo "Dokusho — Scans".

**No entra**: título editable (la primera línea alcanza para identificar la nota),
tags, búsqueda, carpetas, límite o TTL de notas, un "borrar todas las imágenes" global
(con veinte notas, de a una alcanza), cuota de disco.
Con veinte notas ninguna hace falta; si llegan a doscientas, se agrega búsqueda y
recién ahí se sabrá qué hay que buscar.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Palabras de recortes filtrándose al mazo Words | Test que fija la separación en ambas direcciones; es la única garantía, el prefijo no está tipado |
| El refactor de `ItemOracion` rompe el lector | Refactor acotado a promover visibilidad, sin cambiar comportamiento; el lector tiene tests y va al smoke |
| Una nota editada pierde la furigana original | `texto` es la fuente de verdad y los párrafos se regeneran; no hay estado que se desincronice |
| Las imágenes llenan el disco | JPEG 90 en vez de PNG (10x menos); botón de borrado por nota; el volumen se revisa si aparece el problema |
| `tieneImagen` se desincroniza del archivo | La vista trata "flag true, archivo ausente" como estado normal, no como error |
| La captura deja de pasar por `ImportScreen` y se pierde el paso de corrección | El modo edición de la nota cubre el mismo caso, con un paso menos |
