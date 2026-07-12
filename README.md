# mc-chat-viewer

Connects to a Minecraft server using your own Microsoft account and mirrors chat to a
live web dashboard.

## How auth works

No custom OAuth server needed — `mineflayer`/`prismarine-auth` handles the Microsoft
device-code login flow for you. On first run, the console prints a URL and a one-time
code; open the URL in any browser, enter the code, and approve with the Microsoft
account that owns the Minecraft account. Tokens are cached locally afterward
(gitignored) so future runs reconnect without prompting again.

## Setup

```bash
npm install
cp .env.example .env   # fill in MC_SERVER_HOST, MC_ACCOUNT_EMAIL (and port/version if needed)
npm run dev
```

Open http://localhost:3000 to watch chat live.

## Notes

- The bot joins as a real player and occupies a server slot — it will show up in the
  player list like any other connection. There's no way to observe chat invisibly.
- Only sends nothing back to the server; it's read-only (no chat, no movement).
