# Stored profile fixtures

Captured from the Java beans at commit `8ad8ac0`, with Kryo 5.6.2, before their Kotlin migration. All values are synthetic.

- `profiles.json` freezes 197 stored blobs, their decoded fields, and their canonical re-encoding. Cases cover all 23 concrete bean types, legacy version branches, V2Ray transports, and the version-zero ECH probe.
- `constructors.json` freezes nullable construction, initialized defaults, and public JVM field names and types.
- `subscription-share.hex` freezes the subscription share format.

Tests must consume these bytes, not generate replacements from the implementation under test. A changed fixture requires an explicit storage-format migration review.
