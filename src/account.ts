import { Authflow } from "prismarine-auth";
import { ConfidentialClientApplication, Configuration } from "@azure/msal-node";
import { EventEmitter } from "events";
import fs from "fs";
import path from "path";
import crypto from "crypto";
import { fetchPlayerCertificate, PlayerCertificate } from "./protocol/mojangCertificates";

const CACHE_DIR = "./nmp-cache";
const RESUME_MARKER_FILE = path.join(CACHE_DIR, "logged-in.marker");

// Fixed cache-key placeholder: this app only ever has one Microsoft account logged in at a
// time (see servers.ts's DB-backed ownership model for the actual multi-user scoping), so
// there's no real "username" to key the token cache by anymore now that login has no email step.
const WEB_USERNAME = "web";

const MS_CLIENT_ID = process.env.MS_CLIENT_ID ?? "";
const MS_CLIENT_SECRET = process.env.MS_CLIENT_SECRET ?? "";
const MS_REDIRECT_URI = process.env.MS_REDIRECT_URI ?? "";
const LOGIN_SCOPES = ["XboxLive.signin", "offline_access"];

export interface Profile {
  id: string; // 32-char hex, no dashes
  name: string;
}

export type AccountStatus = "logged-out" | "logging-in" | "logged-in" | "error";

interface AccountState {
  status: AccountStatus;
  profile?: Profile;
  token?: string;
  certificate?: PlayerCertificate;
  error?: string;
}

let state: AccountState = { status: "logged-out" };
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

// Mirrors prismarine-auth's own cache-file naming (sha1(username).hex.slice(0, 6) + "_msal-cache.json")
// so that a token we acquire here shows up in exactly the cache file its Authflow(..., { flow: "msal" })
// instance reads from — letting us hand off to prismarine-auth's Xbox Live / XSTS / Minecraft chain
// without reimplementing it ourselves.
function cacheHash(input: string): string {
  return crypto.createHash("sha1").update(input, "binary").digest("hex").substring(0, 6);
}

function msalCacheFile(): string {
  return path.join(CACHE_DIR, `${cacheHash(WEB_USERNAME)}_msal-cache.json`);
}

function readMsalCache(): string {
  try {
    return fs.readFileSync(msalCacheFile(), "utf8");
  } catch {
    return "{}";
  }
}

function writeMsalCache(serialized: string) {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  fs.writeFileSync(msalCacheFile(), serialized, "utf8");
}

/**
 * MSAL/prismarine-auth errors surface as raw English exception messages (often an internal
 * MSAL error code plus the underlying AADSTS code, e.g. "post_request_failed: ... invalid_grant").
 * Translate the ones we actually see in practice into a plain-Korean explanation so the
 * dashboard doesn't show an untranslated wall of English to the user.
 */
function translateAuthError(message: string): string {
  const m = message || "";
  if (/invalid_grant/i.test(m) || /AADSTS70008/.test(m)) {
    return "로그인 세션이 만료됐거나 이미 사용된 인증 코드예요. 다시 로그인해주세요.";
  }
  if (/invalid_client/i.test(m) || /AADSTS7000215|AADSTS700016/.test(m)) {
    return "서버의 Microsoft 로그인 설정(클라이언트 ID/시크릿)이 올바르지 않아요. 관리자에게 문의해주세요.";
  }
  if (/(consent_required|interaction_required|AADSTS65001)/i.test(m)) {
    return "Microsoft 계정 동의가 필요해요. 로그인을 다시 진행해주세요.";
  }
  if (/(ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EAI_AGAIN|network)/i.test(m)) {
    return "네트워크 문제로 Microsoft 서버에 연결하지 못했어요. 잠시 후 다시 시도해주세요.";
  }
  if (/does not have.*Minecraft|doesn'?t own|xbox live account/i.test(m)) {
    return "이 Microsoft 계정에는 마인크래프트 자바 에디션이 연결되어 있지 않아요.";
  }
  return "로그인 처리 중 알 수 없는 오류가 발생했어요. 다시 시도해도 안 되면 관리자에게 문의해주세요.";
}

let msalApp: ConfidentialClientApplication | undefined;

function getMsalApp(): ConfidentialClientApplication {
  if (msalApp) return msalApp;
  if (!MS_CLIENT_ID || !MS_CLIENT_SECRET || !MS_REDIRECT_URI) {
    throw new Error("MS_CLIENT_ID, MS_CLIENT_SECRET, and MS_REDIRECT_URI must be set to use Microsoft login");
  }
  const config: Configuration = {
    auth: {
      clientId: MS_CLIENT_ID,
      clientSecret: MS_CLIENT_SECRET,
      authority: "https://login.microsoftonline.com/consumers",
    },
    cache: {
      cachePlugin: {
        beforeCacheAccess: async (ctx) => ctx.tokenCache.deserialize(readMsalCache()),
        afterCacheAccess: async (ctx) => {
          if (ctx.cacheHasChanged) writeMsalCache(ctx.tokenCache.serialize());
        },
      },
    },
  };
  msalApp = new ConfidentialClientApplication(config);
  return msalApp;
}

// Short-lived CSRF nonces for the OAuth redirect round-trip: created when we send the user to
// Microsoft, checked (and consumed) when they land back on our callback route.
const pendingLoginStates = new Set<string>();

export function createLoginState(): string {
  const value = crypto.randomUUID();
  pendingLoginStates.add(value);
  return value;
}

export function consumeLoginState(value: string | undefined): boolean {
  if (!value || !pendingLoginStates.has(value)) return false;
  pendingLoginStates.delete(value);
  return true;
}

/** Builds the URL to send the browser to for "Sign in with Microsoft". */
export function getMicrosoftLoginUrl(state: string): Promise<string> {
  return getMsalApp().getAuthCodeUrl({ scopes: LOGIN_SCOPES, redirectUri: MS_REDIRECT_URI, state });
}

/**
 * Exchanges the OAuth "code" from the callback for tokens, then hands off to prismarine-auth's
 * Authflow (which finds the token we just cached and skips straight to the Xbox Live / XSTS /
 * Minecraft Java exchange instead of prompting a device code).
 */
export async function completeMicrosoftLogin(code: string): Promise<void> {
  if (state.status === "logging-in") return;
  setState({ status: "logging-in", error: undefined });
  try {
    await getMsalApp().acquireTokenByCode({ code, scopes: LOGIN_SCOPES, redirectUri: MS_REDIRECT_URI });
    const flow = new Authflow(WEB_USERNAME, CACHE_DIR, { authTitle: MS_CLIENT_ID as any, flow: "msal" });
    const { token, profile } = await flow.getMinecraftJavaToken({ fetchProfile: true });
    setState({ status: "logged-in", token, profile: profile as Profile });
    rememberLogin();
  } catch (err: any) {
    setState({ status: "error", error: translateAuthError(err.message) });
  }
}

export function logout() {
  setState({ status: "logged-out", profile: undefined, token: undefined, certificate: undefined, error: undefined });
  fs.rmSync(RESUME_MARKER_FILE, { force: true });
}

function rememberLogin() {
  fs.mkdirSync(CACHE_DIR, { recursive: true });
  fs.writeFileSync(RESUME_MARKER_FILE, "1", "utf8");
}

/**
 * Called once at server startup. If we logged in before, prismarine-auth still has a cached
 * refresh token on disk under nmp-cache/ — reusing it here means the account survives a dev
 * server restart (or any process restart) without needing to sign in again, as long as the
 * underlying Microsoft refresh token (~90 days) hasn't expired.
 */
export function tryResumeSession() {
  if (!fs.existsSync(RESUME_MARKER_FILE)) return;
  if (!MS_CLIENT_ID) return;
  setState({ status: "logging-in", error: undefined });
  const flow = new Authflow(WEB_USERNAME, CACHE_DIR, { authTitle: MS_CLIENT_ID as any, flow: "msal" });
  flow
    .getMinecraftJavaToken({ fetchProfile: true })
    .then(({ token, profile }) => {
      setState({ status: "logged-in", token, profile: profile as Profile });
    })
    .catch((err) => {
      setState({ status: "error", error: translateAuthError(err.message) });
    });
}
