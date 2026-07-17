package com.mineportal.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mineportal.util.AppPaths;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads and persists the server list to ~/.mineportal/servers.json. */
public final class ServerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ServerEntry>>() {
    }.getType();

    public static List<ServerEntry> load() {
        Path file = AppPaths.serversFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<ServerEntry> list = GSON.fromJson(json, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void save(List<ServerEntry> servers) {
        try {
            Path file = AppPaths.serversFile();
            Files.write(file, GSON.toJson(servers, LIST_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("서버 목록을 저장하지 못했습니다: " + e.getMessage(), e);
        }
    }

    private ServerStore() {
    }
}
