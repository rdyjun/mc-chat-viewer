import { NbtValue } from "./nbt";

/**
 * English templates for the translation keys that dominate ordinary server chat feeds (join/
 * leave/death broadcasts, the default chat line format). Not a full lang file — anything not
 * listed here falls back to showing the raw key, e.g. `[some.other.key arg1 arg2]`.
 */
const KNOWN_TRANSLATIONS: Record<string, string> = {
  "multiplayer.player.joined": "%s joined the game",
  "multiplayer.player.left": "%s left the game",
  "chat.type.text": "<%s> %s",
  "chat.type.announcement": "[%s] %s",
  "chat.disabled.missingProfileKey": "Chat message rejected: this server requires signed chat, and we send unsigned messages (see README's Known Caveats)",
};

/** Best-effort flattening of a Text Component (NBT or parsed JSON) into plain display text. */
export function textComponentToPlainText(value: NbtValue): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return value.map(textComponentToPlainText).join("");

  let result = "";
  if (typeof value.text === "string") {
    result += value.text;
  } else if (typeof value.translate === "string") {
    const args = Array.isArray(value.with) ? value.with.map(textComponentToPlainText) : [];
    const template = KNOWN_TRANSLATIONS[value.translate];
    if (template) {
      let i = 0;
      result += template.replace(/%s/g, () => args[i++] ?? "");
    } else {
      result += `[${value.translate}${args.length ? " " + args.join(" ") : ""}]`;
    }
  }
  if (Array.isArray(value.extra)) {
    result += value.extra.map(textComponentToPlainText).join("");
  }
  return result;
}
