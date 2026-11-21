//! Try the SDK from a terminal:
//!   cargo run --example cli -- <API_URL> <PUBLIC_KEY> <PRODUCT> activate <KEY>
//!   cargo run --example cli -- <API_URL> <PUBLIC_KEY> <PRODUCT> check
//!   cargo run --example cli -- <API_URL> <PUBLIC_KEY> <PRODUCT> update <PLATFORM> <CURRENT_VERSION>
//!   cargo run --example cli -- <API_URL> <PUBLIC_KEY> <PRODUCT> deactivate
use bizfunnel_license::{Config, LicenseClient};

#[tokio::main]
async fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 5 {
        eprintln!("usage: cli <API_URL> <PUBLIC_KEY> <PRODUCT> activate <KEY> | check | update <PLATFORM> <VERSION> | deactivate");
        std::process::exit(2);
    }
    let client = LicenseClient::new(Config::new(&a[1], &a[3], [("k1", a[2].as_str())], "license-cli.json")).expect("config");
    println!("machine id: {}", client.machine_id().unwrap_or_default());
    match a[4].as_str() {
        "activate" => println!("{:#?}", client.activate(&a[5]).await),
        "check" => println!("{:#?}", client.check(false).await),
        "update" => println!("{:#?}", client.check_update(&a[5], &a[6]).await),
        "deactivate" => println!("{:#?}", client.deactivate().await),
        _ => eprintln!("unknown command"),
    }
}
