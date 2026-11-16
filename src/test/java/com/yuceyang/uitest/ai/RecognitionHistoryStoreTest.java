package com.yuceyang.uitest.ai;

import com.yuceyang.uitest.tree.NodeType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最近识别记录：识别结果一出来就落盘，关弹窗/刷新都能找回；每个项目最多留 20 条。
 */
class RecognitionHistoryStoreTest {

    private static RecognitionHistoryStore.Entry entry(String projectId, String regionName, long createdAt) {
        List<RecognitionHistoryStore.RegionDraft> nodes = new ArrayList<>();
        nodes.add(new RecognitionHistoryStore.RegionDraft("root", "演示系统", NodeType.MODULE, null, null));
        nodes.add(new RecognitionHistoryStore.RegionDraft("region", regionName, NodeType.TITLE1, "root", null));
        return new RecognitionHistoryStore.Entry(null, projectId, "s1", "演示系统", "shots/a.png",
                List.of(new int[] {10, 20, 300, 120}), "按三组归类", regionName, "一句话摘要", createdAt, nodes);
    }

    @Test
    void newEntriesComeFirstAndSurviveRestart(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        store.add(entry("p1", "第一次", 1_000L));
        store.add(entry("p1", "第二次", 2_000L));

        RecognitionHistoryStore restarted = new RecognitionHistoryStore(dir.toString());
        List<RecognitionHistoryStore.Entry> recent = restarted.recent("p1", 20);

        assertThat(recent).extracting(RecognitionHistoryStore.Entry::regionName)
                .containsExactly("第二次", "第一次");
        assertThat(recent.get(0).nodes()).hasSize(2);
        assertThat(recent.get(0).rects()).singleElement().satisfies(rect ->
                assertThat(rect).containsExactly(10, 20, 300, 120));
        assertThat(recent.get(0).hint()).isEqualTo("按三组归类");
    }

    @Test
    void severalRegionsKeepTheirSelectionOrder(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        List<RecognitionHistoryStore.RegionDraft> nodes = new ArrayList<>();
        nodes.add(new RecognitionHistoryStore.RegionDraft("region", "源中心", NodeType.TITLE1, null, null));
        store.add(new RecognitionHistoryStore.Entry(null, "p1", "s1", "演示系统", "shots/a.png",
                List.of(new int[] {1143, 71, 288, 68}, new int[] {1380, 400, 44, 96}),
                "搜索栏 + 操作按钮", "源中心", "", 1L, nodes));

        RecognitionHistoryStore.Entry restored = new RecognitionHistoryStore(dir.toString()).recent("p1", 20).get(0);

        assertThat(restored.rects()).hasSize(2);
        assertThat(restored.rects().get(0)).containsExactly(1143, 71, 288, 68);
        assertThat(restored.rects().get(1)).containsExactly(1380, 400, 44, 96);
    }

    /** 显示项是草稿行上的标记：重新打开历史记录后仍要按显示项处理，不能悄悄变回功能点。 */
    @Test
    void displayFlagsSurviveARoundTrip(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        List<RecognitionHistoryStore.RegionDraft> nodes = List.of(
                new RecognitionHistoryStore.RegionDraft("region", "主机运行状态", NodeType.TITLE2, null, null),
                new RecognitionHistoryStore.RegionDraft("a", "处理器使用量", NodeType.POINT, "region", null, true),
                new RecognitionHistoryStore.RegionDraft("b", "刷新按钮", NodeType.POINT, "region", null));
        store.add(new RecognitionHistoryStore.Entry(null, "p1", "s1", "演示系统", "shots/a.png",
                List.of(new int[] {0, 0, 100, 100}), "显示类型", "主机运行状态", "", 1L, nodes));

        RecognitionHistoryStore.Entry restored = new RecognitionHistoryStore(dir.toString()).recent("p1", 20).get(0);

        assertThat(restored.nodes()).filteredOn(RecognitionHistoryStore.RegionDraft::display)
                .singleElement().satisfies(node -> assertThat(node.name()).isEqualTo("处理器使用量"));
        assertThat(restored.nodes()).filteredOn(node -> !node.display())
                .extracting(RecognitionHistoryStore.RegionDraft::name)
                .containsExactly("主机运行状态", "刷新按钮");
    }

    /** 老版本一条记录只存单个 rect，升级后不能整份历史都读不出来。 */
    @Test
    void legacySingleRectStillLoads(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("recognition-history"));
        Files.writeString(root.resolve("p1.json"), """
                {"entries":[{"id":"e1","projectId":"p1","systemId":"s1","systemName":"演示系统",
                 "screenshotPath":"shots/a.png","rect":[10,20,300,120],"hint":"旧记录",
                 "regionName":"旧区域","summary":"","createdAt":5,"nodes":[]}]}
                """);

        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        List<RecognitionHistoryStore.Entry> recent = store.recent("p1", 20);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).regionName()).isEqualTo("旧区域");
        assertThat(recent.get(0).rects()).singleElement().satisfies(rect ->
                assertThat(rect).containsExactly(10, 20, 300, 120));
    }

    @Test
    void keepsOnlyTheLatestTwenty(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        for (int i = 0; i < 25; i++) store.add(entry("p1", "第" + i + "次", i + 1L));

        List<RecognitionHistoryStore.Entry> recent = store.recent("p1", 50);

        assertThat(recent).hasSize(20);
        assertThat(recent.get(0).regionName()).isEqualTo("第24次");
        assertThat(recent.get(19).regionName()).isEqualTo("第5次");
    }

    @Test
    void projectsAreSeparated(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        store.add(entry("p1", "A 项目", 1L));
        store.add(entry("p2", "B 项目", 2L));

        assertThat(store.recent("p1", 20)).extracting(RecognitionHistoryStore.Entry::regionName)
                .containsExactly("A 项目");
        assertThat(store.recent("p2", 20)).extracting(RecognitionHistoryStore.Entry::regionName)
                .containsExactly("B 项目");
    }

    @Test
    void removeAndClearWork(@TempDir Path dir) {
        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());
        RecognitionHistoryStore.Entry first = store.add(entry("p1", "要删的", 1L));
        store.add(entry("p1", "留着的", 2L));

        assertThat(store.remove("p1", first.id())).isTrue();
        assertThat(store.recent("p1", 20)).extracting(RecognitionHistoryStore.Entry::regionName)
                .containsExactly("留着的");

        store.clear("p1");
        assertThat(store.recent("p1", 20)).isEmpty();
    }

    @Test
    void brokenFileDoesNotBreakTheDialog(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("recognition-history"));
        Files.writeString(root.resolve("p1.json"), "{ this is not json");

        RecognitionHistoryStore store = new RecognitionHistoryStore(dir.toString());

        assertThat(store.recent("p1", 20)).isEmpty();
        store.add(entry("p1", "重来一次", 1L));
        assertThat(new RecognitionHistoryStore(dir.toString()).recent("p1", 20)).hasSize(1);
    }
}
