# bizfunnel-license (Rust / Tauri)

This is the license client for Tauri apps, and for any other Rust desktop app. It works on Windows, macOS and Linux.

What it does:
- **Device binding.** The device ID is the OS machine ID hashed with your product slug, so the raw ID never leaves the computer.
- **Signed answers.** Every server answer, and the stored license, is checked with your Ed25519 **public** key. A fake server or an edited license file can't unlock the app.
- **Offline use.** No network call is made until the product's re-check period is up. After that, the app keeps working through the grace period if your server is down.
- **Updates.** It can check for a newer build. The version, URL and SHA-256 all come from the signed answer.

## Add it to your Tauri app

Copy this folder into your repo, e.g. `crates/bizfunnel-license`. Then in `src-tauri/Cargo.toml`:

```toml
[dependencies]
bizfunnel-license = { path = "../crates/bizfunnel-license" }
```

Then in `src-tauri/src/lib.rs` (Tauri v2):

```rust
use bizfunnel_license::{Config, LicenseClient, LicenseStatus, UpdateInfo};
use tauri::{Manager, State};

const API: &str = "https://licenses.yourdomain.com/api/v1";
const PRODUCT: &str = "pos-desktop";                          // product slug from the panel
const PUBLIC_KEY: &str = "PASTE_FROM_SETTINGS_AND_API";     // Settings & API → Public key

struct License(LicenseClient);

#[tauri::command]
async fn license_status(lic: State<'_, License>) -> Result<LicenseStatus, String> {
    Ok(lic.0.check(false).await)
}

#[tauri::command]
async fn license_activate(key: String, lic: State<'_, License>) -> Result<LicenseStatus, String> {
    lic.0.activate(&key).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn license_deactivate(lic: State<'_, License>) -> Result<LicenseStatus, String> {
    lic.0.deactivate().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn license_update(lic: State<'_, License>, app: tauri::AppHandle) -> Result<Option<UpdateInfo>, String> {
    let platform = std::env::consts::OS; // "windows" | "macos" | "linux"
    lic.0.check_update(platform, &app.package_info().version.to_string()).await.map_err(|e| e.to_string())
}

/// Guard paid features in RUST, not only in the web UI (the UI can be edited).
#[tauri::command]
async fn export_report(lic: State<'_, License>) -> Result<String, String> {
    let s = lic.0.check(false).await;
    if !s.valid { return Err(s.message); }
    if !s.has_feature("reports") { return Err("Reports are not included in your plan.".into()); }
    Ok("…".into())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let dir = app.path().app_data_dir()?;
            let client = LicenseClient::new(
                Config::new(API, PRODUCT, [("k1", PUBLIC_KEY)], dir.join("license.json"))
                    .app_version(&app.package_info().version.to_string()),
            )?;
            app.manage(License(client));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![license_status, license_activate, license_deactivate, license_update, export_report])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

In the frontend:

```ts
import { invoke } from '@tauri-apps/api/core';

const s = await invoke<{ valid: boolean; status: string; message: string }>('license_status');
if (!s.valid) showActivationScreen(s.message);              // status: no_key | expired | suspended | …
// on the activation screen:
const r = await invoke('license_activate', { key: input.value });
```

## API

| Call | What it does |
| --- | --- |
| `LicenseClient::new(Config)` | Builds the client. `Config::new(api_url, product_slug, [(kid, public_key)], storage_path)` plus `.app_version()`, `.device_label()`, `.timeout()` |
| `activate(key).await` | Binds the key to this device and stores the signed local key |
| `check(force_online).await` | Returns a `LicenseStatus` and never errors. No network call until the re-check is due |
| `deactivate().await` | Frees this device's seat and forgets the key |
| `check_update(platform, current_version).await` | Returns `Some(UpdateInfo { version, url, sha256, notes, … })` when a newer build is available within the license's updates period |
| `LicenseClient::verify_download(bytes, sha256)` | Checks a downloaded installer before running it |
| `machine_id()` | This device's ID, as sent to the server |

**`LicenseStatus`** has these fields and helpers:
- `valid`
- `status`: `active`, `trial`, `grace`, `no_key`, `suspended`, `expired`, `revoked`, `invalid`, `limit_reached`, `not_activated`, `binding_mismatch`, `rate_limited` or `error`
- `message`: text to show the user
- `claims`: plan, features, limits, expiry, `licensed_to`, …
- `has_feature("x")`, `limit("max_users")`, `in_grace()`

## Tips

- Keep `license.json` in the app data folder. It's useless on another machine, because it's bound to this device and signed.
- Call `check(false)` at start-up and, say, every few hours. It costs nothing until the re-check is due.
- Release builds: enable `strip = true` and `lto = true` in `[profile.release]` so the checks are harder to find and patch.

## Test

```
LH_API=https://licenses-test.yourdomain.com/api/v1 LH_PUBKEY=... LH_KEY=<key with free seats> cargo test --test live -- --nocapture
cargo run --example cli -- <API> <PUBKEY> pos-desktop check
```
