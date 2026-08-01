# 마인포탈 (MinePortal)

Web service that lets a logged-in user connect to real Minecraft servers and view/send
chat from a browser — no client install required for a quick look, with a desktop client
available for servers that need a direct, IP-stable connection. Live at
[mineportal.kr](https://mineportal.kr).

## Architecture

| Path | What it is |
| --- | --- |
| `server/` | Spring Boot backend. Holds account/server-list state, proxies chat over WebSocket, and connects to Minecraft servers via MCProtocolLib for "trial mode" (server-relayed) connections. |
| `client/` | The whole frontend — a single static `client/index.html` (no build step, no bundler). Served by the backend and deployed to `mineportal.kr`. |
| `desktop-client/` | Java desktop app. Connects directly from the user's own PC (bypassing the backend's relay), so it works reliably against IP-restricted/whitelisted servers without the "same IP, multiple connections" ban risk that trial mode carries. |
| `toss-client/` | [App in Toss](https://toss.im) mini-app wrapper (`@apps-in-toss/web-framework` + `ait` CLI) that embeds `mineportal.kr` in a native-feeling shell for the Toss platform. |
| `.github/workflows/` | `deploy.yml` auto-deploys `main` to production over SSH (docker compose rebuild) on every push. `toss-deploy.yml` is a manual-trigger workflow that builds and deploys the App in Toss `.ait` bundle. |

## Deploy

Pushing to `main` automatically deploys to `mineportal.kr` — `deploy.yml` SSHes into the
production host, resets to `origin/main`, and rebuilds the `docker compose` stack. There is
no staging environment, so changes to `client/` or `server/` go live as soon as they land on
`main`.

The App in Toss mini-app deploys separately and only on demand: run the **Deploy App in
Toss** workflow from the Actions tab (`toss-deploy.yml`), which needs the
`APPS_IN_TOSS_API_KEY` repo secret.

## Local setup

```bash
cp server/.env.example server/.env
cd server && ./gradlew bootRun
```

Open http://localhost:3000 — the backend serves `client/index.html` directly.

## Client (`client/index.html`)

Everything on the site — the marketing home page, the login/account flow, the server list,
and the multi-server chat grid — lives in this one file, translated between Korean and
English via a small in-page i18n dictionary (`data-i18n` attributes + a `lang`/`t()` helper).
There's no build step: edit the file, and what you see is what ships.

Home page sections, top to bottom:
- **Hero** — headline, download/trial CTAs, and an auto-cycling feature-tab row (a single
  fill bar spans the whole tab row and advances the active tab as it reaches each one) with
  a live-looking demo window mockup of the Play tab.
- **How it works** — three steps stacked on the left (active = white, others dimmed via
  opacity); a fake circular cursor moves to and "clicks" each step's action in the preview
  on the right (Microsoft login → add & connect to a server → send chat and use a command),
  advancing to the next step only once that action's mini-animation finishes.
- **Trust points** — a 4-card grid (account safety, real protocol, open source, IP-restricted
  server support).
- **FAQ** — a single-open accordion.

Play tab: the left sidebar (server discovery links + server list) can be collapsed to a thin
strip via a toggle button, independent of the manual drag-resize handle between sidebar and
chat panel.

Visual style is a "liquid glass" treatment applied consistently across the app — translucent
`backdrop-filter` surfaces with a soft inset top highlight — shared via the `--glass-blur` /
`--glass-highlight` CSS custom properties in `:root`.

## Login and connection modes

Trial mode (no desktop client) relays the connection through the backend, so it's available
instantly from the browser but can look like "multiple connections from the same IP" to a
server and risks a ban. Full mode requires the desktop client running locally and connects
directly from the user's own PC, avoiding that risk and working on IP-restricted servers.

## Chat signing

Chat is signed by default once logged in — the backend fetches an RSA key pair from Mojang's
player-certificates endpoint and signs every outgoing chat message with it
(`server/.../connection/McConnectionManager.java`), so servers with secure-chat enforcement
accept it instead of rejecting or kicking. The desktop client implements the same signing
logic independently for its own direct connections. Command arguments that the server's
Brigadier command tree marks as chat-like (`CommandParser.MESSAGE`) are signed the same way
as plain chat.
