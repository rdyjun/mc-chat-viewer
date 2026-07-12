/**
 * minecraft-data (used by the old mineflayer-based client) doesn't have entries for very new
 * calendar-versioned releases yet, so there's no library to resolve "26.1.2" -> protocol number
 * 775 for us. Known mappings go here; anything else must be passed as a bare protocol number.
 */
const KNOWN_VERSIONS: Record<string, number> = {
  "26.1.2": 775,
  "26.2": 776,
};

export function resolveProtocolVersion(input: string): number {
  const trimmed = input.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  if (trimmed in KNOWN_VERSIONS) return KNOWN_VERSIONS[trimmed];
  throw new Error(
    `Unknown Minecraft version "${trimmed}" — pass its protocol number directly (see minecraft.wiki/w/Protocol_version), or add it to protocolVersions.ts`
  );
}
