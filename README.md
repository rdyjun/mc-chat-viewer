# mc-chat-viewer

Connects to any Minecraft server (chosen at runtime from the dashboard) using your own
Microsoft account and mirrors chat to a live web dashboard. Multiple sessions (different
servers/accounts) can run side by side.

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
flow. Instead of printing the login URL/code to the server console, it's forwarded to the
dashboard (`onMsaCode` -> WebSocket) so it shows up next to the session you just started.
Open the URL, enter the code, approve with the Microsoft account that owns the Minecraft
account. Tokens are cached locally afterward (gitignored, under `nmp-cache/`) so
reconnecting the same account later skips the prompt.

## Setup

```bash
npm install
cp .env.example .env   # WEB_PORT only, defaults to 3000
npm run dev
```

Open http://localhost:3000, fill in the server host/port, the Minecraft version (or bare
protocol number — auto-detection isn't available without minecraft-data, see above), and
your Microsoft account email, then click Connect. If it's a new account/server pair you'll
see a login code — approve it in a browser, then chat starts streaming in.

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

- Each session joins as a real player and occupies a server slot — it shows up in the
  player list like any other connection. There's no way to observe chat invisibly.
- Read-only: nothing is sent back to the server (no chat, no movement).
- Sessions live in memory only; restarting the process drops them (reconnect from the
  dashboard again).
