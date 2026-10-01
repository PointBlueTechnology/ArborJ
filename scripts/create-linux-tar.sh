#!/bin/bash
#
# Creates an architecture-independent tar.gz for Linux.
# User provides their own JRE (Java 25+).
#
# Usage: mvn clean package && ./scripts/create-linux-tar.sh
#

set -euo pipefail

APP_NAME="ArborJ"
VERSION=$(sed -n 's/.*<app\.version>\(.*\)<\/app\.version>.*/\1/p' pom.xml)
STAGING="target/${APP_NAME}-${VERSION}"
TAR_FILE="target/installer/${APP_NAME}-${VERSION}-linux.tar.gz"

echo "Creating Linux tar.gz..."

# Clean
rm -rf "$STAGING" "$TAR_FILE"
mkdir -p "$STAGING/lib" "target/installer"

# Copy application jars, leaving JavaFX out. Maven resolves JavaFX's native
# jars for whichever host runs the build, so target/lib holds the build
# machine's natives — macOS dylibs when this is run from a Mac. The
# classifier-less javafx jars are empty stubs (a manifest and nothing else);
# every class and native library lives in the platform-classified jar. A
# tarball packed from target/lib alone therefore could not start on Linux.
for jar in target/lib/*.jar; do
    case "$(basename "$jar")" in
        javafx-*) continue ;;
    esac
    cp "$jar" "$STAGING/lib/"
done
cp target/${APP_NAME}-${VERSION}.jar "$STAGING/lib/"

# Fetch the real JavaFX natives for both Linux architectures. They cannot
# share one directory: the x86_64 and aarch64 jars carry identically-named
# .so files, so whichever landed on the classpath first would win and leave
# the other architecture broken. arborj.sh picks a directory at run time.
JAVAFX_VERSION=$(sed -n 's/.*<javafx\.version>\(.*\)<\/javafx\.version>.*/\1/p' pom.xml)
if [[ -z "$JAVAFX_VERSION" ]]; then
    echo "Error: could not read <javafx.version> from pom.xml"
    exit 1
fi

echo "Fetching JavaFX ${JAVAFX_VERSION} natives for Linux..."
for arch_pair in "x86_64:linux" "aarch64:linux-aarch64"; do
    arch="${arch_pair%%:*}"
    classifier="${arch_pair##*:}"
    mkdir -p "$STAGING/lib/javafx/$arch"
    for module in base graphics controls; do
        mvn -q dependency:copy \
            -Dartifact="org.openjfx:javafx-${module}:${JAVAFX_VERSION}:jar:${classifier}" \
            -DoutputDirectory="$STAGING/lib/javafx/$arch"
    done
done

# Copy icon and license
cp src/main/resources/com/pointbluetech/arborj/icons/icon-256.png "$STAGING/arborj.png"
cp LICENSE.txt "$STAGING/"
cp THIRD-PARTY-LICENSES.txt "$STAGING/"

# Create launch script
cat > "$STAGING/arborj.sh" << 'LAUNCH'
#!/bin/bash
# ArborJ Launch Script — requires Java 25+
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# JavaFX ships its native code per architecture, and the x86_64 and aarch64
# jars use identical names inside, so only the matching one goes on the
# classpath.
case "$(uname -m)" in
    x86_64|amd64)  FX_DIR="$SCRIPT_DIR/lib/javafx/x86_64" ;;
    aarch64|arm64) FX_DIR="$SCRIPT_DIR/lib/javafx/aarch64" ;;
    *)
        echo "Error: unsupported architecture '$(uname -m)'."
        echo "ArborJ bundles JavaFX for x86_64 and aarch64 only."
        exit 1
        ;;
esac

if [ ! -d "$FX_DIR" ]; then
    echo "Error: missing JavaFX libraries for $(uname -m) at $FX_DIR"
    exit 1
fi

CLASSPATH="$SCRIPT_DIR/lib/*:$FX_DIR/*"

# Check for Java
if command -v java &>/dev/null; then
    JAVA=java
elif [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    echo "Error: Java not found. Install Java 25+ and ensure 'java' is on your PATH."
    exit 1
fi

# Check version
JAVA_VER=$($JAVA -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "$JAVA_VER" -lt 25 ] 2>/dev/null; then
    echo "Error: Java 25+ required (found Java $JAVA_VER)"
    exit 1
fi

# Read user heap setting if present
HEAP_OPT="-Xmx1g"
if [ -f "$HOME/.arborj/jvm.options" ]; then
    HEAP_OPT="$(cat "$HOME/.arborj/jvm.options")"
fi

exec $JAVA \
    $HEAP_OPT \
    --enable-native-access=ALL-UNNAMED \
    -cp "$CLASSPATH" \
    com.pointbluetech.arborj.Launcher "$@"
LAUNCH

chmod +x "$STAGING/arborj.sh"

# Create .desktop file for Linux menu integration
cat > "$STAGING/arborj.desktop" << DESKTOP
[Desktop Entry]
Name=ArborJ
Comment=Cross-platform LDAP Browser
Exec=$STAGING/arborj.sh
Icon=$STAGING/arborj.png
Terminal=false
Type=Application
Categories=Network;
DESKTOP

# Create README
cat > "$STAGING/README.txt" << README
ArborJ ${VERSION} — Cross-Platform LDAP Browser
================================================

Requirements: Java 25 or later, on x86_64 or aarch64

To run:
    ./arborj.sh

JavaFX for both architectures is bundled under lib/javafx/; arborj.sh picks
the right one from 'uname -m'.

To install the menu entry (optional):
    cp arborj.desktop ~/.local/share/applications/
    (Edit the Exec= and Icon= paths to match your install location)

Configuration is stored in ~/.arborj/
README

# Create tar
cd target
tar czf "installer/${APP_NAME}-${VERSION}-linux.tar.gz" "${APP_NAME}-${VERSION}"
cd ..

# Clean staging
rm -rf "$STAGING"

echo ""
echo "Created: $TAR_FILE"
echo "Size: $(du -h "$TAR_FILE" | cut -f1)"
