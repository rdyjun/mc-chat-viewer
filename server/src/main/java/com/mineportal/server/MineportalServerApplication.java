package com.mineportal.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class MineportalServerApplication {

    public static void main(String[] args) throws IOException {
        // sqlite-jdbc creates the .db file itself but not missing parent directories, and this
        // must happen before the DataSource bean is created during context refresh.
        String dbPath = System.getenv().getOrDefault("DB_PATH", "./data/app.db");
        Path parent = Paths.get(dbPath).toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        SpringApplication.run(MineportalServerApplication.class, args);
    }

}
