package cn.datacraft.atcoder;

import java.time.Instant;

public interface AtcoderStandingsGateway {
    enum CookieStatus { AVAILABLE, MISSING, INVALID }

    AtcoderStandings.Snapshot fetchStandings(String contestId);
    AtcoderStandings.ContestMetadata fetchMetadata(String contestId);
    CookieStatus cookieStatus();
    String cookieSource();
    Instant cookieUpdatedAt();
    AtcoderStandings.Snapshot updateCookie(String cookie, String contestId);
    void clearManagedCookie();
}
