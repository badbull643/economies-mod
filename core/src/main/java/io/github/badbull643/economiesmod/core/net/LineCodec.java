package io.github.badbull643.economiesmod.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Log lines packed for the wire: gzip, then base64, so a chunk of history travels as one string
 * inside the ordinary JSON message.
 *
 * Log lines are mostly field names, hex hashes and UUIDs that repeat from line to line — each
 * line's prevHash is the previous line's hash, and every line names the same market — so a chunk
 * shrinks by well over half even after base64 adds its third back. Signatures are random and do
 * not shrink; everything else does.
 *
 * Applied to a whole chunk rather than per line: gzip needs neighbours to find the repetition, and
 * a chunk is already the unit the chunking code bounds.
 */
public final class LineCodec {

    /**
     * How far one packed chunk may inflate before it is refused.
     *
     * A chunk is built to stay under MessageChannel's 700 KB budget before packing, so an honest
     * one inflates to well under a megabyte. Gzip can expand a tiny input into an enormous one,
     * and this decodes input from the network, so the limit is what stops a hostile peer from
     * sending a kilobyte that unpacks into gigabytes.
     */
    static final int MAX_INFLATED_BYTES = 32 * 1024 * 1024;

    private LineCodec() {}

    public static String pack(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(bytes)) {
            boolean first = true;
            for (String line : lines) {
                if (!first) gz.write('\n');
                gz.write(line.getBytes(StandardCharsets.UTF_8));
                first = false;
            }
        } catch (IOException e) {
            throw new IllegalStateException("gzip into memory failed", e);   // cannot happen
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static List<String> unpack(String packed) throws IOException {
        List<String> out = new ArrayList<>();
        if (packed == null || packed.isEmpty()) return out;
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(packed);
        } catch (IllegalArgumentException e) {
            throw new IOException("packed lines are not base64");
        }
        ByteArrayOutputStream inflated = new ByteArrayOutputStream();
        try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                if (inflated.size() + n > MAX_INFLATED_BYTES) {
                    throw new IOException("packed lines inflate past "
                            + MAX_INFLATED_BYTES + " bytes");
                }
                inflated.write(buf, 0, n);
            }
        }
        for (String line : new String(inflated.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }
}
