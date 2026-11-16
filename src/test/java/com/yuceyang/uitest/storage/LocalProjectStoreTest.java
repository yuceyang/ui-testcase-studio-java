package com.yuceyang.uitest.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuceyang.uitest.system.SystemUnderTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProjectStoreTest {
    @TempDir Path directory;

    @Test
    void projectAndSystemsSurviveAStoreRestart() throws Exception {
        LocalProjectStore store = new LocalProjectStore(directory.toString(), mapper());
        WorkspaceSnapshot snapshot = store.createProject("演示项目", "独立本地模式");
        SystemUnderTest system = new SystemUnderTest(snapshot.projectId, "订单系统", "order", "");
        system.onCreateForStore();
        snapshot.systems.add(system);
        store.saveProject(snapshot, snapshot.project.getRevision());

        LocalProjectStore restarted = new LocalProjectStore(directory.toString(), mapper());
        WorkspaceSnapshot loaded = restarted.getProject(snapshot.projectId);
        assertThat(loaded.project.getName()).isEqualTo("演示项目");
        assertThat(loaded.systems).extracting(SystemUnderTest::getName).containsExactly("订单系统");
        assertThat(loaded.project.getRevision()).isEqualTo(1);
    }

    @Test
    void staleSaveCannotOverwriteAProject() throws Exception {
        LocalProjectStore store = new LocalProjectStore(directory.toString(), mapper());
        WorkspaceSnapshot snapshot = store.createProject("并发项目", "");
        WorkspaceSnapshot stale = store.getProject(snapshot.projectId);
        WorkspaceSnapshot latest = store.getProject(snapshot.projectId);
        latest.project.setName("新名称");
        store.saveProject(latest, 0);
        stale.project.setName("旧名称");
        assertThatThrownBy(() -> store.saveProject(stale, 0)).isInstanceOf(ConcurrencyException.class);
        assertThat(store.getProject(snapshot.projectId).project.getName()).isEqualTo("新名称");
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
