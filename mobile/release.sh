#!/usr/bin/env bash
# Publica uma versão nova do app Android numa Release do GitHub. Os celulares com o MangaVerse encontram a release,
# baixam o APK anexado e se atualizam sozinhos.
#
# Antes: aumente versionCode e versionName em app/build.gradle.kts.
# Uso:   ./release.sh ["notas da versão"]
#
# Importante: gere sempre na mesma máquina (o APK é assinado com a chave de debug dela). O Android só instala a
# atualização por cima se a assinatura for a mesma da versão instalada.
set -euo pipefail
cd "$(dirname "$0")"

version=$(sed -n 's/^ *versionName *= *"\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
code=$(sed -n 's/^ *versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
[ -n "$version" ] && [ -n "$code" ] || { echo "Não achei versionName/versionCode em app/build.gradle.kts" >&2; exit 1; }
tag="mobile-v$version"
notes=${1:-"MangaVerse para Android $version"}

command -v gh >/dev/null || { echo "Instale o GitHub CLI (gh) e rode 'gh auth login'" >&2; exit 1; }
if gh release view "$tag" >/dev/null 2>&1; then
  echo "A release $tag já existe. Aumente versionName/versionCode em app/build.gradle.kts." >&2
  exit 1
fi

./gradlew :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/MangaVerse.apk
cp dist/MangaVerse.apk "dist/MangaVerse-$version.apk"

gh release create "$tag" "dist/MangaVerse-$version.apk" --title "MangaVerse Android $version" --notes "$notes"
echo "Publicado: $tag (versionCode $code)"
