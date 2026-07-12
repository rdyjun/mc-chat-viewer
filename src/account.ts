import { Authflow, Titles } from "prismarine-auth";
import { EventEmitter } from "events";

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

/** Public view of account state — never includes the raw token. */
export function getAccountState(): Omit<AccountState, "token"> {
  const { token, ...rest } = state;
  return rest;
}

export function getAccessToken(): { token: string; profile: Profile } | null {
  if (state.status === "logged-in" && state.token && state.profile) {
    return { token: state.token, profile: state.profile };
  }
  return null;
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
    })
    .catch((err) => {
      setState({ status: "error", error: err.message, msaCode: null });
    });
}

export function logout() {
  setState({ status: "logged-out", email: undefined, profile: undefined, token: undefined, msaCode: null });
}
