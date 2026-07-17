package com.mineportal;

import java.io.InputStream;
import java.util.Properties;

/** Reads the app version and target GitHub repo baked into the jar at build time. */
public final class BuildInfo {

    public static final String VERSION;
    public static final String GITHUB_REPO;

    static {
        String version = "0.0.0";
        String repo = "rdyjun/mc-chat-viewer";
        try (InputStream in = BuildInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("app.version", version).trim();
                repo = props.getProperty("github.repo", repo).trim();
            }
        } catch (Exception ignored) {
        }
        VERSION = version;
        GITHUB_REPO = repo;
    }

    private BuildInfo() {
    }
}
