# Changelog

Cambios notables de la app Dokusho Renshū, del más nuevo al más viejo. Formato
[Keep a Changelog](https://keepachangelog.com/es/1.1.0/), versiones
[SemVer](https://semver.org/lang/es/) con prerelease (`0.1.0-beta.N`). Las releases del
diccionario (`db-vN`) no van acá. Antes de la beta.5 las notas vivían solo en la release
de GitHub.

## [Sin publicar]

- Acá van los cambios de la próxima versión (`release.sh` pide renombrar esta sección).

## [0.1.0-beta.6] - 2026-09-23

### Arreglado
- El escaneo con la burbuja flotante ya no devuelve a veces la imagen del escaneo anterior.

## [0.1.0-beta.5] - 2026-09-22

### Agregado
- La app se actualiza sola: al abrirla chequea (una vez por día) si hay una release nueva
  en GitHub y ofrece bajarla e instalarla desde un aviso arriba de la biblioteca. Verifica
  el sha256 antes de instalar. Pide el permiso de "instalar apps desconocidas" la primera vez.

### Cambiado
- La versión que muestra About es la real del APK (antes decía siempre 0.1.0).
- La release se firma con una clave fija (`key.properties`, fuera del repo); es la misma
  firma de las betas anteriores, así que se instala encima.
