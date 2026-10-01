# ArborJ

[![CI](https://github.com/PointBlueTechnology/ArborJ/actions/workflows/ci.yml/badge.svg)](https://github.com/PointBlueTechnology/ArborJ/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.txt)

A cross-platform LDAP browser written in Java / JavaFX. Designed as a desktop
tool for administering eDirectory, Active Directory, OpenLDAP, and generic
LDAP v3 directories — with attention to the realities of those environments
(private CAs, self-signed test certs, NMAS, DirXML, partition replicas,
unicodePwd, schema variations).

Signed installers for macOS and Windows and a Linux tarball are available
from [pointbluetech.com/arborj](https://www.pointbluetech.com/arborj/).

### On a Mac? Try Arbor

**Mac users should consider [Arbor](https://www.pointbluetech.com/)**, the
commercial native macOS version of this tool. It's built in SwiftUI, needs no
Java runtime, runs natively on Apple Silicon and Intel, and syncs profiles
through iCloud. It's on the Mac App Store in two editions:

- [Arbor LDAP Browser](https://apps.apple.com/us/app/arbor-ldap-browser/id6759270047?mt=12):
  free, with Pro features as an in-app purchase.
- [ArborPro LDAP Browser](https://apps.apple.com/us/app/arborpro-ldap-browser/id6802792427?mt=12):
  a one-time upfront purchase, suited to enterprise and volume purchasing.

---

## Table of contents

- [Features](#features)
- [Requirements](#requirements)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Design decisions](#design-decisions)
- [Packaging](#packaging)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### Browsing and searching
- Tree view with lazy-loaded children, multi-window support, and a context
  menu per entry (rename, add attribute, new entry, delete, copy DN, export
  LDIF, change password where supported).
- Fast typed-prefix navigation in the tree.
- Search panel with attribute picker, filter builder, scope / size-limit
  controls, persistent saved searches, and a recent-search history menu.
- Search results pane plus a spreadsheet-style **Table View** with
  user-ordered columns, in-line editable cells (when not read-only), and a
  CSV export that respects user-configurable export settings.

### Attribute editing
- Syntax-aware attribute renderer (DN, GeneralizedTime, NetAddress, UAC
  flags for AD, etc.).
- Inline edit-and-save with atomic LDAP modify (DELETE + ADD in one request)
  so a partial failure can't leave the entry with the old value already
  deleted.
- DN picker (tree dialog) for DN-syntax attributes.
- **Hex viewer** for binary / stream-syntax attributes:
  - Virtualized display for arbitrarily large values (DirXML configs,
    certificates, large stream values).
  - Edit Hex… sub-dialog for byte-level edits via paste (tolerates
    whitespace, commas, colons, and the dialog's own dump format).
  - Load from File… and Save to File… for binary import/export.
  - View as Text toggle for textual blobs (XML configs, etc.).

### LDIF
- **Streaming LDIF import** — entries flow straight from disk to the
  directory without holding the whole file or the entry list in memory, so
  multi-hundred-MB LDIFs don't OOM.
- LDIF export at entry, children, subtree, and search-result granularity.
- User-configurable LDIF / CSV format settings (line separator, fold length,
  delimiters, encoding).

### TLS and credentials
- Self-signed and private-CA-signed certificates are supported via an
  explicit user-acceptance prompt — see the `InteractiveTrustManager`
  javadoc in `LDAPService.java` for the full threat model.
- Approved certs are stored per host:port in an AES-GCM-encrypted credential
  store at `~/.arborj/credentials.enc`.
- Saved passwords (also AES-GCM) are deleted automatically when the user
  unchecks "Save Password" and reconnects.
- Connection profiles persisted as JSON in `~/.arborj/profiles.json`, with
  atomic temp-write-and-rename so a crash mid-save can't corrupt the file.
- Auto-reconnect on heartbeat failure, with full state refresh (directory
  type, schema, root nodes) so the UI doesn't show stale data after a drop.

### Directory-specific features

**eDirectory:**
- Partition Status — replica holders for any partition, populated via the
  authoritative NDS `getReplicaInfo` extended operation rather than the
  unreliable `Replica` attribute integer fields.
- Replication Status — per-partition replica state on the connected server.
- DirXML Command (gated on `DIRXML_COMMAND_OID` advertisement).
- DirXML Associations modifier — single-object and bulk operations
  (add/remove/disable/enable/migrate) on `DirXML-Associations`.
- ACL Editor — read/edit `ACL` attribute values per RFC-style trustee rules.
- Effective Rights (gated on `GET_EFFECTIVE_PRIVILEGES_REQUEST_OID`).
- NMAS error decoding — RFC 3062 password failures surface real reasons
  ("password too short", "password unique violation", etc.) extracted from
  NMAS-specific response controls and matched-DN.

**Active Directory:**
- Domain info, password policy, account status, group membership, replication
  metadata viewers.
- Password reset via direct `unicodePwd` modify (no UAC clobber: the dialog
  blocks the UAC flag update if it couldn't first read the existing value).
- Administrative password reset (RFC 3062 with null oldPassword) for non-AD
  directories where the bound user has reset rights.

**OpenLDAP:**
- Server Monitor and Server Statistics views via `cn=monitor`.

### App-level
- Persistent activity log (modifications, searches) with masking for
  sensitive attribute values.
- "What's New" dialog tied to `APP_VERSION`, shown once after each upgrade.
- Update-check pings a Cloudflare Workers endpoint at startup; manual
  re-check available from the gear menu.
- Multi-window support — multiple independent connections in one app
  instance.
- macOS Cmd+W closes the window (matches platform convention); other
  shortcuts are `Cmd+,` Settings, `Cmd+N` New Connection, `Cmd+E` Export
  selected entry.

---

## Requirements

### To run

- **Java 25** or later (JavaFX is bundled in the platform installers; the
  Linux tar.gz expects you to provide your own JRE).

### To build

- **Java 25 JDK** — `mvn` uses `maven.compiler.source/target=25`.
- **Maven 3.9+**.
- For installer packaging: **`jpackage`** (ships with JDK 25). The
  `package-mac` / `package-linux` / `package-windows` profiles auto-activate
  by OS family, so a plain `mvn clean package` produces the platform's
  app-image.

---

## Building from source

```bash
# Compile + run unit tests
mvn test

# Build platform installer (auto-activates the right profile)
mvn clean package
# → target/ArborJ-<version>.jar              (the executable jar)
# → target/lib/                              (runtime classpath)
# → target/installer/ArborJ.app              (mac, jpackage app-image)
# → target/installer/ArborJ-<version>.deb    (linux, jpackage)
# → target/installer/ArborJ-<version>.msi    (windows, jpackage)

# Run from source
mvn javafx:run
# OR directly: java --enable-native-access=ALL-UNNAMED \
#   -cp "target/lib/*" com.pointbluetech.arborj.Launcher
```

Tests live in `src/test/java`. The whole suite is fast (~3s) and runs on every
`mvn package`. Skip with `-DskipTests` if you really need to.

---

## Project structure

```
src/main/java/com/pointbluetech/arborj/
├── ArborJApp.java               JavaFX Application entry, APP_VERSION, multi-window
├── Launcher.java                Plain main() that delegates to ArborJApp (jpackage friendly)
├── controller/
│   └── MainController.java      Service ↔ UI bridge; exposes JavaFX properties
├── model/                       Records / enums / connection profile / etc.
├── service/
│   ├── LDAPService.java         All LDAP ops; trust manager; heartbeat
│   ├── SchemaService.java       Schema introspection, container detection
│   ├── ProfileStore.java        Connection profiles (atomic JSON persistence)
│   ├── CredentialStore.java     AES-GCM encrypted password / approved-cert store
│   ├── LDIFImporter.java        Streaming RFC 2849 parser + importer
│   ├── LDIFExporter.java        LDIF emitter with format settings
│   ├── ActivityLogger.java      In-memory mod / search log
│   ├── AppLogger.java           Tees stdout/stderr to ~/.arborj/arborj.log
│   ├── UpdateChecker.java       Hits the Cloudflare Workers version endpoint
│   ├── ExportSettings.java      LDIF/CSV format settings
│   ├── FontSettings.java        Detail-pane font preferences
│   └── SavedSearchStore.java
├── view/
│   ├── MainView.java            Toolbar, search panel, dir-services menus
│   ├── ConnectionDialog.java    Profile manager + connect form
│   ├── SettingsDialog.java
│   ├── AttributePickerDialog.java   Pick / Paste tabs, history dropdown
│   ├── AttributeTableView.java
│   ├── SearchResultsTableView.java  Spreadsheet-style results with column reorder
│   ├── HexEditorDialog.java     Virtualized hex viewer + Edit Hex / Load / Save
│   ├── FilterBuilderDialog.java RFC 4515 escape-aware visual builder
│   ├── ChangePasswordDialog.java AD + RFC 3062, with admin-reset toggle
│   ├── CertTrustDialog.java
│   ├── WhatsNewDialog.java
│   ├── ad/                      Active Directory–specific views
│   ├── edir/                    eDirectory–specific views
│   └── openldap/                OpenLDAP monitor / statistics views
└── util/
    ├── ADHelpers.java           UAC flag definitions
    ├── LDAPFilterValidator.java
    └── NDSNetAddress.java       Decoder for NDS Net Address binary syntax

scripts/
├── create-dmg.sh                DMG-only (subset of sign-and-notarize-mac.sh)
├── create-linux-tar.sh          Portable Linux tar.gz (BYO Java)
├── sign-and-notarize-mac.sh     Full Mac release flow: codesign → DMG → notary → staple
├── sign-windows.ps1             Trusted Signing for MSI / app-image
├── signing-metadata.example.json  Template for the (gitignored) Trusted Signing config
└── check-version-endpoint.sh    Smoke-test the update endpoint
```

---

## Design decisions

These are the non-obvious choices that have come up in code review or have a
written rationale somewhere in the codebase. If a future SAST run or
reviewer flags one, this is where to start.

### TLS trust model

`InteractiveTrustManager` (in `LDAPService.java`) is deliberately permissive:
default keystore is tried first, and any failure routes to a user-acceptance
prompt with full cert details. After approval, the cert DER is stored per
host:port and accepted silently on subsequent connects. Cert rotation
re-prompts.

Hostname / SAN matching is **intentionally not enforced** at either the LDAP
or SSL layer (we explicitly call `setSSLSocketVerifier(null)` and never set
`setEndpointIdentificationAlgorithm`). Rationale: the tool exists to talk to
internal directories where IP-only access, multi-hostname clusters, and
mismatched CN/SAN are common.

The `InteractiveTrustManager` class javadoc carries the full threat model
and a "Reviewer note" block specifically for future SAST output.

### Atomic file writes

`ProfileStore.persist` and `CredentialStore.persist` write to a temp file in
the same directory and then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`
into place (with a non-atomic-rename fallback). A crash mid-write cannot
corrupt the existing store.

### Streaming LDIF

`LDIFImporter.streamingImport(Path, ...)` walks the file with a
`BufferedReader` via an inner `EntryReader` that yields one entry at a time.
The dialog runs entries through the LDAP service in lockstep — no
`Files.readString`, no full entry list in memory. Multi-hundred-MB files
work cleanly.

### Virtualized hex view

JavaFX's `TextArea` has rendering limits that make ~500K+ character dumps
look truncated. The hex view is a virtualized `ListView<String>` (one
16-byte row per item). The trade-off is that the bytes shown can't be
edited inline — `Edit Hex…`, `Load from File…`, and `View as Text` cover
that gap.

### Atomic attribute edit

`MainController.saveAttributeValue` issues `[DELETE(old), ADD(new)]` as a
single LDAP modify request via `modifyMultipleAttributes`. RFC 4511 §4.6
guarantees transactional application — either both apply or neither does —
so a failed ADD can't leave the entry with the old value already deleted.

### Modal owner pattern

Every `Stage` / `Dialog<Void>` constructor accepts an optional `Window owner`
and calls `initOwner(owner)` if non-null. Without an owner, modals on macOS
flash and slip behind the main window. Call sites use
`MainView.windowOf()` (or `getDialogPane().getScene().getWindow()` from
inside a `Dialog`).

### eDirectory feature gating

`MainController.dirXMLSupportedProperty / effectiveRightsSupportedProperty
/ passwordModifySupportedProperty` are populated from `RootDSE
supportedExtension` after connect. The corresponding menu items in
`MainView` bind their `visibleProperty` to those flags so unsupported
operations don't appear (and don't fail with confusing errors).

### Partition status from `getReplicaInfo`

The `Replica` attribute on a partition root carries integer fields whose
positional layout differs between root and non-root partitions in a way we
couldn't characterize reliably. `PartitionSyncView` therefore uses the
`Replica` attribute only to enumerate **which servers hold replicas** and
their address hints — actual `replicaType` / `replicaState` /
`replicaNumber` come from the NDS `getReplicaInfo` extended operation,
which is what `ReplicationStatusView` already uses.

### NMAS error decoding

eDirectory's RFC 3062 response often has only `"NMAS Change Password
extension failed"` in the diagnostic message. `LDAPService.formatPasswordError`
extracts the real reason from NMAS response controls
(`2.16.840.1.113719.1.27.103.*`), the matched-DN field, and the LDAP result
code, mapping known codes to text like `"Password too short"`.

### AD UAC: read-then-modify

`ChangePasswordDialog.fetchADFlags` reads the existing
`userAccountControl` before the user submits. If the read fails, the
must-change / never-expires checkboxes are disabled and
`updateADPasswordFlags` is skipped entirely so a fabricated zero can't wipe
account-type bits or `ACCOUNTDISABLE`.

### Per-instance UI state

`AttributeTableView.expandedRows` is per-instance (was previously a
JVM-static set), so multiple windows track expansion independently.

### Filter escaping (RFC 4515)

`FilterBuilderDialog.FilterParser.parseComparison` decodes `\xx` hex
escapes and only treats unescaped `)` as the end of a value.
`FilterSerializer.appendEscapedValue` writes `\`, `*`, `(`, `)`, `NUL` as
`\xx` so filters round-trip. Without these the visual builder would corrupt
filters with literal wildcards or parens in values.

---

## Packaging

The `pom.xml` defines four packaging profiles — three auto-activate by OS
family (`package-mac`, `package-linux`, `package-windows`) and one is
opt-in (`package-windows-portable`).

A plain `mvn clean package` on each platform produces the native installer
into `target/installer/`. Signing is a platform-specific post-build step
handled by the scripts under `scripts/`. The scripts read the version from
`pom.xml`.

### macOS

```bash
export DEVELOPER_ID_APP="Developer ID Application: Your Name (TEAMID)"
./scripts/sign-and-notarize-mac.sh            # codesign → DMG → notarize → staple
./scripts/sign-and-notarize-mac.sh --skip-notarize   # local test build
```

Requires an Apple Developer ID certificate in the login keychain and a
`notarytool` keychain profile (default name `notarytool-arborj`, override
with `NOTARIZE_KEYCHAIN`):

```bash
xcrun notarytool store-credentials notarytool-arborj \
    --apple-id <apple-id> --team-id <team-id> --password <app-specific-password>
```

`scripts/create-dmg.sh` builds an unsigned DMG.

### Windows

```powershell
mvn clean package                       # → target\installer\ArborJ-<v>.msi
mvn package -Ppackage-windows-portable  # → target\installer\ArborJ\  (portable app-image)
.\scripts\sign-windows.ps1              # signs both, zips the portable
```

`sign-windows.ps1` uses **Azure Trusted Signing** via `signtool.exe` and
the `Azure.CodeSigning.Dlib`. Copy `scripts/signing-metadata.example.json`
to `scripts/signing-metadata.json` (gitignored) and fill in your account
and certificate profile. Pass `-MsiPath`, `-AppImageDir`, or `-ExePath` to
target a specific artifact; `-NoZip` skips the zip step.

### Linux

```bash
mvn clean package           # → target/installer/ArborJ-<v>.deb (only on Linux)
./scripts/create-linux-tar.sh
# → target/installer/ArborJ-<version>-linux.tar.gz (BYO Java 25+)
```

The tar.gz bundles JavaFX for x86_64 and aarch64; the launcher script picks
the right one with `uname -m`. It also emits a `.desktop` file.

### Update check

Official builds check `https://arborj-downloads.jcombs.workers.dev/version/arborj`
for a newer version at startup (see `UpdateChecker.java`). The request
carries only the current version number.

---

## Testing

```bash
mvn test
```

The suite is pure-logic unit tests (no JavaFX runtime, no LDAP server). It
covers:

- `LDAPFilterValidator` — RFC 4515 syntax checks.
- `ADHelpers` — UAC bit definitions and decoding.
- `NDSNetAddress` — NDS Net Address binary syntax decoder.
- `MainController.parentDnOf` — DN parent calculation including escaped
  RDN commas.
- `LDAPEntry` — attribute lookup, multi-value handling.
- `LDIFImporter` — RFC 2849 parser, base64 / continuation / version-line
  handling, version-header skipping, blank input edge cases.
- `AssociationExportFile` — DirXML association JSON export round-trip.
- `AttributePickerDialog.parseAttributeList` — paste-list parsing
  (separators, dedupe, quote stripping, `*` preservation).
- `HexEditorDialog.parseHexInput` — hex blob parser with dialog-format
  awareness, mixed separators, dump-line offset stripping, gutter
  detection, error cases.

End-to-end / UI testing is currently manual against real eDirectory,
Active Directory, and OpenLDAP servers. Add automated tests when adding
logic that's straightforward to unit-test.

---

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
To report a security issue, see [SECURITY.md](SECURITY.md).

## License

ArborJ is released under the [MIT License](LICENSE.txt). Third-party
library licenses are listed in [THIRD-PARTY-LICENSES.txt](THIRD-PARTY-LICENSES.txt).
