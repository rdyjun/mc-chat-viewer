package com.mineportal.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineportal.util.AppPaths;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.step.java.StepMCProfile;
import net.raphimc.minecraftauth.step.java.StepMCToken;
import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Microsoft login via Device Code Flow (MinecraftAuth), plus session persistence
 * to ~/.mineportal/session.json so the user does not sign in every launch.
 */
public final class AuthService {

    /** What the UI shows the user so they can complete sign-in in a browser. */
    public static final class DeviceCode {
        public final String verificationUri;
        public final String directVerificationUri;
        public final String userCode;

        DeviceCode(String verificationUri, String directVerificationUri, String userCode) {
            this.verificationUri = verificationUri;
            this.directVerificationUri = directVerificationUri;
            this.userCode = userCode;
        }
    }

    private final HttpClient httpClient = MinecraftAuth.createHttpClient();

    /**
     * Try to restore and refresh a previously saved session. Returns null when there is
     * no saved session or it can no longer be refreshed (user must sign in again).
     */
    public Account restoreSession() {
        Path file = AppPaths.sessionFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            StepFullJavaSession.FullJavaSession session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.fromJson(json);
            session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.refresh(httpClient, session);
            saveSession(session);
            return toAccount(session);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run the full device code login. Blocking (polls Microsoft until the user completes
     * sign-in), so call this off the UI thread. {@code codeCallback} is invoked once with
     * the code/URL to display.
     */
    public Account login(Consumer<DeviceCode> codeCallback) throws Exception {
        StepFullJavaSession.FullJavaSession session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.getFromInput(
                httpClient,
                new StepMsaDeviceCode.MsaDeviceCodeCallback(code ->
                        codeCallback.accept(new DeviceCode(
                                code.getVerificationUri(),
                                code.getDirectVerificationUri(),
                                code.getUserCode()))));
        saveSession(session);
        return toAccount(session);
    }

    public void logout() {
        try {
            Files.deleteIfExists(AppPaths.sessionFile());
        } catch (Exception ignored) {
        }
    }

    private void saveSession(StepFullJavaSession.FullJavaSession session) {
        try {
            JsonObject json = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.toJson(session);
            Files.write(AppPaths.sessionFile(), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private Account toAccount(StepFullJavaSession.FullJavaSession session) {
        StepMCProfile.MCProfile profile = session.getMcProfile();
        StepMCToken.MCToken token = profile.getMcToken();
        return new Account(profile.getId(), profile.getName(), token.getAccessToken());
    }
}
