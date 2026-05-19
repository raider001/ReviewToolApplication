package com.kalynx.serverlessreviewtool.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class NdjsonReader {

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
        .create();

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

    /**
     * Parses NDJSON entries directly from a string without reading from disk.
     *
     * @param content raw NDJSON content
     * @param dataType the type parameter for each stream entry's data field
     * @return parsed stream entries
     */
    public static <T> List<StreamEntry<T>> readFromString(String content, Class<T> dataType) {
        Type type = TypeToken.getParameterized(StreamEntry.class, dataType).getType();
        return readFromString(content, type);
    }

    /**
     * Parses NDJSON entries directly from a string without reading from disk.
     *
     * @param content raw NDJSON content
     * @param type the full generic type for each stream entry
     * @return parsed stream entries
     */
    public static <T> List<StreamEntry<T>> readFromString(String content, Type type) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        List<StreamEntry<T>> entries = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                entries.add(GSON.fromJson(line, type));
            }
        }
        return entries;
    }
}



