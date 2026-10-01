# Security Policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them
privately through GitHub's
[private vulnerability reporting](https://github.com/PointBlueTechnology/ArborJ/security/advisories/new).

Include the ArborJ version, a description of the issue, and steps to
reproduce. We'll acknowledge the report and keep you updated on the fix.

## Supported versions

Only the latest release receives security fixes.

## Known design choices

ArborJ deliberately does not enforce TLS hostname verification, and it lets
the user accept self-signed or privately-signed certificates after reviewing
them. This is intended for internal directory servers. See
"TLS trust model" in the README before reporting this as a vulnerability.
