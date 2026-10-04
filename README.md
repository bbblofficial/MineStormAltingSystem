# MineStormAltingSystem

**Sensitivity-based alt detection** for Spigot / Paper, powered by **GrimAC**.
Multi-platform: backend plugin + optional **BungeeCord** / **Velocity** proxy relay.

> Created by **Muvixo**

## Architecture

```
                    ┌───────────────────────────┐
                    │   BungeeCord / Velocity   │
                    │   MineStormAltingSystem   │  ← stateless relay
                    │   (relays raw packets)    │
                    └──────────┬────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
  ┌─────▼─────┐          ┌─────▼─────┐          ┌─────▼─────┐
  │ backend A │          │ backend B │          │ backend C │
  │ GrimAC +  │          │ GrimAC +  │          │ GrimAC +  │
  │ plugin    │          │ plugin    │          │ plugin    │
  │ MySQL ────┴──────────┴───────────┴──────────┴──► shared  │
  └───────────┘          └───────────┘          └───────────┘
```

- Each backend reads/writes sensitivities to a **shared MySQL** database.
- Every `QUERY` / `ALERT` / `QUERY_RESP` packet travels through the proxy relay.
- A client can never forge a packet: all messages are **HMAC-SHA256 signed** with a shared secret.

## Install

### 1. Backend servers (Spigot/Paper) — every one of them
- Put **GrimAC** in `plugins/`
- Put **`MineStormAltingSystem-Bukkit-1.0.0.jar`** in `plugins/`
- Edit `plugins/MineStormAltingSystem/config.yml`:
  ```yaml
  database:
    type: mysql
    host: 127.0.0.1
    database: minestormalt
    username: root
    password: YOUR_PASSWORD
  network:
    proxy: true
    server-name: lobby          # unique per backend
    secret: "a long random string, identical everywhere"
  ```

### 2. Proxy (optional, only for multi-server networks)

**BungeeCord:**
- Put **`MineStormAltingSystem-Bungee-1.0.0.jar`** in `plugins/`

**Velocity:**
- Put **`MineStormAltingSystem-Velocity-1.0.0.jar`** in `plugins/`

Only ONE of them depending on which proxy you run.

### 3. Single server without a proxy
- `network.proxy: false` in config.yml
- Skip the proxy jar entirely.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/alt <player>` | `minestorm.alt` | Show a player's sensitivity and every account sharing it |
| `/altreport` | `minestorm.altreport` | List all sensitivity groups |
| `/alttoggle` | `minestorm.togglealerts` | Toggle your own alerts |

Staff with `minestorm.alerts` receive alerts on join, both locally and across servers.

## How `/alt` answers

1. **Player on this server** → live read from GrimAC, alt list from shared DB
2. **Player on another server** → shared DB (already populated)
3. **Player offline** → shared DB
4. **No data anywhere + proxy up** → sends a `QUERY` packet, the hosting server replies

## The database creates itself

The plugin creates the database, the `sensitivities` table, and the indexes on first start. In HeidiSQL / phpMyAdmin do **not** create tables manually — just fill in the four values in `config.yml` and start the server.

## Build

```bash
mvn clean package
```

Artifacts:
- `bukkit/target/MineStormAltingSystem-Bukkit-1.0.0.jar`
- `bungee/target/MineStormAltingSystem-Bungee-1.0.0.jar`
- `velocity/target/MineStormAltingSystem-Velocity-1.0.0.jar`

## Creator

```
/alt creator
```
