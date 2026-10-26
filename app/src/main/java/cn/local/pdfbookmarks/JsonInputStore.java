package cn.local.pdfbookmarks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Owns only files/imports/json UUID files. It never reads or deletes the original provider URI. */
public final class JsonInputStore {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final String META = ".meta.json";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Object LOCK = new Object();
    private final Path filesDir;

    public JsonInputStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("缺少应用内部文件目录。");
        if (Files.isSymbolicLink(filesDir.toPath())) throw new IllegalArgumentException("JSON 工作目录不能是链接。");
        try { this.filesDir = filesDir.getCanonicalFile().toPath(); }
        catch (IOException invalid) { throw new IllegalArgumentException("无法核实应用内部文件目录。", invalid); }
    }

    /** Called only after import confirmation; publishes metadata last as the commit marker. */
    public JsonInputRef importShared(String sourceUri, String displayName, byte[] utf8Bytes) throws IOException {
        if (utf8Bytes == null || utf8Bytes.length == 0 || utf8Bytes.length > MAX_BYTES) {
            throw new IOException("目录 JSON 为空或超过 4MB。");
        }
        byte[] bytes = utf8Bytes.clone();
        utf8(bytes);
        JsonInputRef ref = new JsonInputRef(UUID.randomUUID().toString(), sourceUri, displayName, digest(bytes));
        byte[] metadata = JSON.toJson(ref).getBytes(StandardCharsets.UTF_8);
        if (metadata.length > MAX_METADATA_BYTES) throw new IOException("JSON 来源信息过长。");
        synchronized (LOCK) {
            Path directory = directory(true);
            Path data = data(directory, ref.id), meta = metadata(directory, ref.id);
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS) || Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("JSON 工作文件标识已存在，未覆盖原文件。");
            }
            Path partialData = directory.resolve(ref.id + ".json.part");
            Path partialMeta = directory.resolve(ref.id + META + ".part");
            // The UUID is reserved by CREATE_NEW under the store lock; publish only complete files.
            writeNew(partialData, bytes);
            writeNew(partialMeta, metadata);
            Files.move(partialData, data, StandardCopyOption.ATOMIC_MOVE);
            Files.move(partialMeta, meta, StandardCopyOption.ATOMIC_MOVE);
            if (!ref.equals(readMetadata(directory, ref.id))) throw new IOException("JSON 导入记录未通过回读校验。");
            read(ref);
            return ref;
        }
    }

    public byte[] read(JsonInputRef ref) throws IOException {
        if (ref == null) throw new IllegalArgumentException("缺少 JSON 工作文件记录。");
        synchronized (LOCK) {
            Path directory = directory(false);
            if (directory == null || !ref.equals(readMetadata(directory, ref.id))) {
                throw new IOException("JSON 工作文件记录缺失或已经变化，请重新导入。");
            }
            byte[] bytes = readRegular(data(directory, ref.id), MAX_BYTES);
            if (!ref.sha256.equals(digest(bytes))) throw new IOException("JSON 工作文件内容已经变化，请重新导入。");
            utf8(bytes);
            return bytes;
        }
    }

    /** Exact identity lookup for v1 drafts. No filename search or source-URI opening occurs. */
    public JsonInputRef find(String sourceUri, String sha256) throws IOException {
        // Reuse the same validation rules without creating a file or inferring an external path.
        JsonInputRef checked = new JsonInputRef("00000000-0000-0000-0000-000000000000", sourceUri, "input.json", sha256);
        synchronized (LOCK) {
            Path directory = directory(false);
            if (directory == null) return null;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + META)) {
                for (Path path : entries) {
                    String name = path.getFileName().toString(), id = name.substring(0, name.length() - META.length());
                    try { JsonInputRef.checkedId(id); }
                    catch (IllegalArgumentException invalid) { continue; }
                    final JsonInputRef ref;
                    try { ref = readMetadata(directory, id); }
                    catch (IOException invalid) { continue; }
                    if (ref != null && checked.sourceUri.equals(ref.sourceUri) && checked.sha256.equals(ref.sha256)) {
                        read(ref);
                        return ref;
                    }
                }
            }
            return null;
        }
    }

    /** Metadata lookup only, allowing a cleanup retry when data deletion completed before a crash. */
    public JsonInputRef findById(String id) throws IOException {
        JsonInputRef.checkedId(id);
        synchronized (LOCK) {
            Path directory = directory(false);
            return directory == null ? null : readMetadata(directory, id);
        }
    }

    /** Deletes only this exact imported byte file and its matching metadata; source URI is untouched. */
    public void deleteOwned(JsonInputRef ref) throws IOException {
        if (ref == null) throw new IllegalArgumentException("缺少 JSON 工作文件记录。");
        synchronized (LOCK) {
            Path directory = directory(false);
            if (directory == null) return;
            Path data = data(directory, ref.id), meta = metadata(directory, ref.id);
            JsonInputRef recorded = readMetadata(directory, ref.id);
            if (recorded == null) {
                if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) throw new IOException("JSON 工作文件缺少所属记录，未删除。");
                return;
            }
            if (!ref.equals(recorded)) throw new IOException("JSON 工作文件所属记录已变化，未删除。");
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) {
                read(ref);
                Files.delete(data);
            }
            // Keep metadata if byte deletion failed; if this step fails, findById still enables retry.
            Files.delete(meta);
        }
    }

    private Path directory(boolean create) throws IOException {
        Path current = filesDir;
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null;
            Files.createDirectories(current);
        }
        folder(current);
        for (String name : new String[]{"imports", "json"}) {
            current = current.resolve(name);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null;
                Files.createDirectory(current);
            }
            folder(current);
        }
        return current;
    }

    private static void folder(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JSON 工作目录不是有效的应用内部文件夹。");
        }
    }

    private static Path data(Path directory, String id) { return directory.resolve(JsonInputRef.checkedId(id) + ".json"); }
    private static Path metadata(Path directory, String id) { return directory.resolve(JsonInputRef.checkedId(id) + META); }

    private static JsonInputRef readMetadata(Path directory, String id) throws IOException {
        Path path = metadata(directory, id);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        String text = utf8(readRegular(path, MAX_METADATA_BYTES));
        Map<String, String> fields = new HashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ((!key.equals("id") && !key.equals("sourceUri") && !key.equals("displayName") && !key.equals("sha256"))
                        || fields.containsKey(key) || reader.peek() != JsonToken.STRING) {
                    throw new IOException("JSON 导入记录字段无效。");
                }
                fields.put(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.size() != 4) throw new IOException("JSON 导入记录不完整。");
            JsonInputRef ref = new JsonInputRef(fields.get("id"), fields.get("sourceUri"), fields.get("displayName"), fields.get("sha256"));
            if (!id.equals(ref.id)) throw new IOException("JSON 导入记录标识不匹配。");
            return ref;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("JSON 导入记录无效。", invalid);
        }
    }

    private static byte[] readRegular(Path path, int limit) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JSON 工作文件缺失或不是普通文件。");
        }
        if (Files.size(path) > limit) throw new IOException("JSON 工作文件超过大小限制。");
        try (InputStream input = Files.newInputStream(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > limit) throw new IOException("JSON 工作文件超过大小限制。");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IOException("JSON 工作文件不是有效 UTF-8。", invalid); }
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : hash) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
