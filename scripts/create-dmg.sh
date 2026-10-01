#!/bin/bash
#
# Creates a macOS DMG with the .app bundle and Applications symlink.
# Run after: mvn clean package -Ppackage-mac
#

set -euo pipefail

APP_NAME="ArborJ"
VERSION=$(sed -n 's/.*<app\.version>\(.*\)<\/app\.version>.*/\1/p' pom.xml)
APP_DIR="target/installer/${APP_NAME}.app"
DMG_DIR="target/dmg-staging"
DMG_FILE="target/installer/${APP_NAME}-${VERSION}.dmg"

if [[ ! -d "$APP_DIR" ]]; then
    echo "Error: $APP_DIR not found. Run 'mvn clean package -Ppackage-mac' first."
    exit 1
fi

echo "Creating DMG..."

# Clean staging area
rm -rf "$DMG_DIR"
mkdir -p "$DMG_DIR"

# Copy app and create Applications symlink
cp -R "$APP_DIR" "$DMG_DIR/"
ln -s /Applications "$DMG_DIR/Applications"

# Remove old DMG if exists
rm -f "$DMG_FILE"

# Create DMG
hdiutil create -volname "$APP_NAME" \
    -srcfolder "$DMG_DIR" \
    -ov -format UDZO \
    "$DMG_FILE"

# Clean up staging
rm -rf "$DMG_DIR"

echo ""
echo "DMG created: $DMG_FILE"
echo "Size: $(du -h "$DMG_FILE" | cut -f1)"
