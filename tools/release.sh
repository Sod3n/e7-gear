#!/bin/sh
# Builds the signed release APK and, with --publish, puts it on GitHub Releases as v<appVersion>.
# The release key is outside the repo: ~/.e7gear/e7gear-release.jks (keep a copy on another disk),
# its password in the Keychain as "e7gear-release-key". Losing either means no update can ever install over the app.
# Usage: tools/release.sh [--publish]    (bump appVersion in gradle.properties first)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
export E7_RELEASE_STORE=${E7_RELEASE_STORE:-$HOME/.e7gear/e7gear-release.jks}
E7_RELEASE_PASSWORD=$(security find-generic-password -a "$USER" -s e7gear-release-key -w)
export E7_RELEASE_PASSWORD
# the certificate every release must carry; a different one can't update an installed app
CERT="D5:99:AB:A8:A8:CE:15:80:CE:F3:63:10:36:91:B5:59:CD:A1:85:FF:2D:3C:82:78:9B:D7:1E:55:13:F3:CB:49"
VERSION=$(sed -n 's/^appVersion=//p' gradle.properties)

./gradlew -q testDebugUnitTest assembleRelease
APK="app/build/outputs/apk/release/e7.gearscan-$VERSION-release.apk"
SIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
"$SIGNER" verify --print-certs "$APK" | grep -q "SHA-256 digest: $(echo "$CERT" | tr -d ':' | tr 'A-F' 'a-f')" ||
	{ echo "$APK isn't signed with the release key; not releasing"; exit 1; }
echo "$APK: v$VERSION, signed with the release key"

[ "$1" = "--publish" ] || exit 0
OUT="E7-Gear-$VERSION.apk"
cp "$APK" "build/$OUT"
gh release create "v$VERSION" "build/$OUT" --title "E7 Gear $VERSION" --notes-file - <<EOF
Install: download **$OUT** on your phone and open it (allow installs from this source once).
The app checks for newer versions itself and shows a Download link when one is out.
EOF
