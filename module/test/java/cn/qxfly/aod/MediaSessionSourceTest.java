package cn.qxfly.aod;

/**
 * The session position clock. A healthy player refreshes
 * {@code lastPositionUpdateTime} every few seconds, so extrapolation only
 * bridges those gaps; a stalled player freezes the timestamp while still
 * reporting STATE_PLAYING, which used to race the displayed progress to the
 * end of the song ("4:50/4:50" pinned forever).
 */
final class MediaSessionSourceTest {

    private MediaSessionSourceTest() {
    }

    static void run() {
        long now = 1_000_000L;

        // Playing, fresh timestamp: normal bridge from the last update.
        T.eq("fresh playing extrapolates",
                100_000L + 2_000L,
                MediaSessionSource.extrapolate(100_000L, now - 2_000L, 1f, true, now));

        // Playing, frozen timestamp: bounded by the cap instead of racing to
        // the end of the song.
        T.eq("frozen playing stops at the cap",
                100_000L + MediaSessionSource.POSITION_MAX_EXTRAPOLATION_MS,
                MediaSessionSource.extrapolate(100_000L, now - 600_000L, 1f, true, now));

        // Paused never advances, however old the sample is.
        T.eq("paused never advances",
                100_000L,
                MediaSessionSource.extrapolate(100_000L, now - 600_000L, 1f, false, now));

        // The cap scales with playback speed (2x for the capped window).
        T.eq("cap scales with speed",
                100_000L + 2 * MediaSessionSource.POSITION_MAX_EXTRAPOLATION_MS,
                MediaSessionSource.extrapolate(100_000L, now - 600_000L, 2f, true, now));
        T.eq("speed applies to fresh windows",
                100_000L + 3_000L,
                MediaSessionSource.extrapolate(100_000L, now - 1_500L, 2f, true, now));

        // Missing or bogus inputs stay safe.
        T.eq("unknown update time does not advance",
                100_000L,
                MediaSessionSource.extrapolate(100_000L, 0L, 1f, true, now));
        T.eq("future update time does not go backwards",
                100_000L,
                MediaSessionSource.extrapolate(100_000L, now + 5_000L, 1f, true, now));
        T.eq("zero speed does not advance",
                100_000L,
                MediaSessionSource.extrapolate(100_000L, now - 1_000L, 0f, true, now));
        T.eq("negative base clamps to zero",
                MediaSessionSource.POSITION_MAX_EXTRAPOLATION_MS,
                MediaSessionSource.extrapolate(-1L, now - 600_000L, 1f, true, now));
    }
}
