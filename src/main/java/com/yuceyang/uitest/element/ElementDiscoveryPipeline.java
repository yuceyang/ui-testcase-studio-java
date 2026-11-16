package com.yuceyang.uitest.element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ElementDiscoveryPipeline {
    public List<NormalizedElement> normalize(List<RawElement> candidates) {
        Map<String, NormalizedElement> unique = new LinkedHashMap<>();
        for (RawElement raw : candidates) {
            if (raw == null || !raw.visible()) continue;
            String name = normalizeText(raw.name());
            if (name.isBlank()) continue;
            if (raw.occluded() || raw.area() < 4) continue;
            String semantic = normalizeDynamicIdentity(firstNonBlank(raw.semanticPath(), raw.selector(), ""));
            String fingerprint = fingerprint(raw.framePath(), raw.kind(), name, semantic);
            NormalizedElement normalized = new NormalizedElement(fingerprint, name, raw.kind(),
                    raw.component(), raw.selector(), semantic, raw.framePath(), identityStrength(raw),
                    raw.x(), raw.y(), raw.w(), raw.h());
            unique.merge(fingerprint, normalized, (a, b) -> a.identityStrength().compareTo(b.identityStrength()) >= 0 ? a : b);
        }
        List<NormalizedElement> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparing(NormalizedElement::fingerprint));
        return list;
    }

    public static String normalizeDynamicIdentity(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)([_-])[a-z0-9]+(?:[_-][a-z0-9]+)*(?=[_-]|\\s|$)", "$1*")
                .replaceAll("(?i)\\b(?:uuid|id|key|hash)[_-]?[a-f0-9-]{8,}\\b", "*")
                .replaceAll("\\s+", " ").trim();
    }

    public static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(normalizeDynamicIdentity(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String identityStrength(RawElement raw) {
        if (raw.testId() != null && !raw.testId().isBlank()) return "STRONG";
        if (raw.stableId() != null && !raw.stableId().isBlank()) return "STRONG";
        if (raw.ariaLabel() != null && !raw.ariaLabel().isBlank()) return "MEDIUM";
        return "WEAK";
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    public record RawElement(String name, String kind, String component, String selector,
            String semanticPath, String framePath, boolean visible, boolean occluded,
            double area, String testId, String stableId, String ariaLabel,
            double x, double y, double w, double h) {}

    public record NormalizedElement(String fingerprint, String name, String kind, String component,
            String selector, String semanticPath, String framePath, String identityStrength,
            double x, double y, double w, double h) {}
}
