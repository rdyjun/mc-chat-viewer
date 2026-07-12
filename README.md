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
published test vectors, NBT text-component decoding, the serverbound Chat packet's field
layout) without needing a live server. The full flow — handshake through encryption,
compression, login, configuration, and play — has been verified against a real 26.1.2
server (see caveats below for what's still best-effort).

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

## Chat signing

Chat is signed by default once logged in: `src/account.ts` fetches an RSA key pair from
Mojang's `/player/certificates` endpoint, `RawMcClient` registers it with the server via
the Player Session packet on entering play, and every `sendChat()` call signs the message
with it (`src/protocol/chatSigning.ts`). If certificate fetch fails for any reason, chat
falls back to unsigned automatically — servers with secure-chat enforcement will then
reject it with a clean `chat.disabled.missingProfileKey` message instead of sending it (no
crash, no kick). Verified end-to-end against a live server: sign, send, get broadcast back,
and correctly re-parsed.

Two non-obvious bugs surfaced while wiring this up, in case they resurface for a different
server version:
- Mojang's returned public key PEM is labeled `RSA PUBLIC KEY` (implying PKCS#1) but its
  DER content is actually X.509 SubjectPublicKeyInfo. Node's `createPublicKey` cross-checks
  the PEM header against the requested `type` and rejects the mismatch regardless of
  whether the DER itself is valid — worked around by decoding the PEM body ourselves
  instead of asking Node to parse the whole PEM (`pemBodyToDer` in `mojangCertificates.ts`).
- The private key PEM needs `type: "pkcs1"` passed explicitly; header-only auto-detection
  isn't reliable in Node.

## Known caveats

- The exact byte layout `chatSigning.ts` signs over (mirroring vanilla's
  SignedMessageBody format) couldn't be confirmed against a spec for this exact server
  version — it's inferred from general protocol knowledge. It's evidently correct (the live
  server accepted a signed message), but if a different server rejects signed chat as
  invalid, this is the place to double-check.
- Client Information (locale/render-distance/etc.) is deliberately never sent — a live
  server rejected our attempt with a decoder exception, its exact layout having changed
  since whatever version's docs were available, and none of that data matters for a
  read-only-by-default chat client anyway. Servers use defaults when it's never sent.
- If something doesn't work, the dashboard's "연결 로그" panel (or the server console log)
  will show which packet/state failed — that's the starting point for fixing the relevant
  packet in `src/protocol/rawClient.ts`. Every protocol bug found so far (the handshake
  state-machine bug, the missing Chat Checksum byte, the missing Player Chat Message Global
  Index field, the PEM parsing issues above) was found and fixed exactly this way, against
  a live server.

## Notes

- Each connected server joins as a real player and occupies a server slot — it shows up in
  the player list like any other connection. There's no way to observe chat invisibly.
- Chat sending is supported (signed, falling back to unsigned — see above) via the input
  under each server's message log; nothing else is sent back to the server (no movement, no
  commands beyond plain chat).
- Account and server state live in memory only; restarting the process drops them (log in
  and reconnect from the dashboard again — added servers aren't persisted to disk either).
