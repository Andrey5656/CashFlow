# CashFlow

A Vault economy for Paper and Folia. Balances are kept in memory, so plugins that use Vault get an answer right away
and never wait on the database. Every change is saved in the background and written to a log, so players can see
their history and admins can see which plugin moved money and roll a balance back.

Players pay each other with `/pay`, check their money with `/balance` and see the richest players with `/baltop`.
The payer and the receiver both see the payment in chat and on the action bar and hear a sound. Several servers can
share the same balances through MySQL and Redis.

## Features

- Works as the Vault economy, so shops, jobs, auction houses and every other Vault plugin use it
- Balances kept in memory: Vault calls never wait on the database
- Safe on Folia: two payments to or from the same player at the same moment can't create or lose money
- Changes saved in the background within half a second, and everything saved when the server stops
- `/pay` to online and offline players, with amounts like `500`, `2.5k`, `1m` and `1b`, a minimum amount and a
  cooldown
- The payer and the receiver both get a chat line, an action bar line and a sound
- Players who were offline see who paid them when they join
- `/paytoggle` stops receiving payments; the player can still pay others
- `/paymsgtoggle` hides payment messages and the sound; the money still arrives
- `/baltop` in chat with clickable page arrows and your own rank; staff can be left out with a permission
- `/history` shows a player's own transactions
- `/eco give`, `take`, `set` and `reset`, with `-s` to do it without telling the player
- `/eco history` shows a player's full log, including which plugin added or took money
- `/eco rollback` puts a balance back to what it was a set time ago
- Short amounts (`1.5K`, `2.5M`) or full amounts (`1,500.50`), 0 to 2 decimals, any currency symbol
- Every message in `messages.yml` with MiniMessage colors, and every sound can be changed
- PlaceholderAPI placeholders for balances, ranks and the top list
- Vault banks and name-based accounts, which plugins like Towny use
- SQLite or MySQL/MariaDB
- Network sync: one balance on several servers with MySQL and Redis
- Folia support
- `/eco reload` to reload the config and messages without a restart

## Requirements

- Paper 1.21.7 or newer (tested on 1.21.7)
- Folia 26.x (tested on Canvas 26.3, a Folia fork). Use a Vault build that supports Folia.
- Java 25 or newer on 26.x servers, Java 21 or newer on 1.21.x servers
- [Vault](https://www.spigotmc.org/resources/vault.34315/)
- [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) for placeholders (optional)
- MySQL or MariaDB, and Redis for network sync (optional; tested on MySQL 8.4, MariaDB 11.4 and Redis 3.0)

## Installation

1. Put Vault and `CashFlow-<version>.jar` in your `plugins` folder.
2. Start the server. The plugin downloads its libraries (Kotlin, HikariCP, SQLite and MySQL drivers, Jedis) on first
   start.
3. Edit `plugins/CashFlow/config.yml` and `plugins/CashFlow/messages.yml`, then run `/eco reload`.

Use CashFlow as your only economy plugin. It does not copy balances from other economy plugins.

## Commands and permissions

| Command | Permission | Description |
|---|---|---|
| `/balance [player]` | `cashflow.balance`, and `cashflow.balance.others` for another player | Shows a balance. Aliases: `/bal`, `/money` |
| `/pay <player> <amount>` | `cashflow.pay` | Pays a player, online or offline |
| `/paytoggle` | `cashflow.paytoggle` | Turns receiving payments off, or back on |
| `/paymsgtoggle` | `cashflow.paymsgtoggle` | Hides payment messages and the sound, or shows them again |
| `/baltop [page]` | `cashflow.baltop` | Shows the top balances |
| `/history [page]` | `cashflow.history` | Shows your own transactions |
| `/eco give <player> <amount> [-s]` | `cashflow.admin` (op) | Adds money. Alias: `/economy` |
| `/eco take <player> <amount> [-s]` | `cashflow.admin` (op) | Takes money. Fails if the player has less |
| `/eco set <player> <amount> [-s]` | `cashflow.admin` (op) | Sets a balance |
| `/eco reset <player> [-s]` | `cashflow.admin` (op) | Sets a balance to `starting-balance` |
| `/eco history <player> [page]` | `cashflow.admin` (op) | Shows a player's full log |
| `/eco rollback <player> <time>` | `cashflow.admin` (op) | Puts a balance back to what it was `<time>` ago, like `30m`, `2h`, `3d` or `1d12h` |
| `/eco reload` | `cashflow.admin` (op) | Reloads `config.yml` and `messages.yml` |
| | `cashflow.baltop.hidden` | Leaves the player out of `/baltop`. Nobody has it by default, not even ops |

Every player has the permissions that aren't marked op. `-s` stops the message the player would get from `/eco`.
Offline players can be paid if they joined the server since CashFlow was installed. Time in `/eco rollback` uses
`s`, `m`, `h`, `d` and `w`, and a rollback shows up in the log too.

## Configuration

```yaml
storage:
  type: sqlite
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: root
    password: ""

currency:
  singular: Dollar
  plural: Dollars
  decimals: 2
  format: short
  money: "<#00FF00>$</#00FF00> <amount>"

starting-balance: 0
max-balance: 0

pay:
  minimum: 1
  cooldown: 1.0

log-days: 30

sounds:
  pay-sent:
    sound: entity.experience_orb.pickup
    volume: 1.0
    pitch: 1.0
  pay-received:
    sound: entity.experience_orb.pickup
    volume: 1.0
    pitch: 1.0
  error:
    sound: entity.villager.no
    volume: 1.0
    pitch: 1.0

network:
  enabled: false
  redis:
    host: localhost
    port: 6379
    password: ""
```

| Key | Description |
|---|---|
| `storage.type` | `sqlite` (saved in `plugins/CashFlow/cashflow.db`) or `mysql` (MySQL and MariaDB) |
| `currency.singular` / `currency.plural` | The currency name other plugins can show |
| `currency.decimals` | `0`, `1` or `2`. How many decimals players can type and see |
| `currency.format` | `short` (`1.5K`, `2.5M`) or `full` (`1,500.50`) |
| `currency.money` | How every amount looks. `<amount>` is the number. Plugins that use Vault get the same text without colors |
| `starting-balance` | The money a new player starts with |
| `max-balance` | The most money one player can have. `0` means no limit |
| `pay.minimum` | The smallest amount players can pay |
| `pay.cooldown` | Seconds between two payments from the same player. `0` turns it off |
| `log-days` | How many days the log is kept. `/history`, `/eco history` and `/eco rollback` only reach this far back |
| `sounds.pay-sent` | Plays for the payer |
| `sounds.pay-received` | Plays for the receiver |
| `sounds.error` | Plays with every red message, like "You don't have enough money." |
| `network` | See Network sync below |

Sounds take any sound key, including resource pack sounds, and an empty `sound` turns that sound off. Short amounts
are never rounded up: 999,999 shows as `999.9K`. Changing `storage` or `network` needs a restart; everything else
applies with `/eco reload`.

## Messages

```yaml
pay-sent: "You paid <player> <money>"
pay-sent-action-bar: "You paid <player> <money>"
pay-received: "<player> paid you <money>"
pay-received-action-bar: "<player> paid you <money>"
pay-offline: "<player> paid you <money> while you were away"

balance: "You have <money>"
balance-other: "<player> has <money>"

paytoggle-off: "You no longer receive payments."
paytoggle-on: "You receive payments again."
paymsgtoggle-off: "Payment messages are now hidden."
paymsgtoggle-on: "Payment messages are now shown."

baltop-header: "Top balances <gray>(<page>/<pages>)"
baltop-line: "<gray><rank>.</gray> <player> <money>"
baltop-self: "Your rank: <gray>#<rank></gray> <money>"
baltop-empty: "Nobody is on the top list yet."
baltop-page: "<previous> <gray>Page <page>/<pages></gray> <next>"
baltop-previous: "<#00FF00>«"
baltop-next: "<#00FF00>»"

history-header: "Your history <gray>(<page>/<pages>)"
history-other-header: "History of <player> <gray>(<page>/<pages>)"
history-empty: "No transactions yet."
history-line: "<gray><time> ago ·</gray> <entry>"
history:
  pay-sent: "You paid <other> <money>"
  pay-received: "<other> paid you <money>"
  admin-give: "<other> gave you <money>"
  admin-take: "<other> took <money>"
  admin-set: "<other> set your balance to <money>"
  plugin-deposit: "<other> gave you <money>"
  plugin-withdraw: "<other> took <money>"
  rollback: "<other> rolled your balance back to <money>"
history-other:
  pay-sent: "<player> paid <other> <money>"
  pay-received: "<other> paid <player> <money>"
  admin-give: "<other> gave <player> <money>"
  admin-take: "<other> took <money> from <player>"
  admin-set: "<other> set <player>'s balance to <money>"
  plugin-deposit: "<other> gave <player> <money>"
  plugin-withdraw: "<other> took <money> from <player>"
  rollback: "<other> rolled <player> back to <money>"

eco-give: "Gave <player> <money>"
eco-take: "Took <money> from <player>"
eco-set: "Set <player>'s balance to <money>"
eco-reset: "Reset <player>'s balance to <money>"
eco-received: "You received <money>"
eco-taken: "<money> was taken from you"
eco-set-target: "Your balance was set to <money>"
rollback: "Rolled <player> back to <money>"
reload: "CashFlow reloaded."

usage-balance: '<red>Usage: /balance \<player>'
usage-pay: '<red>Usage: /pay \<player> \<amount>'
usage-eco: '<red>Usage: /eco \<give|take|set> \<player> \<amount> [-s], /eco reset \<player> [-s], /eco history \<player> [page], /eco rollback \<player> \<time>, /eco reload'
not-found: "<red>Player not found."
pay-self: "<red>You can't pay yourself."
pay-off: "<red><player> has payments turned off."
no-money: "<red>You don't have enough money."
invalid-amount: "<red>Invalid amount."
pay-minimum: "<red>The minimum is <money>."
pay-cooldown: "<red>Wait <seconds>s before paying again."
max-balance: "<red><player> can't hold that much money."
eco-not-enough: "<red><player> only has <money>."
rollback-none: "<red><player> has no changes in that time."
invalid-time: "<red>Invalid time. Use 30m, 2h or 3d."
no-permission: "<red>You don't have permission."
players-only: "<red>Only players can use this."
```

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html) colors. Text with no color is white.

| Tag | Description |
|---|---|
| `<player>` | A player's name |
| `<money>` | An amount, drawn with `currency.money` |
| `<page>` / `<pages>` | The page you are on and how many there are |
| `<rank>` | A place on the top list |
| `<seconds>` | Seconds left on the pay cooldown |
| `<time>` | How long ago a change happened, like `5m` or `2h` |
| `<other>` | In history lines: the other player, the admin (`Console` from the console) or the plugin that made the change |
| `<entry>` | In `history-line`: the line from `history` or `history-other` |
| `<previous>` / `<next>` | In `baltop-page`: the `baltop-previous` and `baltop-next` arrows. Clicking one opens that page |

`history` holds the lines players see in `/history`, and `history-other` the lines admins see in `/eco history`.
An empty message is not sent, so an empty `pay-sent-action-bar` turns off the payer's action bar. Names are always
shown as plain text, so a name can never add colors.

## Placeholders

| Placeholder | Example |
|---|---|
| `%cashflow_balance%` | `1500.5` |
| `%cashflow_balance_formatted%` | `1.5K` |
| `%cashflow_balance_commas%` | `1,500.50` |
| `%cashflow_rank%` | `12`, or `-` when the player is not on the top list |
| `%cashflow_paytoggle%` | `on` when the player receives payments, `off` when not |
| `%cashflow_top_<n>_name%` | The name at place `<n>` on the top list |
| `%cashflow_top_<n>_balance%` | Their balance, like `1500.5` |
| `%cashflow_top_<n>_balance_formatted%` | Their balance, like `1.5K` |
| `%cashflow_top_<n>_balance_commas%` | Their balance, like `1,500.50` |

The top list is rebuilt every 30 seconds.

## Vault

- Every Vault method works, including the old name-based ones. A name that isn't a player gets its own account,
  which is what plugins like Towny use for town banks.
- Vault banks work: plugins can create, delete, fill and empty them. Vault has no way to add bank members, so only
  the owner counts as a member.
- Every change a plugin makes is logged with that plugin's name, so `/eco history` shows lines like
  `Shop took $ 500 from Steve`.

## Network sync

To share balances between servers:

1. Set `storage.type: mysql` on every server, with the same database.
2. Set `network.enabled: true` and the Redis address on every server.
3. Restart the servers.

Changes go through Redis right away, so balances, toggles and new players show up on every server. A payment to a
player on another server shows them the message there. If Redis goes down, each server keeps working on its own,
logs one warning, and catches up from MySQL when Redis is back. With SQLite, network sync stays off.

One limit: if two servers take money from the same player within the same few milliseconds, both can go through and
the balance can end slightly below zero. Both changes are in the log.

## Building

Requires JDK 25 or newer and Maven.

```
mvn package
```

The jar is written to `target/`.

## Support

Ask on my Discord: https://discord.gg/nRjDw9k2vJ

## License

[MIT](LICENSE)
