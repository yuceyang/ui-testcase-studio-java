package com.yuceyang.uitest.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuceyang.uitest.tree.NodeType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 最近识别记录：手动截图框选识别出来的草稿，关掉弹窗后还能找回。
 *
 * <p>存在 {@code <数据目录>/recognition-history/<项目>.json}，每个项目保留最近 20 条。
 * 只记草稿本身（区域名、提示词、节点名称/类型/层级）和来源截图路径 —— 不复制截图文件，
 * 恢复时按原路径重新裁剪；截图被删也能恢复出节点，只是没有缩略图。
 * 这是本机缓存，不是业务数据：删掉只等于清空「最近识别」。
 */
@Service
public class RecognitionHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(RecognitionHistoryStore.class);
    private static final int MAX_ENTRIES = 20;

    /**
     * 草稿节点：只留恢复所需的最小字段，等价于界面上的 DraftNode。
     * id 要一起存 —— parentId 指的是它，丢了就接不回父子关系。
     */
    public record RegionDraft(String id, String name, NodeType type, String parentId, String elementId,
                              boolean display) {

        /** 老记录没有 display 字段与 node_type 之外的标记，读出来按普通节点处理。 */
        public RegionDraft(String id, String name, NodeType type, String parentId, String elementId) {
            this(id, name, type, parentId, elementId, false);
        }
    }

    /**
     * 一条识别记录。{@code rects} 是这次框选的若干个区域（图片原始像素，按框选顺序）；
     * 早期版本只存一个 {@code rect}，读旧文件时由 {@link #read} 转成单元素列表，缩略图不至于丢。
     */
    public record Entry(String id, String projectId, String systemId, String systemName,
                        String screenshotPath, List<int[]> rects, String hint,
                        String regionName, String summary, long createdAt, List<RegionDraft> nodes) {

        @JsonCreator
        static Entry read(@JsonProperty("id") String id,
                          @JsonProperty("projectId") String projectId,
                          @JsonProperty("systemId") String systemId,
                          @JsonProperty("systemName") String systemName,
                          @JsonProperty("screenshotPath") String screenshotPath,
                          @JsonProperty("rects") List<int[]> rects,
                          @JsonProperty("rect") int[] legacyRect,
                          @JsonProperty("hint") String hint,
                          @JsonProperty("regionName") String regionName,
                          @JsonProperty("summary") String summary,
                          @JsonProperty("createdAt") long createdAt,
                          @JsonProperty("nodes") List<RegionDraft> nodes) {
            List<int[]> picked = rects == null ? List.of() : rects;
            if (picked.isEmpty() && legacyRect != null && legacyRect.length >= 4) {
                picked = List.of(legacyRect);
            }
            return new Entry(id, projectId, systemId, systemName, screenshotPath, picked, hint,
                    regionName, summary, createdAt, nodes);
        }
    }

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, List<Entry>> cache = new LinkedHashMap<>();
    private final Object lock = new Object();

    public RecognitionHistoryStore(@Value("${qts.storage.directory}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize().resolve("recognition-history");
    }

    /** 某项目的最近记录，新的在前。 */
    public List<Entry> recent(String projectId, int limit) {
        if (projectId == null) return List.of();
        synchronized (lock) {
            List<Entry> entries = load(projectId);
            List<Entry> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparingLong(Entry::createdAt).reversed());
            return limit > 0 && sorted.size() > limit ? List.copyOf(sorted.subList(0, limit)) : List.copyOf(sorted);
        }
    }

    public Optional<Entry> find(String projectId, String entryId) {
        return recent(projectId, 0).stream().filter(e -> e.id().equals(entryId)).findFirst();
    }

    /** 记一条；id 与时间戳由这里补，调用方不用管。返回落库后的记录。 */
    public Entry add(Entry entry) {
        if (entry == null || entry.projectId() == null) return entry;
        Entry stored = new Entry(entry.id() == null || entry.id().isBlank() ? UUID.randomUUID().toString() : entry.id(),
                entry.projectId(), entry.systemId(), entry.systemName(), entry.screenshotPath(),
                entry.rects() == null ? List.of() : List.copyOf(entry.rects()),
                entry.hint(), entry.regionName(), entry.summary(),
                entry.createdAt() > 0 ? entry.createdAt() : System.currentTimeMillis(), entry.nodes());
        synchronized (lock) {
            List<Entry> entries = new ArrayList<>(load(entry.projectId()));
            entries.removeIf(e -> e.id().equals(stored.id()));
            entries.add(0, stored);
            if (entries.size() > MAX_ENTRIES) entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
            cache.put(entry.projectId(), entries);
            persist(entry.projectId(), entries);
        }
        return stored;
    }

    public boolean remove(String projectId, String entryId) {
        if (projectId == null || entryId == null) return false;
        synchronized (lock) {
            List<Entry> entries = new ArrayList<>(load(projectId));
            boolean removed = entries.removeIf(e -> e.id().equals(entryId));
            if (!removed) return false;
            cache.put(projectId, entries);
            persist(projectId, entries);
            return true;
        }
    }

    public void clear(String projectId) {
        if (projectId == null) return;
        synchronized (lock) {
            List<Entry> empty = List.of();
            cache.put(projectId, new ArrayList<>());
            persist(projectId, empty);
        }
    }

    private List<Entry> load(String projectId) {
        List<Entry> cached = cache.get(projectId);
        if (cached != null) return cached;
        List<Entry> entries = new ArrayList<>();
        Path file = file(projectId);
        if (Files.isRegularFile(file)) {
            try {
                Stored stored = mapper.readValue(file.toFile(), Stored.class);
                if (stored != null && stored.entries() != null) entries.addAll(stored.entries());
            } catch (IOException | RuntimeException e) {
                // 记录文件坏了就当没有：识别本身已经完成，不能因为历史文件让弹窗报错。
                log.warn("识别记录读取失败，已忽略：{}", e.getMessage());
            }
        }
        cache.put(projectId, entries);
        return entries;
    }

    private void persist(String projectId, List<Entry> entries) {
        try {
            Files.createDirectories(root);
            Path target = file(projectId);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), new Stored(entries));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("识别记录写入失败：{}", e.getMessage());
        }
    }

    private Path file(String projectId) {
        String name = String.valueOf(projectId).replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(name + ".json");
    }

    /** 文件结构；用 record 是为了让 Jackson 直接读写，不留 setter。 */
    public record Stored(List<Entry> entries) {}
}
