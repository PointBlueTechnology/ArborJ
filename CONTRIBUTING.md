# Contributing to ArborJ

Thanks for your interest in improving ArborJ.

## Getting started

1. Install JDK 25 and Maven 3.9+.
2. `mvn test` to build and run the unit tests.
3. `mvn javafx:run` to launch the app.

## Pull requests

- Keep changes focused; one fix or feature per PR.
- Add unit tests for logic that can be tested without a live LDAP server
  or the JavaFX runtime (see the existing tests under `src/test/java`).
- Make sure `mvn test` passes.
- Describe how you tested the change, including which directory servers
  (eDirectory, Active Directory, OpenLDAP, other) you tried it against.

## Reporting bugs

Open an issue with the ArborJ version, your OS, the directory server type
and version, and steps to reproduce. Remove any hostnames, DNs, or other
details from your environment that you don't want to make public.

By contributing, you agree that your contributions are licensed under the
MIT License.
