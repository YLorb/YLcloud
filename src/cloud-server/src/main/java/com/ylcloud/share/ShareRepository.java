package com.ylcloud.share;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.ylcloud.share.ShareModels.*;

@Repository
@RequiredArgsConstructor
public class ShareRepository {
    private final JdbcTemplate db;
    private final RowMapper<Link> links = BeanPropertyRowMapper.newInstance(Link.class);
    private final RowMapper<Node> nodes = BeanPropertyRowMapper.newInstance(Node.class);
    public static String table(String type) {
        return switch(type) { case "PERSONAL" -> "user_file"; case "SPACE" -> "space_file";
            default -> throw new IllegalArgumentException("分享对象类型无效"); };
    }
    private String nodeSelect(String type) {
        String owner = "PERSONAL".equals(type) ? "n.user_id" : "n.created_by";
        String space = "PERSONAL".equals(type) ? "NULL" : "n.space_id";
        String lifecycle = "PERSONAL".equals(type) ? "'ACTIVE'" : "n.lifecycle_state";
        return "SELECT n.id,n.file_name name,n.file_uuid uuid,n.is_dir dir,n.status,n.parent_id," +
                owner+" owner_id,"+space+" space_id,"+lifecycle+" lifecycle_state,"+
                "(n.share_revision+COALESCE(f.share_revision,0)) revision,"+
                "GREATEST(COALESCE(n.share_replaced_at,'1000-01-01'),COALESCE(f.share_replaced_at,'1000-01-01')) replaced_at,"+
                "f.size FROM "+table(type)+" n LEFT JOIN file_info f ON f.file_uuid=n.file_uuid ";
    }
    public Node node(String type,long id) {
        return db.query(nodeSelect(type)+" WHERE n.id=? FOR SHARE",nodes,id).stream().findFirst().orElse(null);
    }
    public void lockNode(String type,long id) {
        if(db.queryForList("SELECT id FROM "+table(type)+" WHERE id=? FOR UPDATE",id).isEmpty()) throw new Expired();
    }
    public List<Node> children(String type,long parentId,long afterId) {
        return db.query(nodeSelect(type)+" WHERE n.parent_id=? AND n.id>? ORDER BY n.id LIMIT 200 FOR SHARE",nodes,parentId,afterId);
    }
    public boolean physicalExists(String uuid) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM file_info WHERE file_uuid=? AND status=1",Boolean.class,uuid));
    }
    public Link byId(long id,boolean lock) {
        return db.query("SELECT * FROM share_link WHERE id=?"+(lock?" FOR UPDATE":""),links,id).stream().findFirst().orElse(null);
    }
    public Link byCode(String code) {
        return db.query("SELECT * FROM share_link WHERE short_code=?",links,code).stream().findFirst().orElse(null);
    }
    public List<Link> reusable(long creator,String type,long source) {
        return db.query("SELECT * FROM share_link WHERE creator_id=? AND source_type=? AND source_id=? AND revoked_at IS NULL ORDER BY id DESC",
                links,creator,type,source);
    }
    public long insert(Link l) {
        KeyHolder key = new GeneratedKeyHolder();
        db.update(c -> {
            PreparedStatement s = c.prepareStatement("INSERT INTO share_link(creator_id,source_type,source_id,space_id,file_uuid,content_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            s.setLong(1,l.getCreatorId());s.setString(2,l.getSourceType());s.setLong(3,l.getSourceId());
            s.setObject(4,l.getSpaceId());s.setString(5,l.getFileUuid());s.setLong(6,l.getContentRevision());
            s.setObject(7,l.getCreatedAt());s.setObject(8,l.getCreatedAt()); return s;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void save(Link l) {
        db.update("UPDATE share_link SET short_code=?,expires_at=?,max_downloads=?,password_enabled=?,password_hash=?,force_download=?,revoked_at=?,updated_at=?,version=? WHERE id=?",
                l.getShortCode(),l.getExpiresAt(),l.getMaxDownloads(),l.isPasswordEnabled(),l.getPasswordHash(),l.isForceDownload(),
                l.getRevokedAt(),l.getUpdatedAt(),l.getVersion(),l.getId());
    }
    public boolean approve(long id,LocalDateTime now) {
        return db.update("UPDATE share_link SET download_count=download_count+1 WHERE id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>?) AND (max_downloads IS NULL OR download_count<max_downloads) AND download_count<9223372036854775807",id,now)==1;
    }
    public List<Link> list(Long creator,int offset,int size) {
        return creator==null ? db.query("SELECT * FROM share_link ORDER BY id DESC LIMIT ? OFFSET ?",links,size,offset)
                : db.query("SELECT * FROM share_link WHERE creator_id=? ORDER BY id DESC LIMIT ? OFFSET ?",links,creator,size,offset);
    }
    public long count(Long creator) {
        return creator==null?db.queryForObject("SELECT COUNT(*) FROM share_link",Long.class):
                db.queryForObject("SELECT COUNT(*) FROM share_link WHERE creator_id=?",Long.class,creator);
    }
    public long visit(Link link,String code,Long user,String ip,String outcome,LocalDateTime now) {
        KeyHolder key=new GeneratedKeyHolder();
        db.update(c->{
            PreparedStatement s=c.prepareStatement("INSERT INTO share_link_visit(link_id,requested_code,user_id,ip,visited_at,outcome) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            s.setObject(1,link==null?null:link.getId());s.setString(2,code);s.setObject(3,user);
            s.setString(4,user==null?ip:null);s.setObject(5,now);s.setString(6,outcome);return s;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public void visitOutcome(long id,String outcome) {
        db.update("UPDATE share_link_visit SET outcome=? WHERE id=?",outcome,id);
    }
    public Page<Map<String,Object>> visits(Long id,int page,int size) {
        String filter=id==null?"":" WHERE v.link_id=?";
        List<Object> args=new ArrayList<>();if(id!=null)args.add(id);
        long total=db.queryForObject("SELECT COUNT(*) FROM share_link_visit v"+filter,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=db.queryForList("SELECT v.id,v.link_id linkId,v.requested_code shortCode,v.user_id userId,u.username,v.ip,v.city,v.visited_at visitedAt,v.outcome FROM share_link_visit v LEFT JOIN users u ON u.user_id=v.user_id"+filter+" ORDER BY v.id DESC LIMIT ? OFFSET ?",args.toArray());
        return new Page<>(rows,total,page,size);
    }
    public int cleanup(LocalDateTime cutoff) {
        return db.update("DELETE FROM share_link_visit WHERE visited_at<? ORDER BY visited_at LIMIT 1000",cutoff);
    }
}
