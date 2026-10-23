/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 Umi.
 */
package org.telegram.messenger.amegram;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Explicit UI-setting allowlist, never a dump of preferences, accounts or sessions. */
public final class AmeGramBackup {
    public static final int MAX_BYTES = 16 * 1024;
    private static final String FORMAT = "amegram-settings";

    public static final class Snapshot {
        public final boolean oledDarkBackground;

        public Snapshot(boolean oledDarkBackground) {
            this.oledDarkBackground = oledDarkBackground;
        }
    }

    private AmeGramBackup() {}

    public static byte[] encode(Snapshot snapshot) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.setIndent("  ");
            writer.beginObject();
            writer.name("format").value(FORMAT);
            writer.name("version").value(1);
            writer.name("settings").beginObject();
            writer.name("oled_dark_background").value(snapshot.oledDarkBackground);
            writer.endObject();
            writer.endObject();
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Caller owns the stream. Entire input must validate before any preference is written. */
    public static Snapshot decode(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException();
                bytes.write(buffer, 0, count);
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                Set<String> fields = new HashSet<>();
                Snapshot snapshot = null;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!fields.add(name)) throw new IOException();
                    switch (name) {
                        case "format":
                            if (reader.peek() != JsonToken.STRING || !FORMAT.equals(reader.nextString())) throw new IOException();
                            break;
                        case "version":
                            if (reader.peek() != JsonToken.NUMBER || !"1".equals(reader.nextString())) throw new IOException();
                            break;
                        case "settings":
                            snapshot = readSettings(reader);
                            break;
                        default:
                            throw new IOException();
                    }
                }
                reader.endObject();
                if (fields.size() != 3 || snapshot == null || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
                return snapshot;
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            // Parser exceptions can contain file contents. Never retain or log their cause.
            throw new IOException("Invalid or unsupported AmeGram settings backup.");
        }
    }

    private static Snapshot readSettings(JsonReader reader) throws IOException {
        reader.beginObject();
        Boolean oled = null;
        while (reader.hasNext()) {
            if (!"oled_dark_background".equals(reader.nextName()) || oled != null
                    || reader.peek() != JsonToken.BOOLEAN) throw new IOException();
            oled = reader.nextBoolean();
        }
        reader.endObject();
        if (oled == null) throw new IOException();
        return new Snapshot(oled);
    }
}
