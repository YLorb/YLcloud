package com.ylcloud.service;

import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.utils.HashUtil;
import com.ylcloud.utils.ShortLinkUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** 持久化预览地址别名；短码不是访问凭证，目标接口仍执行原有鉴权。 */
@Service
public class PreviewLinkService {
    private static final Pattern TARGET = Pattern.compile(
            "(?:/api/file/preview/[A-Za-z0-9_-]+/stream\\?parentId=(?:[0-9]+|null)"
            + "|/api/space/[0-9]+/files/[0-9]+(?:/versions/[0-9]+)?/preview(?:/stream)?"
            + "|/api/space/[0-9]+/rag/evidence/[A-Za-z0-9_-]+/preview)(?:#page=[1-9][0-9]*)?");
    private final JdbcTemplate db;
    private final ShortLinkUtil generator;

    public PreviewLinkService(JdbcTemplate db, ShortLinkUtil generator) {
        this.db = db;
        this.generator = generator;
    }

    public String shorten(String target) {
        validateTarget(target);
        String hash = HashUtil.sha256(target);
        String existing = findByTarget(hash, target, false);
        if (existing != null) return "/p/" + existing;
        try {
            String code = generator.generate(candidate -> {
                try {
                    db.update("INSERT INTO preview_short_link(short_code,target_hash,target_url) VALUES(?,?,?)",
                            candidate, hash, target);
                    return true;
                } catch (DuplicateKeyException duplicate) {
                    // Locking read sees a concurrently committed row even in REPEATABLE READ.
                    String winner = findByTarget(hash, target, true);
                    if (winner != null) {
                        throw new ExistingTarget(winner);
                    }
                    return false; // Only the candidate short code collided: let the utility retry.
                }
            });
            return "/p/" + code;
        } catch (ExistingTarget existingTarget) {
            return "/p/" + existingTarget.code;
        }
    }

    public String resolve(String code) {
        if (code == null || !code.matches("[A-Za-z0-9]{1,11}")) {
            throw new NotFoundException("预览链接不存在");
        }
        var targets = db.query("SELECT target_url FROM preview_short_link WHERE short_code=?",
                (rs, row) -> rs.getString(1), code);
        if (targets.isEmpty()) throw new NotFoundException("预览链接不存在");
        String target = targets.get(0);
        validateTarget(target);
        return target;
    }

    private String findByTarget(String hash, String target, boolean currentRead) {
        var rows = db.query("SELECT short_code,target_url FROM preview_short_link WHERE target_hash=?"
                        + (currentRead ? " FOR SHARE" : ""),
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, hash);
        if (rows.isEmpty()) return null;
        if (!target.equals(rows.get(0)[1])) throw new IllegalStateException("Preview target hash collision");
        return rows.get(0)[0];
    }

    private static void validateTarget(String target) {
        if (target == null || !TARGET.matcher(target).matches()) {
            throw new IllegalArgumentException("不支持的预览地址");
        }
    }

    private static final class ExistingTarget extends RuntimeException {
        private final String code;
        private ExistingTarget(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }
}
