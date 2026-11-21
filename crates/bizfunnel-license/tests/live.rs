//! Live test against a running License Manager. Skipped unless these are set:
//!   LH_API=http://127.0.0.1:8080/api/v1  LH_PUBKEY=<base64>  LH_KEY=<POS lifetime key with 2 free seats>
//!   LH_HAS_UPDATE=1 (optional: a windows build newer than 3.4.0 exists in the license period)
use bizfunnel_license::{Config, LicenseClient};
use std::time::Duration;

fn env() -> Option<(String, String, String)> {
    Some((std::env::var("LH_API").ok()?, std::env::var("LH_PUBKEY").ok()?, std::env::var("LH_KEY").ok()?))
}

fn client(api: &str, pubkey: &str, dir: &std::path::Path, machine: &str) -> LicenseClient {
    LicenseClient::new(
        Config::new(api, "pos-desktop", [("k1", pubkey)], dir.join("license.json"))
            .app_version("3.4.0")
            .device_label("Rust test runner")
            .machine_id_override(machine)
            .timeout(Duration::from_secs(5)),
    )
    .unwrap()
}

#[tokio::test]
async fn full_flow() {
    let Some((api, pubkey, key)) = env() else {
        eprintln!("LH_* not set: skipping live test");
        return;
    };
    let tmp = tempfile::tempdir().unwrap();
    let machine = format!("rust-test-{}", rand_suffix());
    let c = client(&api, &pubkey, tmp.path(), &machine);

    // No key yet
    assert_eq!(c.check(false).await.status, "no_key");

    // Activate
    let s = c.activate(&format!("  {}  ", key.to_lowercase())).await.unwrap();
    assert!(s.valid, "activate: {} {}", s.status, s.message);
    let claims = s.claims.clone().unwrap();
    assert_eq!(claims.pid, "pos-desktop");
    assert_eq!(claims.bind.kind, "machine");
    assert_eq!(claims.bind.value.len(), 64);
    println!("activated: plan={} features={:?}", claims.plan, claims.features);

    // Offline: a client pointed at a dead server still trusts the stored, signed key
    let dead = LicenseClient::new(
        Config::new("http://127.0.0.1:9/api/v1", "pos-desktop", [("k1", pubkey.as_str())], tmp.path().join("license.json"))
            .machine_id_override(&machine).timeout(Duration::from_secs(2)),
    ).unwrap();
    let s = dead.check(false).await;
    assert!(s.valid && s.status != "grace", "offline before next_check: {}", s.status);
    // Forced re-check with the server down → grace
    let s = dead.check(true).await;
    assert!(s.valid && s.in_grace(), "grace: {} {}", s.status, s.message);

    // The license file is copied to another machine → refused
    let other = LicenseClient::new(
        Config::new(&api, "pos-desktop", [("k1", pubkey.as_str())], tmp.path().join("license.json"))
            .machine_id_override("some-other-pc").timeout(Duration::from_secs(5)),
    ).unwrap();
    let s = other.check(false).await;
    assert!(!s.valid, "copied file must not work on another machine");

    // Tampered local key → ignored, fresh one fetched
    let path = tmp.path().join("license.json");
    let mut stored: serde_json::Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    let lk = stored["local_key"].as_str().unwrap().to_string();
    let parts: Vec<&str> = lk.split('.').collect();
    let forged = format!("{}.{}.{}x.{}", parts[0], parts[1], parts[2], parts[3]);
    stored["local_key"] = serde_json::Value::String(forged);
    std::fs::write(&path, serde_json::to_vec(&stored).unwrap()).unwrap();
    let s = c.check(false).await;
    assert!(s.valid, "tampered key should be replaced by a fresh one: {}", s.message);

    // Wrong public key → every server answer is untrusted
    let wrong = LicenseClient::new(
        Config::new(&api, "pos-desktop", [("k1", "8S2HSauUDjX8c0JPH0GWbITaLdZC7jgIA8WZGePbmMQ=")], tmp.path().join("other.json"))
            .machine_id_override(&machine),
    ).unwrap();
    assert!(wrong.activate(&key).await.is_err(), "answers signed by another key must be rejected");

    // Updates
    if std::env::var("LH_HAS_UPDATE").is_ok() {
        let u = c.check_update("windows", "3.4.0").await.unwrap().expect("an update");
        println!("update: {} {}", u.version, u.url);
        let bytes = reqwest::get(&u.url).await.unwrap().bytes().await.unwrap();
        assert!(LicenseClient::verify_download(&bytes, u.sha256.as_deref().unwrap()));
        assert!(c.check_update("windows", &u.version).await.unwrap().is_none());
    }

    // Wrong product
    let wp = LicenseClient::new(
        Config::new(&api, "clinic-crm", [("k1", pubkey.as_str())], tmp.path().join("wp.json")).machine_id_override(&machine),
    ).unwrap();
    let s = wp.activate(&key).await.unwrap();
    assert_eq!(s.status, "invalid");

    // Deactivate frees the seat and forgets the key
    let s = c.deactivate().await.unwrap();
    assert_eq!(s.status, "deactivated");
    assert_eq!(c.check(false).await.status, "no_key");
}

fn rand_suffix() -> String {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos().to_string()
}
