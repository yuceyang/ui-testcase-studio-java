package com.yuceyang.uitest.ui;

import com.vaadin.flow.dom.Element;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框选器必须让覆盖层盖住整张截图：之前覆盖层直接挂在滚动容器里、只有一屏高，
 * 图被容器裁掉的下半部分压根没有覆盖层，怎么拖都框不到。
 * 另外要能连续框多个区域（搜索栏 + 操作按钮分散在页面两处时，一个框识别不全）。
 */
class ShotRegionPickerTest {

    @Test
    void overlayCoversTheWholeImageThroughACanvasWrapper() {
        ShotRegionPicker picker = new ShotRegionPicker("data:,", regions -> { });

        Element canvas = picker.getElement().getChild(0);
        assertThat(canvas.getClassList()).contains("shot-picker-canvas");
        assertThat(canvas.getChildCount()).isEqualTo(2);
        assertThat(canvas.getChild(0).getClassList()).contains("shot-picker-image");
        assertThat(canvas.getChild(1).getClassList()).contains("shot-picker-overlay");
    }

    @Test
    void draggingNearTheEdgeAutoScrollsTheContainer() {
        assertThat(ShotRegionPicker.JS)
                .contains("autoScroll")
                .contains("scrollTop")
                .contains("pointerdown");
    }

    @Test
    void everyDragAddsOneMoreRegionInSelectionOrder() {
        List<List<int[]>> pushes = new ArrayList<>();
        ShotRegionPicker picker = new ShotRegionPicker("data:,", pushes::add);

        assertThat(picker.regions()).isEmpty();
        picker.selected(10, 20, 100, 200);
        picker.selected(900, 60, 44, 96);

        assertThat(picker.regions()).hasSize(2);
        assertThat(picker.regions().get(0)).containsExactly(10, 20, 100, 200);
        assertThat(picker.regions().get(1)).containsExactly(900, 60, 44, 96);
        // 每次变化都回调一次，界面上的「已选 N 个区域」才能跟着走
        assertThat(pushes).hasSize(2);
        assertThat(pushes.getLast()).hasSize(2);
    }

    @Test
    void tinySelectionsAreIgnored() {
        ShotRegionPicker picker = new ShotRegionPicker("data:,", regions -> { });

        picker.selected(10, 10, 3, 3);

        assertThat(picker.regions()).isEmpty();
    }

    @Test
    void removingOneBoxKeepsTheOthersInOrder() {
        ShotRegionPicker picker = new ShotRegionPicker("data:,", regions -> { });
        picker.selected(0, 0, 100, 100);
        picker.selected(200, 200, 50, 50);
        picker.selected(300, 300, 60, 60);

        picker.removed(1);

        assertThat(picker.regions()).hasSize(2);
        assertThat(picker.regions().get(0)).containsExactly(0, 0, 100, 100);
        assertThat(picker.regions().get(1)).containsExactly(300, 300, 60, 60);
    }

    @Test
    void outOfRangeRemovalChangesNothing() {
        ShotRegionPicker picker = new ShotRegionPicker("data:,", regions -> { });
        picker.selected(0, 0, 100, 100);

        picker.removed(7);
        picker.removed(-1);

        assertThat(picker.regions()).hasSize(1);
    }

    @Test
    void clearingRemovesEveryBoxAndNotifies() {
        List<List<int[]>> pushes = new ArrayList<>();
        ShotRegionPicker picker = new ShotRegionPicker("data:,", pushes::add);
        picker.selected(0, 0, 100, 100);
        picker.selected(200, 200, 50, 50);

        picker.clearRegions();

        assertThat(picker.regions()).isEmpty();
        assertThat(pushes.getLast()).isEmpty();
    }

    @Test
    void removeButtonsAndNumberBadgesAreRendered() {
        assertThat(ShotRegionPicker.JS)
                .contains("shot-picker-remove")
                .contains("shot-picker-badge")
                .contains("$server.removed");
    }
}
