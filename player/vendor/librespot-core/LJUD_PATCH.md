# Vendored librespot-core 0.8.0

Unmodified copy of [librespot-core 0.8.0](https://crates.io/crates/librespot-core)
(MIT, see `LICENSE`) with **one change**, in `src/config.rs`:

```rust
pub const OS: &str = "linux";   // was: std::env::consts::OS
```

Compiled for Android, librespot otherwise identifies itself to Spotify as the
Android client while logging in with the desktop ("keymaster") client id, and
Spotify rejects that with `Login request was denied: BAD_REQUEST`. Presenting as
desktop Linux, like regular librespot, works.

Used via `[patch.crates-io]` in `player/Cargo.toml`.
