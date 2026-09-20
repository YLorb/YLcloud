package com.ylcloud.service;

import com.ylcloud.entity.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Browser credentials are opaque. Only their SHA-256 hashes leave this service. */
@Service
public class BrowserSessionService {
    public static final Duration LIFETIME = Duration.ofDays(15);
    public static final String USER_ATTRIBUTE = "browserSessionUser";
    private final JdbcTemplate jdbc;
    private final boolean secure;
    private final SecureRandom random = new SecureRandom();

    public BrowserSessionService(JdbcTemplate jdbc,
            @Value("${ylcloud.session.cookie-secure:true}") boolean secure) {
        this.jdbc = jdbc;
        this.secure = secure;
    }

    public String cookieName() { return secure ? "__Host-ylcloud_session" : "ylcloud_session"; }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String credential(HttpServletRequest request) {
        String found = null;
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (!cookieName().equals(cookie.getName())) continue;
            if (found != null || !cookie.getValue().matches("[A-Za-z0-9_-]{43}")) return null;
            found = cookie.getValue();
        }
        return found;
    }

    // Caller holds the users row lock while checking the password and creating the session.
    public String create(Long userId, HttpServletRequest request) {
        revokeCurrent(request);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int inserted = jdbc.update("insert into user_login_session " +
                "(session_id_hash,user_id,session_version,created_at,last_seen_at,expires_at) " +
                "select ?,user_id,session_version,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)," +
                "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 DAY) from users where user_id=? " +
                "and status=1 and (account_status is null or account_status='ACTIVE')", hash(raw), userId);
        if (inserted != 1) throw new IllegalStateException("Account changed during login");
        return raw;
    }

    public void setCookie(HttpServletResponse response, String raw, boolean remember) {
        var cookie = ResponseCookie.from(cookieName(), raw).httpOnly(true).secure(secure)
                .sameSite("Lax").path("/");
        if (remember) cookie.maxAge(LIFETIME);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName(), "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(0).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    public User authenticate(HttpServletRequest request) {
        String raw = credential(request);
        if (raw == null) return null;
        List<User> users = jdbc.query("select u.user_id as id,u.username,u.nickname,u.role," +
                "u.deployment_owner as deploymentOwner,u.status,u.account_status as accountStatus " +
                "from user_login_session s join users u on u.user_id=s.user_id " +
                "where s.session_id_hash=? and s.revoked_at is null " +
                "and s.session_version=u.session_version and u.status=1 " +
                "and (u.account_status is null or u.account_status='ACTIVE') " +
                "and s.expires_at>UTC_TIMESTAMP(6) " +
                "and s.last_seen_at>DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 15 DAY)",
                BeanPropertyRowMapper.newInstance(User.class), hash(raw));
        if (users.isEmpty()) return null;
        // Throttled writes. Polling may update activity but never extends absolute expiry.
        jdbc.update("update user_login_session set last_seen_at=UTC_TIMESTAMP(6) " +
                "where session_id_hash=? and revoked_at is null and expires_at>UTC_TIMESTAMP(6) " +
                "and last_seen_at<=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) " +
                "and last_seen_at>DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 15 DAY)", hash(raw));
        return users.get(0);
    }

    public void revokeCurrent(HttpServletRequest request) {
        String raw = credential(request);
        if (raw != null) jdbc.update("update user_login_session set revoked_at=UTC_TIMESTAMP(6)," +
                "revoke_reason='LOGOUT' where session_id_hash=? and revoked_at is null", hash(raw));
    }

    @Transactional
    public void revokeAll(Long userId) {
        // Same row lock as login: a concurrent login is ordered before or after revocation.
        jdbc.update("update users set session_version=session_version+1 where user_id=?", userId);
        jdbc.update("update user_login_session set revoked_at=UTC_TIMESTAMP(6),revoke_reason='REVOKE_ALL' " +
                "where user_id=? and revoked_at is null", userId);
    }

    @Scheduled(fixedDelayString = "${ylcloud.session.cleanup-ms:3600000}")
    public void cleanup() {
        jdbc.update("delete from user_login_session where expires_at<DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY) limit 1000");
        jdbc.update("delete from login_rate_bucket where window_start<FLOOR(UNIX_TIMESTAMP()/900)-2 limit 1000");
    }
}
