#!/bin/sh
# Minimal SDK-only build for this dependency-free native Android app.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35 and build-tools 35.0.0}"
here_bt="$ANDROID_HOME/build-tools/35.0.0"
here_jar="$ANDROID_HOME/platforms/android-35/android.jar"
here_out="$PWD/build/native"
rm -rf "$here_out/generated" "$here_out/classes" "$here_out/dex"
mkdir -p "$here_out/generated" "$here_out/classes" "$here_out/dex"
python3 - <<'PY'
from pathlib import Path
p=Path('app/src/main/AndroidManifest.xml')
s=p.read_text().replace('<manifest xmlns:', '<manifest package="sk.technicallyhere" xmlns:')
s=s.replace('<application ', '<application android:debuggable="true" ')
Path('build/native/AndroidManifest.xml').write_text(s)
PY
"$here_bt/aapt2" compile --dir app/src/main/res -o "$here_out/resources.zip"
"$here_bt/aapt2" link -o "$here_out/unsigned.apk" -I "$here_jar" --manifest "$here_out/AndroidManifest.xml" --java "$here_out/generated" --min-sdk-version 26 --target-sdk-version 35 --version-code 13 --version-name 0.4.6 -A app/src/main/appassets "$here_out/resources.zip"
find app/src/main/java "$here_out/generated" -name '*.java' > "$here_out/sources.txt"
java com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -classpath "$here_jar" -d "$here_out/classes" @"$here_out/sources.txt"
find "$here_out/classes" -name '*.class' > "$here_out/classes.txt"
"$here_bt/d8" --lib "$here_jar" --min-api 26 --output "$here_out/dex" @"$here_out/classes.txt"
(cd "$here_out/dex" && zip -q "$here_out/unsigned.apk" classes*.dex)
"$here_bt/zipalign" -f -p 4 "$here_out/unsigned.apk" "$here_out/aligned.apk"
if [ ! -f debug-only.keystore ]; then
  keytool -genkeypair -keystore debug-only.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=SK'
fi
"$here_bt/apksigner" sign --ks debug-only.keystore --ks-pass pass:android --key-pass pass:android --out "$here_out/technically-here-debug.apk" "$here_out/aligned.apk"
"$here_bt/apksigner" verify "$here_out/technically-here-debug.apk"
printf '%s\n' "$here_out/technically-here-debug.apk"
