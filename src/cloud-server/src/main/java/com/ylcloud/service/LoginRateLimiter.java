package com.ylcloud.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class LoginRateLimiter {
    private final JdbcTemplate jdbc;
    public LoginRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Autocommit counters survive failed credential transactions and are shared across instances.
    public boolean allow(String username, String address) {
        boolean account = consume("account:" + username.trim().toLowerCase(Locale.ROOT), 20);
        boolean source = consume("ip:" + address, 200);
        return account && source;
    }

    private boolean consume(String key, int limit) {
        String hash = BrowserSessionService.hash(key);
        Long window = jdbc.queryForObject("select FLOOR(UNIX_TIMESTAMP()/900)", Long.class);
        jdbc.update("insert into login_rate_bucket(bucket_key,window_start,attempts) values(?,?,1) " +
                "on duplicate key update attempts=LEAST(attempts+1,1000000)", hash, window);
        Integer count = jdbc.queryForObject("select attempts from login_rate_bucket where bucket_key=? and window_start=?",
                Integer.class, hash, window);
        return count != null && count <= limit;
    }
}
