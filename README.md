# 読書練習 — Dokusho Renshū

App Android para leer japonés y quedarte con el vocabulario que te frenó.

Lees un texto, tocás la palabra que no entendés, aparece su definición con oraciones
de ejemplo. Lo que tocaste queda guardado, y cuando querés lo exportás a Anki.

Todo funciona **sin internet**: el diccionario y el reconocedor de texto viajan dentro
de la app.

## Qué podés leer

**Cuentos clásicos.** Vienen 10 obras de dominio público de Aozora Bunko —Momotaro,
Urashima Taro, Kintaro y otras— con furigana sobre los kanji y una traducción literal
al inglés por oración.

**Tus propios textos.** Pegás o abrís un `.txt` en japonés y la app le genera la
furigana y lo corta en oraciones.

**Cualquier cosa en pantalla.** Un botón flotante que queda sobre las demás apps:
lo tocás, arrastrás para marcar un área, y el texto japonés de ahí se reconoce y se
guarda como una nota. Sirve para manga, tweets, juegos, capturas.

## Cómo se usa

### Instalar

Bajate el APK de la última [release](https://github.com/T4toh/dokusho-renshuu/releases)
e instalalo. Android va a avisarte que viene de fuera de la tienda; es esperable.

Necesitás Android 8 o superior. La captura de pantalla pide Android 10 o superior.

> **Ojo:** la release publicada hoy (`v0.1.0-beta.3`) es anterior a la captura de
> pantalla y a las notas. Para probar eso hay que compilar desde el código — ver
> [`app/README.md`](app/README.md).

### Leer

La **Biblioteca** tiene dos pestañas: **Stories** para los cuentos y tus textos
importados, **Notes** para lo que capturás de otras apps.

Dentro de un texto:

- **Tocá una palabra** y se abre su definición, su lectura y oraciones de ejemplo.
  Con eso queda registrada en tu vocabulario.
- **Mantené apretado** para empezar a seleccionar, y seguí tocando para extender la
  selección. Podés copiar lo seleccionado o buscarlo en la web.
- Tocá un **kanji** en la hoja de definición para ver su detalle, y marcalo como
  fácil, medio o difícil si querés estudiarlo aparte.

### Capturar de otras apps

En la pestaña **Notes**, tocá **Scan**. La pantalla te va a pedir dos permisos que
Android obliga a conceder a mano: dibujar sobre otras apps, y notificaciones.

Después, **Start floating button**: aparece una burbuja que podés arrastrar y que
queda flotando sobre lo que estés usando. Tocala, marcá el área con el texto, y la
nota se abre sola con el japonés ya reconocido.

Si el reconocimiento sale desordenado —pasa con texto vertical— tocá **Edit** y
corregilo a mano; la furigana se regenera. Cada nota guarda también la captura
original, colapsada arriba, por si necesitás ver el texto real.

> Android va a pedirte autorización de grabación **en cada captura**. Es molesto y
> está en el backlog: la sesión se puede mantener viva en vez de pedirla cada vez.

### Exportar a Anki

Desde **Export** generás archivos `.apkg` que importás en Anki o AnkiDroid:

| Mazo | Qué trae |
|---|---|
| `Dokusho — Words` | las palabras que tocaste leyendo cuentos y textos importados |
| `Dokusho — Scans` | las palabras que tocaste en las notas capturadas |
| `Dokusho — Kanji` | los kanji que marcaste con dificultad |
| `Dokusho — Stories` | un submazo por historia, con sus kanji para estudiar antes de leerla |

Las tarjetas traen la palabra, su lectura, sus significados y oraciones de ejemplo
sacadas de lo que leíste, con Tatoeba como relleno.

Re-exportar **actualiza** las tarjetas que ya importaste, no las duplica.

## El repo

Es un monorepo con la app y las herramientas que le arman los datos. Cada parte tiene
su propio README con el detalle de cómo se construye y se regenera:

| Carpeta | Qué es |
|---|---|
| [`app/`](app/) | la app Android (Kotlin + Jetpack Compose) |
| [`diccionario/`](diccionario/) | arma el SQLite del diccionario desde Jitendex, KANJIDIC2 y Tatoeba |
| [`historias/`](historias/) | convierte cuentos de Aozora Bunko en el JSON que lee la app |
| `catalogo/` | la salida de `historias/`: lo que viene empaquetado en la app |
| [`docs/`](docs/) | estado del proyecto, specs y planes de implementación |

`docs/ESTADO.md` es el mapa: qué está hecho, qué se decidió y por qué, y qué límites
conocidos tiene cada cosa.

## Créditos

La app no sería posible sin estas fuentes libres:

- [Jitendex](https://jitendex.org) — diccionario japonés-inglés, CC BY-SA 4.0
- KANJIDIC2 ([EDRDG](https://www.edrdg.org)) — datos de kanji, CC BY-SA 4.0
- [Tatoeba](https://tatoeba.org) — oraciones de ejemplo, CC-BY 2.0 FR
- [Aozora Bunko](https://www.aozora.gr.jp) — los cuentos, dominio público
- [Kuromoji](https://www.atilika.org/) — análisis morfológico japonés, Apache 2.0
- Google ML Kit Text Recognition v2 — reconocimiento de texto, Apache 2.0

Las atribuciones completas están también dentro de la app, en **About**.
