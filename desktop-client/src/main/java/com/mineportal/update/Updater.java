package com.mineportal.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineportal.BuildInfo;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Checks GitHub Releases for a newer version and, when possible, swaps the running
 * fat jar via a small detached batch script. Falls back to opening the release page.
 */
public final class Updater {

    public static final class ReleaseInfo {
        public final String tag;
        public final String jarDownloadUrl; // null if the release has no .jar asset
        public final String htmlUrl;

        ReleaseInfo(String tag, String jarDownloadUrl, String htmlUrl) {
            this.tag = tag;
            this.jarDownloadUrl = jarDownloadUrl;
            this.htmlUrl = htmlUrl;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Returns release info when the latest release is newer than the running version, else null. */
    public ReleaseInfo checkForUpdate() throws Exception {
        String url = "https://api.github.com/repos/" + BuildInfo.GITHUB_REPO + "/releases/latest";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "mineportal-client/" + BuildInfo.VERSION)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = json.has("tag_name") && !json.get("tag_name").isJsonNull()
                ? json.get("tag_name").getAsString() : null;
        if (tag == null) {
            return null;
        }
        if (!isNewer(tag, BuildInfo.VERSION)) {
            return null;
        }

        String htmlUrl = json.has("html_url") ? json.get("html_url").getAsString() : null;
        String jarUrl = null;
        if (json.has("assets") && json.get("assets").isJsonArray()) {
            JsonArray assets = json.getAsJsonArray("assets");
            for (JsonElement el : assets) {
                JsonObject asset = el.getAsJsonObject();
                String name = asset.has("name") ? asset.get("name").getAsString() : "";
                if (name.toLowerCase().endsWith(".jar")) {
                    jarUrl = asset.get("browser_download_url").getAsString();
                    break;
                }
            }
        }
        return new ReleaseInfo(tag, jarUrl, htmlUrl);
    }

    /**
     * Downloads the new jar and launches a detached script that replaces the running jar
     * once this process exits, then relaunches it. Returns false when a self-update is not
     * possible (e.g. running from an IDE/classes dir or no jar asset) — the caller should
     * open the release page instead. On success the caller must exit the app promptly.
     */
    public boolean applyUpdate(ReleaseInfo release) {
        if (release.jarDownloadUrl == null) {
            return false;
        }
        Path currentJar = currentJarPath();
        if (currentJar == null) {
            return false;
        }
        try {
            Path newJar = currentJar.resolveSibling(currentJar.getFileName() + ".new");
            download(release.jarDownloadUrl, newJar);

            long pid = ProcessHandle.current().pid();
            Path script = Files.createTempFile("mineportal-update", ".bat");
            String java = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
            String content = ""
                    + "@echo off\r\n"
                    + "setlocal\r\n"
                    + ":waitloop\r\n"
                    + "tasklist /fi \"PID eq " + pid + "\" | find \"" + pid + "\" >nul\r\n"
                    + "if not errorlevel 1 (\r\n"
                    + "  timeout /t 1 /nobreak >nul\r\n"
                    + "  goto waitloop\r\n"
                    + ")\r\n"
                    + "move /y \"" + newJar + "\" \"" + currentJar + "\" >nul\r\n"
                    + "start \"\" \"" + java + "\" -jar \"" + currentJar + "\"\r\n"
                    + "del \"%~f0\"\r\n";
            Files.write(script, content.getBytes());

            new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min",
                    script.toString())
                    .start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void download(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "mineportal-client/" + BuildInfo.VERSION)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Download failed: HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path currentJarPath() {
        try {
            URI uri = Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            if (path.toString().toLowerCase().endsWith(".jar")) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Compares dotted version strings, ignoring a leading 'v'. Non-numeric parts fall back to string compare. */
    static boolean isNewer(String remote, String local) {
        int[] r = parse(remote);
        int[] l = parse(local);
        if (r == null || l == null) {
            return !normalize(remote).equals(normalize(local));
        }
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv != lv) {
                return rv > lv;
            }
        }
        return false;
    }

    private static String normalize(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    private static int[] parse(String v) {
        try {
            String[] parts = normalize(v).split("\\.");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
