# Captura OCR con overlay flotante — diseño

> Plan: `docs/superpowers/plans/2026-09-16-captura-ocr-overlay.md`
> Origen: migración de `Kanji-no-Ryoushi` (Flutter) a `dokusho-renshuu` (nativo).

## Problema

Leer japonés en apps que no son el lector: manga, Twitter, juegos, capturas de
pantalla. Hoy eso vive en un repo aparte (`Kanji-no-Ryoushi`, Flutter) con su
propio diccionario, su propio historial y ninguna conexión con el vocabulario ni
con los mazos Anki de Dokusho.

## Decisión

Migrar la captura a Dokusho y dar de baja `Kanji-no-Ryoushi`.

Lo que se trae: los dos `Service` de Android (bubble flotante + captura con
MediaProjection), ~1000 LOC de Kotlin ya probadas en dispositivo, incluidos los
workarounds de MIUI y los `foregroundServiceType` de Android 14.

Lo que NO se trae: toda la capa Flutter (3744 LOC de Dart). Su diccionario
(JitendEx descargado en runtime), su historial propio y sus pantallas duplican
cosas que Dokusho ya tiene mejor resueltas: `diccionario-v2.db` con oraciones de
ejemplo, `Tokenizador` Kuromoji, `BuscadorPalabras`, `PalabraSheet`, progreso en
Room y export a Anki.

## Flujo

```
bubble flotante  →  overlay de selección  →  MediaProjection captura
                                                     ↓
                                          recorte del área elegida
                                                     ↓
                                          ML Kit OCR japonés → texto
                                                     ↓
                                 Intent a MainActivity con el texto
                                                     ↓
                            ImportScreen precargada (texto + título)
                                                     ↓
                 ImportadorHistoria → Historia → vuelta a Biblioteca
```

La clave del diseño es la última mitad: **la captura termina en el pipeline de
import que ya existe**. Una vez que el texto entra como `Historia`, todo lo demás
es gratis — furigana Kuromoji, tokens tappeables, `PalabraSheet` con definiciones
y ejemplos, palabras tocadas en Room, export a mazos Anki. No se escribe ni una
línea de UI de diccionario nueva.

## Dónde se hace el OCR

Dentro de `ScreenCaptureService`, no en la Activity.

La versión Flutter mandaba el PNG crudo a Dart por `MethodChannel` mediante un
callback estático, y Dart hacía el OCR. Nativo no necesita ese rodeo: el Service
corre ML Kit sobre el `Bitmap` que ya tiene en memoria y le pasa a la Activity
solo el **texto** por `Intent`. Eso elimina el callback estático, el `ByteArray`
cruzando procesos y todo el `MethodChannel`.

El texto reconocido son unos cientos de caracteres — entra holgado en el límite
de ~500 KB de una transacción binder.

## Contrato de unión de bloques

ML Kit devuelve `TextBlock` → `Line` → texto. La regla de unión:

- Líneas **dentro** de un bloque: se concatenan **sin separador**. El japonés no
  usa espacios, y en texto vertical (manga) cada columna es una `Line` del mismo
  globo — concatenarlas da el orden de lectura correcto.
- Bloques **entre sí**: se separan con `\n`.

Eso encaja directo con `ImportadorHistoria`, que trata cada línea no vacía como
un párrafo y después corta oraciones con `SegmentadorTexto`.

**Límite conocido:** ML Kit no garantiza el orden de las `Line` dentro de un
bloque para texto vertical derecha-a-izquierda. Si en uso real aparecen columnas
desordenadas, la corrección es ordenar por `boundingBox` antes de unir. El texto
queda editable en `ImportScreen` justamente para poder arreglarlo a mano mientras
tanto.

## Restricciones de la plataforma

Estas son del OS, no del código, y definen cuánto puede achicarse el flujo:

1. **Android 14+ invalida el token de MediaProjection después de cada sesión.**
   Cada captura necesita un diálogo de consentimiento nuevo. El código portado ya
   lo contempla (`cleanup()` borra las credenciales guardadas). Consecuencia: en
   Android 14+ el flujo real es *tap en el bubble → se abre la app → diálogo de
   permiso → overlay*, no un tap solo. No hay forma de evitarlo.
2. **Arrancar una Activity desde background está bloqueado desde Android 10**,
   salvo excepciones. La app califica por tener `SYSTEM_ALERT_WINDOW` concedido —
   que es justamente el permiso del overlay. Por eso el Service puede abrir
   `MainActivity` con el texto.
3. **`SYSTEM_ALERT_WINDOW` y `POST_NOTIFICATIONS` son permisos que el usuario
   concede a mano**, el primero desde Ajustes del sistema. La UI tiene que
   explicar y linkear, no puede pedirlos con un diálogo común.
4. **minSdk sigue en 26.** La captura se ofrece solo en Android 10+ (API 29); por
   debajo, la entrada de UI queda deshabilitada con una explicación. No se sube el
   minSdk por una feature opcional.

## Alcance

**Entra:** bubble, overlay de selección, captura, recorte, OCR, entrega a
`ImportScreen`, permisos y su UI, atribución de ML Kit, baja de
`Kanji-no-Ryoushi`.

**No entra** (backlog explícito del `TODO.md` viejo, ninguno bloquea el flujo):
Quick Settings Tile, zoom en el overlay, ajustes de contraste/brillo pre-OCR,
historial de capturas guardadas.

## Costo

ML Kit `text-recognition-japanese` 16.0.1 trae el modelo embebido en el APK
(funciona offline, sin Play Services). Suma peso: el APK release hoy pesa 42.7 MB
y se espera que quede en el orden de 55-60 MB. Medirlo es parte del plan.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Orden de columnas en texto vertical | Texto editable en `ImportScreen`; ordenar por `boundingBox` si molesta |
| Android 14+ pide permiso en cada captura | Límite del OS, se documenta en la UI |
| Peso del APK | Medir en el plan; el modelo embebido es el precio de funcionar offline |
| Workarounds de MIUI se pierden al portar | Se portan los archivos enteros con sus comentarios, no se reescriben |
| Una `Historia` por captura ensucia la biblioteca | Se acepta de entrada; si molesta, agregar filtro o TTL a las importadas |
