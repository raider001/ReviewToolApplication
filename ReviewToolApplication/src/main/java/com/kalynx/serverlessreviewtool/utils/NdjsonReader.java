package com.kalynx.serverlessreviewtool.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class NdjsonReader {

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
        .create();

    /**
     * Deserialises UTF-8 NDJSON bytes (one JSON object per line) into a list of entries.
     * Returns an empty list for null or empty input. Use when content comes from memory
     * (e.g. a git blob), not a file.
     */
    public static <T> List<StreamEntry<T>> fromBytes(byte[] bytes, Class<T> dataType) throws IOException {
        Type type = TypeToken.getParameterized(StreamEntry.class, dataType).getType();
        return fromBytes(bytes, type);
    }

    public static <T> List<StreamEntry<T>> fromBytes(byte[] bytes, Type type) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<StreamEntry<T>> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    StreamEntry<T> entry = GSON.fromJson(line, type);
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    public static <T> List<StreamEntry<T>> read(Path filePath, Class<T> dataType) throws IOException {
        Type type = TypeToken.getParameterized(StreamEntry.class, dataType).getType();
        return read(filePath, type);
    }

    public static <T> List<StreamEntry<T>> read(Path filePath, Type type) throws IOException {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }

        List<StreamEntry<T>> entries = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                StreamEntry<T> entry = GSON.fromJson(line, type);
                entries.add(entry);
            }
        }

        return entries;
    }
}



