# FairPing

Fabric mod for Minecraft 1.21.11 that artificially adds delay to your connection so your ping matches everyone else's on the server. Useful for practice servers where you want to simulate a specific ping without actually having it.

---

## Commands

`/fp` and `/fairping` both work.

| Command | What it does |
|---|---|
| `/fp add <ms>` | Adds that many ms on top of your real ping |
| `/fp total <ms>` | Tries to make your total ping equal to that value |
| `/fp off` | Turns it off |
| `/fp status` | Shows your base ping, how much was added, and the total |
| `/fp chat on/off` | Toggles whether commands announce in chat (on by default) |

`/fp add 0` and `/fp total 0` both turn it off.

---

## Installation

Drop the jar into your mods folder alongside [Fabric API](https://modrinth.com/mod/fabric-api). Requires Fabric loader.

---

## How it works

Injects a Netty channel handler into the MC connection pipeline and holds packets in a timed queue before dispatching them. Splits the delay evenly across inbound and outbound so ping measurements stay accurate. Ping is measured by timing the built-in `QueryPing` packet rather than anything external.

---

MIT License
