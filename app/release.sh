#!/bin/bash
# Prepara un release de la app: valida versión, changelog y firma, buildea el APK
# release e imprime el comando de publicación. NO publica: el tag lo crea una persona.
# Port de contador-de-truco/release.sh (Pulpero) a gradle.
set -euo pipefail

cd "$(dirname "$0")"

GRADLE=app/build.gradle.kts
SALIDA=app/build/outputs/apk/release

if ! gh auth status >/dev/null 2>&1; then
    echo "❌ gh no está autenticado (gh auth login). Sin eso no se pueden verificar el tag ni el versionCode publicado."
    exit 1
fi

VERSION=$(grep -E '^\s*versionName = "' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/')
BUILD=$(grep -E '^\s*versionCode = ' "$GRADLE" | sed -E 's/[^0-9]*([0-9]+).*/\1/')
TAG="v$VERSION"

if [ -z "$VERSION" ] || ! [[ "$BUILD" =~ ^[0-9]+$ ]]; then
    echo "❌ No pude leer versionName/versionCode de $GRADLE (leí '$VERSION' / '$BUILD')."
    exit 1
fi

if ! grep -q "^## \[$VERSION\]" CHANGELOG.md; then
    echo "❌ CHANGELOG.md no tiene la sección '## [$VERSION] - AAAA-MM-DD'."
    echo "   Renombrá '## [Sin publicar]' con la versión y la fecha."
    exit 1
fi

if [ ! -f key.properties ]; then
    echo "❌ Falta app/key.properties: el APK saldría con otra firma y las betas instaladas"
    echo "   lo rechazarían (INSTALL_FAILED_UPDATE_INCOMPATIBLE)."
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || gh release view "$TAG" >/dev/null 2>&1; then
    echo "❌ El tag $TAG ya existe. Subí versionName en $GRADLE."
    exit 1
fi

# El updater compara versionName, pero Android compara versionCode: si no sube, el
# instalador rechaza el APK aunque el tag sea mayor. Se mira la última release de la
# APP (tags v*), no las del diccionario (db-v*).
ULTIMO_TAG=$(gh release list --limit 30 --json tagName --jq '[.[] | select(.tagName | startswith("v"))][0].tagName // empty')
if [ -n "$ULTIMO_TAG" ]; then
    BUILD_PUBLICADO=$(gh release download "$ULTIMO_TAG" --pattern versionCode.txt -O - 2>/dev/null | tr -d '[:space:]' || true)
    if [ -z "$BUILD_PUBLICADO" ]; then
        echo "⚠️  $ULTIMO_TAG no tiene versionCode.txt: no se puede verificar el bump del versionCode."
    elif ! [[ "$BUILD_PUBLICADO" =~ ^[0-9]+$ ]]; then
        echo "❌ versionCode.txt de $ULTIMO_TAG no es un número: '$BUILD_PUBLICADO'"
        exit 1
    elif [ "$BUILD" -le "$BUILD_PUBLICADO" ]; then
        echo "❌ versionCode $BUILD no es mayor que el publicado en $ULTIMO_TAG ($BUILD_PUBLICADO)."
        exit 1
    fi
fi

echo "🔨 ./gradlew assembleRelease"
./gradlew assembleRelease

APK="$SALIDA/dokusho-renshuu-$TAG.apk"
cp "$SALIDA/app-release.apk" "$APK"
echo "$BUILD" > "$SALIDA/versionCode.txt"
shasum -a 256 "$APK"

echo ""
echo "⚠️  Probá ESTE apk en un dispositivo antes de publicar (R8 vs ML Kit: el OCR puede"
echo "   volver vacío sin crashear, ver docs/ESTADO.md). Instalar: adb install -r \"$APK\""
echo ""
echo "✅ Listo. Para publicar $TAG, corré vos:"
echo ""
echo "  gh release create $TAG \"$APK\" \"$SALIDA/versionCode.txt\" --prerelease --title $TAG --generate-notes"
echo ""
