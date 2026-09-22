# Smoke: updater in-app

Cómo probar el flujo completo en dispositivo **sin publicar nada**, y qué mirar.

## Preparación

La beta.4 instalada no tiene updater. Para ver el banner hay que instalar un build que se
crea más viejo que la última release publicada:

1. En `app/app/build.gradle.kts` poner temporalmente `versionCode = 1` y
   `versionName = "0.1.0-beta.3"` (**no commitear**).
2. `cd app && ./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`
   (misma firma y mismo versionCode que la beta.4: instala encima sin desinstalar).
3. Si hace falta forzar un chequeo fresco: `adb shell pm clear com.tatoh.dokushorenshu`
   (**borra progreso, historias importadas y notas**; avisar antes).

## Flujo

- [ ] Abrir la app. En ≤ 3 s aparece el banner "New version available: 0.1.0-beta.4"
      arriba de Biblioteca; el TopAppBar no tiene doble hueco de status bar.
- [ ] `adb logcat -s Updater` no muestra warnings.
- [ ] Rotar: el banner sigue.
- [ ] ✕ → desaparece. Reabrir la app: no vuelve (gate 24 h). `pm clear` y seguir.
- [ ] `Update` sin el permiso → "To update, allow "Install unknown apps" for Dokusho." ·
      `Open Settings` abre la pantalla de la app en Ajustes. Conceder y volver: la descarga
      arranca sola.
- [ ] Barra de progreso avanza (83 MB). Rotar en el medio: sigue descargando, sin re-encolar.
- [ ] Al terminar, "Verifying the download…" un par de segundos (indeterminada) y se abre
      el instalador de Android con Dokusho Renshū. Cancelar.
- [ ] Banner en "Update ready to install." · `Install` reabre el instalador sin bajar de
      nuevo. Instalar.
- [ ] La app se reabre como beta.4 (About: `Dokusho Renshū 0.1.0`, porque la beta.4 aún no
      leía la versión real). Notificación de descarga completada en la bandeja.
- [ ] Sin red (modo avión, `pm clear`, abrir): no hay banner, logcat dice
      `no se pudo chequear la release`, y no crashea.

## Después

Revertir `versionCode`/`versionName` al valor real de la rama e instalar la beta.5:
`./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`.
