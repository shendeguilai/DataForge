package cn.datacraft.cspsim;

import cn.datacraft.cspsim.CspTypes.Entry;
import cn.datacraft.job.ArtifactStorage;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Component
public class CspFiles {
    public static final int MAX_FILE = 2 * 1024 * 1024;
    public static final long MAX_WORKSPACE = 25L * 1024 * 1024;
    private final Path root;
    public CspFiles(ArtifactStorage storage) throws IOException {
        root = storage.root().resolve("csp-sim/blobs");
        Files.createDirectories(root);
    }
    public String put(byte[] bytes) {
        String id = UUID.randomUUID().toString();
        try { Files.write(root.resolve(id), bytes, StandardOpenOption.CREATE_NEW); return id; }
        catch (IOException e) { throw new IllegalStateException("文件保存失败", e); }
    }
    public byte[] get(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("文件标识无效");
        try { return Files.readAllBytes(root.resolve(id)); }
        catch (IOException e) { throw new IllegalStateException("保存的文件无法读取，请联系老师", e); }
    }
    public long size(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("文件标识无效");
        try { return Files.size(root.resolve(id)); }
        catch (IOException e) { throw new IllegalStateException("保存的文件无法读取，请联系老师", e); }
    }
    public static String path(String raw, boolean windows) {
        if (raw == null || raw.length() > 500 || raw.indexOf('\0') >= 0 || raw.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("路径无效");
        String value = windows ? raw.replace('\\', '/') : raw;
        if (windows && value.matches("^[A-Za-z]:/?.*")) value = "/" + value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(2);
        if (windows && value.matches("/[a-z](/.*)?")) value = "/" + value.substring(1,2).toUpperCase(Locale.ROOT) + value.substring(2);
        if (!value.startsWith("/") || value.contains("\\")) throw new IllegalArgumentException("请输入完整路径");
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.equals("/")) return value;
        if (windows && !value.matches("/[A-Z](/.*)?")) throw new IllegalArgumentException("Windows路径必须从盘符开始，如D:/answers");
        String[] parts = value.substring(1).split("/", -1);
        for (String name : parts) name(name, windows);
        return value;
    }
    public static void name(String name, boolean windows) {
        if (name == null || name.isEmpty() || name.length() > 150 || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("文件或目录名称无效");
        if (windows && (name.matches(".*[<>:\"|?*].*") || name.endsWith(".") || name.endsWith(" ")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")))
            throw new IllegalArgumentException("Windows 文件名包含保留名称或无效字符");
    }
    public static String parent(String path) { int i = path.lastIndexOf('/'); return i <= 0 ? "/" : path.substring(0, i); }
    public static String base(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    public static String find(Map<String, Entry> entries, String path, boolean windows) {
        return entries.keySet().stream().filter(p -> windows ? p.equalsIgnoreCase(path) : p.equals(path)).findFirst().orElse(null);
    }
    public static String actualParent(Map<String, Entry> entries, String path, boolean windows) {
        String parent = find(entries, parent(path), windows);
        if (parent == null || !entries.get(parent).directory) throw new IllegalArgumentException("请先创建上级目录");
        return (parent.equals("/") ? "" : parent) + "/" + base(path);
    }
    public static void seed(Map<String, Entry> entries, String path) {
        entries.putIfAbsent("/", new Entry(true));
        if (path.equals("/")) return;
        String current = "";
        for (String segment : path.substring(1).split("/")) { current += "/" + segment; entries.putIfAbsent(current, new Entry(true)); }
    }
    public static void operate(Map<String, Entry> entries, String action, String rawPath, String rawTarget, boolean windows) {
        String path = path(rawPath, windows), found = find(entries, path, windows);
        if ("mkdir".equals(action)) {
            if (windows && parent(path).equals("/")) throw new IllegalArgumentException("不能在此电脑下创建磁盘，请进入已有盘符");
            if (found != null) throw new IllegalArgumentException("这个名称已存在");
            entries.put(actualParent(entries, path, windows), new Entry(true));
        } else if ("delete".equals(action) || "move".equals(action)) {
            if (found == null) throw new IllegalArgumentException("文件不存在");
            if (found.equals("/") || windows && found.matches("/[A-Z]")) throw new IllegalArgumentException("不能修改根目录");
            List<String> children = entries.keySet().stream().filter(p -> p.equals(found) || p.startsWith(found + "/")).toList();
            if ("delete".equals(action)) children.forEach(entries::remove);
            else {
                String target = actualParent(entries, path(rawTarget, windows), windows);
                String collision = find(entries, target, windows);
                if (collision != null && !collision.equals(found)) throw new IllegalArgumentException("目标名称已存在");
                String lowerTarget = windows ? target.toLowerCase(Locale.ROOT) : target;
                String lowerFound = windows ? found.toLowerCase(Locale.ROOT) : found;
                if (lowerTarget.startsWith(lowerFound + "/")) throw new IllegalArgumentException("不能移动到自己的子目录");
                Map<String, Entry> moved = new LinkedHashMap<>();
                for (String child : children) moved.put(target + child.substring(found.length()), entries.get(child));
                children.forEach(entries::remove); entries.putAll(moved);
            }
        } else throw new IllegalArgumentException("不支持的文件操作");
        quota(entries);
    }
    public static void quota(Map<String, Entry> entries) {
        if (entries.size() > 1500 || entries.values().stream().mapToLong(e -> e.bytes).sum() > MAX_WORKSPACE)
            throw new IllegalArgumentException("工作区最多 1500 个项目、25MB 文件");
    }
    /** Extract to memory only; never use archive paths as host filesystem paths. */
    public static Map<String, byte[]> unzip(byte[] bytes) {
        if (bytes.length > MAX_WORKSPACE) throw new IllegalArgumentException("数据包不能超过25MB");
        Map<String, byte[]> result = new LinkedHashMap<>(); long total = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 2500) throw new IllegalArgumentException("数据包文件数量过多");
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || Arrays.stream(name.split("/")).anyMatch(p -> p.equals("..") || p.equals(".")))
                    throw new IllegalArgumentException("数据包含有不安全路径");
                if (entry.isDirectory()) continue;
                byte[] content = zip.readNBytes(25 * 1024 * 1024 + 1);
                total += content.length;
                if (total > 100L * 1024 * 1024 || content.length > 25 * 1024 * 1024)
                    throw new IllegalArgumentException("数据包展开后过大");
                if (result.putIfAbsent(name, content) != null) throw new IllegalArgumentException("数据包包含重名文件");
            }
        } catch (IOException e) { throw new IllegalArgumentException("无法读取ZIP数据包", e); }
        if (result.isEmpty()) throw new IllegalArgumentException("ZIP中没有文件");
        return result;
    }
    public static byte[] zip(Map<String, byte[]> files) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (var file : files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry(); }
            zip.finish(); return buffer.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("导出失败", e); }
    }
    public static List<List<String>> csv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (quoted || cell.isEmpty()) quoted = !quoted;
                else throw new IllegalArgumentException("CSV引号格式错误");
            } else if (!quoted && (c == ',' || c == '\n' || c == '\r')) {
                row.add(cell.toString()); cell.setLength(0);
                if (c != ',') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(row);
                    row = new ArrayList<>();
                }
            } else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV引号未闭合");
        row.add(cell.toString()); if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(row);
        if (rows.size() > 2001) throw new IllegalArgumentException("一次最多导入2000名学生");
        return rows;
    }
    public static String csvCell(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        if (text.matches("^[=+@\\-\\t\\r].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
