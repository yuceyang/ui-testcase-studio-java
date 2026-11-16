package com.yuceyang.uitest.element;

import com.yuceyang.uitest.element.ElementDiscoveryPipeline.NormalizedElement;
import com.yuceyang.uitest.element.ElementDiscoveryPipeline.RawElement;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ElementDiscoveryPipelineTest {
    private final ElementDiscoveryPipeline pipeline = new ElementDiscoveryPipeline();

    @Test
    void removesBlankInvisibleAndDuplicateCandidates() {
        RawElement first = candidate("登录", "div_itemBox_r9ujz_21 MYSQL-box", true);
        RawElement duplicate = candidate("  登录 ", "div_itemBox_other99 MYSQL-box", true);
        RawElement invisible = candidate("删除", "button", false);
        RawElement blank = candidate("  ", "button", true);
        List<NormalizedElement> result = pipeline.normalize(List.of(first, duplicate, invisible, blank));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("登录");
    }

    @Test
    void dynamicClassFragmentDoesNotChangeFingerprint() {
        String first = ElementDiscoveryPipeline.normalizeDynamicIdentity("itemBox_r9ujz_21 MYSQL-box");
        String next = ElementDiscoveryPipeline.normalizeDynamicIdentity("itemBox_other99_22 MYSQL-box");
        assertThat(ElementDiscoveryPipeline.fingerprint(first)).isEqualTo(ElementDiscoveryPipeline.fingerprint(next));
    }

    @Test
    void preservesStableTestIdAsStrongIdentity() {
        assertThat(pipeline.normalize(List.of(new RawElement("提交", "button", null, "button",
                "main/form", "main", true, false, 80, "submit-test", null, null, 0, 0, 0, 0))))
                .singleElement().extracting(NormalizedElement::identityStrength).isEqualTo("STRONG");
    }

    private static RawElement candidate(String name, String selector, boolean visible) {
        return new RawElement(name, "button", null, selector, selector, "main", visible, false, 80,
                null, null, null, 0, 0, 0, 0);
    }
}
