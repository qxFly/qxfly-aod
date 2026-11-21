package cn.qxfly.aod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Track title/artist resolution: merges the SuperLyric payload's metadata with
 * what the platform {@code MediaSession} reports. Pure logic, no Android
 * dependencies - covered by {@code TrackInfoTest}.
 *
 * <p>Why a cross-check is needed (seen live with 盐歌 / com.salt.music):
 * <ul>
 *   <li>its SuperLyric payload keeps a <b>stale track</b> - it reported
 *       "奔跑/羽·泉" while a completely different song was playing, so the
 *       payload title alone showed the wrong song on the AOD;</li>
 *   <li>its session {@code METADATA_KEY_TITLE} is the <b>current lyric line</b>,
 *       not the song title, so the session alone cannot be trusted either.</li>
 * </ul>
 * The session's ARTIST string still names the real artist/album, so when it
 * <em>contradicts</em> the payload's artist the payload metadata is stale and
 * the session wins; a session title that merely repeats a lyric line is never
 * shown as a track title.
 */
final class TrackInfo {

    @Nullable
    public final String title;
    @Nullable
    public final String artist;

    private TrackInfo(@Nullable String title, @Nullable String artist) {
        this.title = title;
        this.artist = artist;
    }

    /**
     * @param payloadTitle  SuperLyric payload title (clean when fresh)
     * @param payloadArtist SuperLyric payload artist
     * @param sessionTitle  media-session METADATA_KEY_TITLE
     * @param sessionArtist media-session METADATA_KEY_ARTIST
     * @param lines         the lyric lines currently on screen (or known to the
     *                      sources); a session title equal to one of them is the
     *                      "player publishes the line as title" pattern
     * @return the title/artist pair to display (either may be null)
     */
    @NonNull
    static TrackInfo resolve(@Nullable String payloadTitle, @Nullable String payloadArtist,
                             @Nullable String sessionTitle, @Nullable String sessionArtist,
                             @Nullable String... lines) {
        String pTitle = blankToNull(payloadTitle);
        String pArtist = blankToNull(payloadArtist);
        String sTitle = blankToNull(sessionTitle);
        String sArtist = blankToNull(sessionArtist);

        // A session title that repeats a lyric line is not a track title.
        if (sTitle != null && lines != null) {
            for (String line : lines) {
                if (line != null && sTitle.equals(line)) {
                    sTitle = null;
                    break;
                }
            }
        }

        // Contradiction between the two independent sources: the payload's
        // artist cannot be found in the session's artist string, so the payload
        // metadata belongs to an earlier track. Take the session's - its title
        // may be null (line-as-title player), which correctly drops the row to
        // artist-only instead of showing the stale song.
        if (sArtist != null && pArtist != null && !sArtist.contains(pArtist)) {
            return new TrackInfo(sTitle, sArtist);
        }

        // No contradiction (or nothing to check against): payload first, the
        // session only fills gaps - the payload carries clean raw metadata.
        if (pTitle != null) {
            return new TrackInfo(pTitle, pArtist);
        }
        if (sTitle != null || sArtist != null) {
            return new TrackInfo(sTitle, sArtist);
        }
        return new TrackInfo(null, pArtist);
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
