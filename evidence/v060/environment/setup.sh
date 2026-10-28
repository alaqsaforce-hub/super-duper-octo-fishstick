#!/usr/bin/env bash
set -eu
BASE=/home/user/.cache/voicedock-tools
SDK="$BASE/android-sdk"
JDK="$BASE/jdk17"
mkdir -p "$BASE" "$SDK/cmdline-tools"
echo 'Downloading JDK 17 and Android command-line tools...'
curl -fL --retry 3 --connect-timeout 30 -o "$BASE/jdk17.tar.gz" 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' &
p1=$!
curl -fL --retry 3 --connect-timeout 30 -o "$BASE/cmdline-tools.zip" 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' &
p2=$!
wait "$p1"
wait "$p2"
echo 'Installing JDK and SDK manager...'
mkdir -p "$JDK"
tar -xf "$BASE/jdk17.tar.gz" --strip-components=1 -C "$JDK"
unzip -q "$BASE/cmdline-tools.zip" -d "$SDK/cmdline-tools"
mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
rm -f "$BASE/jdk17.tar.gz" "$BASE/cmdline-tools.zip"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$PATH"
java -version
# yes may get SIGPIPE after sdkmanager is done, so do not use pipefail.
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null || true
echo 'Installing SDK platform 35, build tools, NDK 27, CMake and platform tools...'
yes | sdkmanager --sdk_root="$SDK" 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.0.12077973' 'cmake;3.22.1' 'platform-tools'
echo 'Android build environment ready.'
