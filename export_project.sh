#!/usr/bin/env bash
set -e

echo "=================================================="
echo "🚀 Smart Business Manager - APK & Project Export"
echo "=================================================="

# 1. Build Debug APK with Gradle assembleDebug
echo "📦 Step 1: Building Android Debug APK with assembleDebug..."
gradle assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: APK not found at $APK_PATH"
    exit 1
fi

APK_SIZE=$(stat -c%s "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
if [ "$APK_SIZE" -lt 1000000 ]; then
    echo "❌ Error: APK size ($APK_SIZE bytes) is suspiciously small. Build may be incomplete."
    exit 1
fi

APK_SIZE_MB=$(( APK_SIZE / 1048576 ))
echo "✅ APK generated successfully: $APK_PATH ($APK_SIZE_MB MB)"

# 2. Copy APK to release-files/SmartBusinessManager.apk
echo "📂 Step 2: Placing APK into release-files/SmartBusinessManager.apk..."
mkdir -p release-files
cp -f "$APK_PATH" release-files/SmartBusinessManager.apk

# 3. Create SmartBusinessManager_Export.zip with exact requested structure
echo "🗜️  Step 3: Creating SmartBusinessManager_Export.zip via Gradle..."
gradle exportProjectZip

# 4. Verify ZIP
echo "🔍 Step 4: Verifying ZIP contents and integrity..."
if ! unzip -t SmartBusinessManager_Export.zip > /dev/null 2>&1; then
    echo "❌ Error: Corrupted ZIP file!"
    exit 1
fi

if ! unzip -l SmartBusinessManager_Export.zip | grep -q "release-files/SmartBusinessManager.apk"; then
    echo "❌ Error: release-files/SmartBusinessManager.apk missing inside ZIP!"
    exit 1
fi

ZIP_SIZE=$(stat -c%s "SmartBusinessManager_Export.zip" 2>/dev/null || stat -f%z "SmartBusinessManager_Export.zip" 2>/dev/null)
ZIP_SIZE_MB=$(( ZIP_SIZE / 1048576 ))

echo "=================================================="
echo "🎉 Export Complete & Verified Successfully!"
echo "📁 APK Path: release-files/SmartBusinessManager.apk ($APK_SIZE_MB MB)"
echo "📦 ZIP Path: SmartBusinessManager_Export.zip ($ZIP_SIZE_MB MB)"
echo "✨ Contains both full Android Studio source code and installable APK!"
echo "=================================================="
