import { Authflow, Titles } from "prismarine-auth";
import { EventEmitter } from "events";
import fs from "fs";
import path from "path";
import { fetchPlayerCertificate, PlayerCertificate } from "./protocol/mojangCertificates";

const CACHE_DIR = "./nmp-cache";
const LAST_EMAIL_FILE = path.join(CACHE_DIR, "last-email.txt");

export interface MsaCode {
  message: string;
  user_code: string;
  verification_uri: string;
}

export interface Profile {
  id: string; // 32-char hex, no dashes
  name: string;
}

export type AccountStatus = "logged-out" | "logging-in" | "logged-in" | "error";

interface AccountState {
  status: AccountStatus;
  email?: string;
  profile?: Profile;
  token?: string;
  certificate?: PlayerCertificate;
  msaCode: MsaCode | null;
  error?: string;
}

let state: AccountState = { status: "logged-out", msaCode: null };
const emitter = new EventEmitter();

function setState(patch: Partial<AccountState>) {
  state = { ...state, ...patch };
  emitter.emit("change");
}

export function onAccountEvent(listener: () => void) {
  emitter.on("change", listener);
  return () => emitter.off("change", listener);
}

/** Public view of account state — never includes the raw token or private signing key. */
export function getAccountState(): Omit<AccountState, "token" | "certificate"> {
  const { token, certificate, ...rest } = state;
  return rest;
}

export function getAccessToken(): { token: string; profile: Profile } | null {
  if (state.status === "logged-in" && state.token && state.profile) {
    return { token: state.token, profile: state.profile };
  }
  return null;
}

/**
 * Returns a valid (non-expired) chat-signing key pair, fetching or refreshing one from Mojang
 * if needed. Returns null if not logged in or the certificate fetch fails — callers should
 * treat that as "fall back to unsigned chat," not a hard error.
 */
export async function getSigningCertificate(): Promise<PlayerCertificate | null> {
  if (state.status !== "logged-in" || !state.token) return null;
  const needsRefresh = !state.certificate || state.certificate.expiresAt < Date.now() + 60_000;
  if (needsRefresh) {
    try {
      const certificate = await fetchPlayerCertificate(state.token);
      setState({ certificate });
    } catch (err: any) {
      console.warn("Failed to fetch chat-signing certificate, falling back to unsigned chat:", err.message);
      return null;
    }
  }
  return state.certificate ?? null;
}

/**
 * Starts (or restarts, for a different email) the Microsoft device-code login flow. Login
 * happens once per account, independent of any particular server — connectServer() reuses
 * the resulting token for as many servers as you like.
 */
export function startLogin(email: string) {
  if (state.status === "logging-in") return;
  setState({ status: "logging-in", email, msaCode: null, error: undefined });

  // "live" flow + the Nintendo Switch title ID is the combo mineflayer/minecraft-protocol use
  // by default: it doesn't require registering your own Azure app (unlike flow: "msal", which
  // needs an authTitle that's actually your own Azure client ID).
  const flow = new Authflow(
    email,
    "./nmp-cache",
    { authTitle: Titles.MinecraftNintendoSwitch, deviceType: "Nintendo", flow: "live" },
    (code) => setState({ msaCode: code })
  );

  flow
    .getMinecraftJavaToken({ fetchProfile: true })
    .then(({ token, profile }) => {
      setState({ status: "logged-in", token, profile: profile as Profile, msaCode: null });
      rememberEmail(email);
    })
    .catch((err) => {
      setState({ status: "error", error: err.message, msaCode: null });
    });
}

export function logout() {
  setState({
    status: "logged-out",
    email: undefined,
    profile: undefined,
    token: undefined,
    certificate: undefined,
    msaCode: null,
  });
  fs.rmSync(LAST_EMAIL_FILE, { force: true });
}

function rememberEmail(email: string) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  fs.writeFileSync(LAST_EMAIL_FILE, email, "utf8");
}

/**
 * Called once at server startup. If we logged in before, prismarine-auth still has a cached
 * refresh token on disk under nmp-cache/ — reusing it here means the account survives a dev
 * server restart (or any process restart) without the device-code prompt reappearing, as long
 * as the underlying Microsoft refresh token (~90 days) hasn't expired.
 */
export function tryResumeSession() {
  if (!fs.existsSync(LAST_EMAIL_FILE)) return;
  const email = fs.readFileSync(LAST_EMAIL_FILE, "utf8").trim();
  if (email) startLogin(email);
}
