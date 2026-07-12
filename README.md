# mc-chat-viewer

Connects to any Minecraft server (added at runtime from the dashboard) using your own
Microsoft account and mirrors chat to a live web dashboard.

Login and server connections are separate steps: log in with your Microsoft account once,
then add as many servers as you like and click Connect on each — connecting reuses the
already-logged-in account. Clicking Connect before logging in returns a clear "log in
first" error instead of silently failing.

## Why a hand-rolled protocol client

`mineflayer`/`minecraft-protocol` bundle version data (`minecraft-data`) that lags behind
brand-new, calendar-versioned server releases — as of writing they don't support Minecraft
26.1.x yet, so connecting to a server on that version fails with "unsupported protocol
version" no matter how new the npm packages are. `src/protocol/` implements just enough of
the wire protocol by hand (packet framing, encryption, compression, login, chat) to work
around that gap; see the comment at the top of `src/protocol/rawClient.ts` for the design
rationale (in short: unknown packets are always safely skippable because the outer
length-prefix framing tells us their size regardless of whether we understand their
contents).

Auth is unaffected by any of this — `prismarine-auth`'s Microsoft device-code flow is used
directly (no need to reimplement OAuth).

`src/protocol/smoke-test.ts` (`npm run test`) checks the deterministic parts (VarInt
encoding, packet field roundtrips, the Minecraft server-hash algorithm against wiki.vg's
published test vectors, NBT text-component decoding) without needing a live server. The
parts that can only be verified against a real server (exact Configuration-state packet
IDs, the Player Chat Message field layout) are best-effort — see the caveats below.

## How auth works

No custom OAuth server needed — `prismarine-auth` handles the Microsoft device-code login
flow (via `src/account.ts`, using the same client ID mineflayer defaults to, which doesn't
require registering your own Azure app). Instead of printing the login URL/code to the
server console, it's forwarded to the dashboard (`onMsaCode` -> WebSocket) so it shows up
in the Account panel. Open the URL, enter the code, approve with the Microsoft account
that owns the Minecraft account. Tokens are cached locally afterward (gitignored, under
`nmp-cache/`) so logging in again later (same email) skips the prompt. Only one account is
logged in at a time — logging in with a different email replaces it.

## Setup

```bash
npm install
cp .env.example .env   # WEB_PORT only, defaults to 3000
npm run dev
```

Open http://localhost:3000:

1. **Account panel** — enter your Microsoft account email, click 로그인. A login code
   appears; approve it in a browser.
2. **서버 추가** — add a server by host/port and version (or bare protocol number — auto
   version-detection isn't available without minecraft-data, see above).
3. Click **연결** on a server in the list. If you haven't logged in yet, you'll get a
   "log in first" prompt instead of a silent failure. Click a server's name to view its
   chat.

## Known caveats (unverified against a live server)

- **Configuration-state packet IDs** (Finish Configuration, Known Packs, Ping) were pulled
  from the Minecraft Wiki's "current" docs (protocol 776 / MC 26.2), one release ahead of
  what most 26.1.x servers run (protocol 775). IDs are usually stable release-to-release
  but could be off by one for this specific version.
- **Player Chat Message** (regular player-to-player chat) field layout is filled in from
  general protocol knowledge, not a confirmed source for this version — if the layout is
  wrong you'll see a `[플레이어 채팅 메시지 — 파싱 실패]` placeholder instead of a crash
  (parsing failures are caught and can never desync the packet stream), but the message
  text itself will be lost for that entry. System/disguised chat messages use a simpler,
  more confidently-implemented format.
- If something doesn't work, the dashboard status line and server console log will show
  which packet ID/state failed — that's the starting point for adjusting the IDs in
  `src/protocol/rawClient.ts`.

## Notes

- Each connected server joins as a real player and occupies a server slot — it shows up in
  the player list like any other connection. There's no way to observe chat invisibly.
- Read-only: nothing is sent back to the server (no chat, no movement).
- Account and server state live in memory only; restarting the process drops them (log in
  and reconnect from the dashboard again — added servers aren't persisted to disk either).
