package cn.qxfly.aod;

/**
 * Anchor id folding rules: the UI stores coarse ids, but configurations written
 * by older builds hold fine-grained component names and must keep working.
 */
final class AnchorIdsTest {

    private AnchorIdsTest() {
    }

    static void run() {
        // Every canonical id must have at least one candidate name, otherwise
        // the anchor picker would offer an entry that can never resolve.
        for (String canonical : AnchorIds.ANCHOR_ORDER) {
            String[] cands = AnchorIds.canonicalCandidates(canonical);
            T.ok("candidates exist for " + canonical,
                    cands != null && cands.length > 0);
        }
        T.eq("unknown canonical has no candidates",
                null, AnchorIds.canonicalCandidates("nope"));

        // Legacy ids fold onto the four regions.
        T.eq("gradient_layout -> clock",
                AnchorIds.ANCHOR_CLOCK, AnchorIds.legacyToCanonical("gradient_layout"));
        T.eq("clock_vertical_container -> clock",
                AnchorIds.ANCHOR_CLOCK,
                AnchorIds.legacyToCanonical("clock_vertical_container"));
        T.eq("image_shower -> image",
                AnchorIds.ANCHOR_IMAGE, AnchorIds.legacyToCanonical("image_shower"));
        T.eq("classic_gallery -> image",
                AnchorIds.ANCHOR_IMAGE, AnchorIds.legacyToCanonical("classic_gallery"));
        T.eq("aod_icons_container -> notify",
                AnchorIds.ANCHOR_NOTIFY,
                AnchorIds.legacyToCanonical("aod_icons_container"));
        T.eq("notification_container -> notify",
                AnchorIds.ANCHOR_NOTIFY,
                AnchorIds.legacyToCanonical("notification_container"));
        T.eq("date_lunar -> timedate",
                AnchorIds.ANCHOR_TIMEDATE, AnchorIds.legacyToCanonical("date_lunar"));

        // Case-insensitive: MIUI entry names have changed case between builds.
        T.eq("case insensitive",
                AnchorIds.ANCHOR_IMAGE, AnchorIds.legacyToCanonical("IMAGE_Shower"));

        // Unrecognised ids stay untouched (custom components keep working).
        T.eq("unknown id survives",
                null, AnchorIds.legacyToCanonical("my_custom_slot"));

        // Canonical rewrite must keep the '|' separator: dropping it produced
        // ids like "clockGradientLinearLayout" and an all-English picker.
        T.eq("canonical line keeps separator",
                "clock|GradientLinearLayout|[324,657 551x195]",
                ComponentCollector.canonicalLine("clock", "gradient_layout",
                        "gradient_layout|GradientLinearLayout|[324,657 551x195]"));
        T.eq("canonical line bounds",
                "[324,657 551x195]",
                ComponentCollector.boundsOf(
                        "clock|GradientLinearLayout|[324,657 551x195]"));
        T.eq("bounds of short line is empty", "", ComponentCollector.boundsOf("clock|X"));
    }
}
