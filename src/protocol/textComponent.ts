import { NbtValue } from "./nbt";

/** Best-effort flattening of a Text Component (NBT or parsed JSON) into plain display text. */
export function textComponentToPlainText(value: NbtValue): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return value.map(textComponentToPlainText).join("");

  let result = "";
  if (typeof value.text === "string") {
    result += value.text;
  } else if (typeof value.translate === "string") {
    const withArgs = Array.isArray(value.with)
      ? " " + value.with.map(textComponentToPlainText).join(" ")
      : "";
    result += `[${value.translate}${withArgs}]`;
  }
  if (Array.isArray(value.extra)) {
    result += value.extra.map(textComponentToPlainText).join("");
  }
  return result;
}
