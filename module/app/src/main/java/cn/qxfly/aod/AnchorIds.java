package cn.qxfly.aod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Anchor-component id mapping: the coarse anchors shared with the UI
 * ({@code clock} / {@code image} / {@code timedate} / {@code notify}) and the
 * rules that fold legacy per-component ids onto them.
 *
 * <p>Pure logic on purpose: no Android types, so it is covered by the plain
 * JVM tests under {@code src/test}.
 */
public final class AnchorIds {

    public static final String ANCHOR_CLOCK = "clock";
    public static final String ANCHOR_IMAGE = "image";
    public static final String ANCHOR_TIMEDATE = "timedate";
    public static final String ANCHOR_NOTIFY = "notify";

    public static final String[] ANCHOR_ORDER = {
            ANCHOR_CLOCK, ANCHOR_IMAGE, ANCHOR_TIMEDATE, ANCHOR_NOTIFY,
    };

    private AnchorIds() {
    }

    /** Candidate entry names per canonical anchor, best first. */
    @Nullable
    static String[] canonicalCandidates(@NonNull String canonical) {
        switch (canonical) {
            case ANCHOR_CLOCK:
                return new String[]{
                        "gradient_layout", "clock_vertical_container",
                        "clock_container", "clock", "big_time",
                };
            case ANCHOR_IMAGE:
                return new String[]{
                        "image_shower", "image_shower_container",
                        "image_selector_shower", "classic_gallery",
                        "classic_image", "photo", "wallpaper",
                };
            case ANCHOR_TIMEDATE:
                return new String[]{
                        "clock_horizontal_container", "date_lunar",
                        "date_dual", "date", "data_dual_container",
                };
            case ANCHOR_NOTIFY:
                return new String[]{
                        "aod_icons_container", "icons", "fn_container",
                        "notification_container",
                };
            default:
                return null;
        }
    }

    /**
     * Maps a legacy fine-grained anchor id to its nearest coarse region, or
     * null when the id is not recognised (it is then used as-is, unchanged
     * behaviour for custom ids).
     */
    @Nullable
    static String legacyToCanonical(@NonNull String id) {
        String l = id.toLowerCase(java.util.Locale.US);
        if (l.contains("shower") || l.contains("image") || l.contains("gallery")
                || l.contains("photo") || l.contains("wallpaper")
                || l.contains("cover") || l.contains("album")) {
            return ANCHOR_IMAGE;
        }
        if (l.contains("gradient") || l.contains("clock_container")
                || l.contains("clock_vertical") || l.contains("big")) {
            return ANCHOR_CLOCK;
        }
        if (l.contains("icon") || l.contains("notif")) {
            return ANCHOR_NOTIFY;
        }
        if (l.contains("clock") || l.contains("date") || l.contains("time")
                || l.contains("lunar") || l.contains("battery")
                || l.contains("step")) {
            return ANCHOR_TIMEDATE;
        }
        return null;
    }

}
