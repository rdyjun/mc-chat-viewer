# mc-chat-viewer

Connects to any Minecraft server (chosen at runtime from the dashboard) using your own
Microsoft account and mirrors chat to a live web dashboard. Multiple sessions (different
servers/accounts) can run side by side.

## How auth works

No custom OAuth server needed — `mineflayer`/`prismarine-auth` handles the Microsoft
device-code login flow for you. Instead of printing the login URL/code to the server
console, it's forwarded to the dashboard (`onMsaCode` -> WebSocket) so it shows up next
to the session you just started. Open the URL, enter the code, approve with the
Microsoft account that owns the Minecraft account. Tokens are cached locally afterward
(gitignored, under `nmp-cache/`) so reconnecting the same account later skips the prompt.

## Setup

```bash
npm install
cp .env.example .env   # WEB_PORT only, defaults to 3000
npm run dev
```

Open http://localhost:3000, fill in the server host/port and your Microsoft account
email, and click Connect. If it's a new account/server pair you'll see a login code —
approve it in a browser, then chat starts streaming in.

## Notes

- Each session joins as a real player and occupies a server slot — it shows up in the
  player list like any other connection. There's no way to observe chat invisibly.
- Read-only: nothing is sent back to the server (no chat, no movement).
- Sessions live in memory only; restarting the process drops them (reconnect from the
  dashboard again).
