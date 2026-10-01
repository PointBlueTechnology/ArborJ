#!/bin/bash
#
# Sign and notarize the macOS .app bundle and .dmg installer.
#
# Prerequisites:
#   - Apple Developer ID Application certificate in Keychain
#   - Apple Developer ID Installer certificate in Keychain
#   - App-specific password stored in Keychain for notarytool
#
# Usage:
#   ./scripts/sign-and-notarize-mac.sh [--skip-notarize]
#
# Environment variables:
#   DEVELOPER_ID_APP    - (required) "Developer ID Application: Your Name (TEAMID)"
#   NOTARIZE_KEYCHAIN   - Keychain profile name for notarytool credentials
#                         (default: notarytool-arborj)
#

set -euo pipefail

# ---- Configuration ----
APP_NAME="ArborJ"
VERSION=$(sed -n 's/.*<app\.version>\(.*\)<\/app\.version>.*/\1/p' pom.xml)
APP_DIR="target/installer/${APP_NAME}.app"
DMG_FILE="target/installer/${APP_NAME}-${VERSION}.dmg"
SIGNED_DMG="target/installer/${APP_NAME}-${VERSION}-signed.dmg"

: "${DEVELOPER_ID_APP:?Set DEVELOPER_ID_APP to your 'Developer ID Application: Name (TEAMID)' identity}"
NOTARIZE_KEYCHAIN="${NOTARIZE_KEYCHAIN:-notarytool-arborj}"

SKIP_NOTARIZE=false
if [[ "${1:-}" == "--skip-notarize" ]]; then
    SKIP_NOTARIZE=true
fi

echo "=== ArborJ macOS Sign & Notarize ==="
echo ""

# ---- Step 1: Build the package if missing or stale ----
NEEDS_BUILD=false
if [[ ! -d "$APP_DIR" ]]; then
    NEEDS_BUILD=true
else
    EXISTING_VERSION=$(defaults read "$PWD/$APP_DIR/Contents/Info.plist" \
            CFBundleShortVersionString 2>/dev/null || echo "")
    if [[ "$EXISTING_VERSION" != "$VERSION" ]]; then
        echo "Existing .app is version '$EXISTING_VERSION', expected '$VERSION' — rebuilding."
        NEEDS_BUILD=true
    fi
fi
if [[ "$NEEDS_BUILD" == "true" ]]; then
    echo "Building package..."
    mvn clean package -Ppackage-mac
fi

# ---- Step 2: Sign all binaries in the .app bundle ----
# jpackage creates a .app inside the .dmg. We need to extract, sign, re-package.
# If jpackage created a .app directory directly, sign it in place.

if [[ -d "$APP_DIR" ]]; then
    echo "Signing .app bundle..."

    # Sign ALL native binaries: dylibs, jnilibs, and executables
    # Must sign inner components before the outer bundle
    find "$APP_DIR" \( -name "*.dylib" -o -name "*.jnilib" \) | while read -r lib; do
        echo "  Signing: $(basename "$lib")"
        codesign --force --timestamp --options runtime \
            --sign "$DEVELOPER_ID_APP" "$lib"
    done

    # Sign all executables (Mach-O binaries) in the runtime
    find "$APP_DIR/Contents/runtime" -type f -perm +111 | while read -r bin; do
        # Skip directories and non-Mach-O files
        if file "$bin" | grep -q "Mach-O"; then
            echo "  Signing: $(basename "$bin")"
            codesign --force --timestamp --options runtime \
                --sign "$DEVELOPER_ID_APP" "$bin"
        fi
    done

    # Sign everything in Contents/MacOS (launcher + libjli.dylib)
    find "$APP_DIR/Contents/MacOS" -type f | while read -r f; do
        echo "  Signing: MacOS/$(basename "$f")"
        codesign --force --timestamp --options runtime \
            --sign "$DEVELOPER_ID_APP" "$f"
    done

    # Sign the runtime bundle (nested bundle must be signed before outer)
    RUNTIME_DIR="$APP_DIR/Contents/runtime"
    if [[ -d "$RUNTIME_DIR" ]]; then
        echo "  Signing runtime bundle..."
        codesign --force --timestamp --options runtime \
            --sign "$DEVELOPER_ID_APP" "$RUNTIME_DIR"
    fi

    # Sign the overall app bundle last
    echo "  Signing app bundle..."
    codesign --force --timestamp --options runtime \
        --sign "$DEVELOPER_ID_APP" "$APP_DIR"

    echo "  Verifying signature..."
    codesign --verify --deep --strict "$APP_DIR"
    echo "  Signature verified."
    echo ""
fi

# ---- Step 3: Create signed DMG (if .app exists) ----
# Recreate DMG from the now-signed .app (with Applications symlink)
echo "Creating signed DMG..."
DMG_STAGING="target/dmg-staging"
rm -rf "$DMG_STAGING"
mkdir -p "$DMG_STAGING"
cp -R "$APP_DIR" "$DMG_STAGING/"
ln -s /Applications "$DMG_STAGING/Applications"
rm -f "$DMG_FILE"
hdiutil create -volname "$APP_NAME" -srcfolder "$DMG_STAGING" \
    -ov -format UDZO "$DMG_FILE"
rm -rf "$DMG_STAGING"

# Sign the DMG itself
if [[ -f "$DMG_FILE" ]]; then
    echo "Signing DMG..."
    codesign --force --timestamp --sign "$DEVELOPER_ID_APP" "$DMG_FILE"
    echo ""
fi

# ---- Step 4: Notarize ----
if [[ "$SKIP_NOTARIZE" == "true" ]]; then
    echo "Skipping notarization (--skip-notarize)"
    echo ""
    echo "Done! DMG at: $DMG_FILE"
    exit 0
fi

echo "Submitting for notarization..."
echo "(This may take several minutes)"
echo ""

# Store credentials first time:
#   xcrun notarytool store-credentials "$NOTARIZE_KEYCHAIN" \
#       --apple-id "<apple-id>" --team-id "<team-id>" --password "app-specific-password"

xcrun notarytool submit "$DMG_FILE" \
    --keychain-profile "$NOTARIZE_KEYCHAIN" \
    --wait

echo ""
echo "Stapling notarization ticket..."
xcrun stapler staple "$DMG_FILE"

echo ""
echo "Verifying notarization..."
spctl --assess --type open --context context:primary-signature "$DMG_FILE"

echo ""
echo "=== Done! ==="
echo "Signed and notarized DMG: $DMG_FILE"
