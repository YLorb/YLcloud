package com.ylcloud.share;

import com.ylcloud.Exception.*;
import com.ylcloud.authorization.*;
import com.ylcloud.entity.User;
import com.ylcloud.mapper.LoginMapper;
import com.ylcloud.service.AuthorizationService;
import com.ylcloud.service.AccessControlService;
import com.ylcloud.constant.UserPermissionKeys;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.utils.MinioclientUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.ylcloud.share.ShareModels.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareLinkService {
    private final ShareRepository repo;
    private final ShareCrypto crypto;
    private final TransactionTemplate tx;
    private final AuthorizationService authorization;
    private final SpaceFileAccessService spaceAccess;
    private final LoginMapper users;
    private final AccessControlService access;
    private final MinioclientUtil storage;
    private final JdbcTemplate db;
    static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    public View create(long user, Create request) {
        if(request==null || request.sourceType()==null || request.sourceId()==null || request.sourceId()<1) throw new IllegalArgumentException("请选择文件");
        ShareRepository.table(request.sourceType());
        try {
            return tx.execute(status -> {
                repo.lockNode(request.sourceType(),request.sourceId());
                Node node=repo.node(request.sourceType(),request.sourceId());
                if(!Objects.equals(request.spaceId(),node.getSpaceId())) throw new IllegalArgumentException("空间与文件不匹配");
                requireNode(request.sourceType(),node,user,true);
                Link link=repo.reusable(user,request.sourceType(),node.getId()).stream()
                        .filter(l->valid(l,node)).findFirst().orElse(null);
                if(link==null) {
                    link=new Link();link.setCreatorId(user);link.setSourceId(node.getId());
                    link.setSourceType(request.sourceType());link.setSpaceId(node.getSpaceId());
                    link.setFileUuid(node.getUuid());link.setContentRevision(node.getRevision());
                    link.setCreatedAt(now());link.setVersion(0L);link.setDownloadCount(0L);
                    link.setId(repo.insert(link));link.setShortCode(ShareCrypto.code(link.getId(),user));
                } else link=repo.byId(link.getId(),true);
                apply(link,request.conditions());
                repo.save(link);return view(link,node);
            });
        } catch(DuplicateKeyException ex) { throw new IllegalArgumentException("短链接冲突，创建失败"); }
    }
    public View update(long user,long id,Conditions conditions) {
        return tx.execute(status->{
            Link first=repo.byId(id,false);requireManager(user,first);
            // Keep source -> link lock ordering consistent with create and approve.
            Node node=repo.node(first.getSourceType(),first.getSourceId());
            Link link=repo.byId(id,true);
            if(conditions.version()==null || !conditions.version().equals(link.getVersion()))
                throw new IllegalArgumentException("链接已更新，请刷新后重试");
            apply(link,conditions);repo.save(link);
            return view(link,node);
        });
    }
    public void revoke(long user,long id) {
        tx.executeWithoutResult(status->{
            Link l=repo.byId(id,true);requireManager(user,l);
            if(l.getRevokedAt()==null){l.setRevokedAt(now());l.setUpdatedAt(now());l.setVersion(l.getVersion()+1);repo.save(l);}
        });
    }
    private void requireManager(long user,Link l) {
        if(l==null)throw new NotFoundException("链接不存在");
        User u=users.getById(user);
        if(!Objects.equals(l.getCreatorId(),user) && (u==null || !"ADMIN".equalsIgnoreCase(u.getRole())))
            throw new ForbiddenException("只有创建者或管理员可以管理链接");
    }
    private void apply(Link l,Conditions c) {
        if(c==null)throw new IllegalArgumentException("请设置分享条件");
        if(c.passwordEnabled() && c.forceDownload())throw new IllegalArgumentException("密码保护与强制下载不能同时开启");
        if(c.maxDownloads()!=null && c.maxDownloads()<1)throw new IllegalArgumentException("下载次数必须为正整数");
        String mode=c.expiryMode()==null?"KEEP":c.expiryMode();
        switch(mode) {
            case "PERMANENT" -> l.setExpiresAt(null);
            case "SET" -> {
                if(c.expiresAt()==null || !c.expiresAt().toInstant().isAfter(Instant.now()))
                    throw new IllegalArgumentException("到期时间必须晚于当前时间");
                l.setExpiresAt(LocalDateTime.ofInstant(c.expiresAt().toInstant(),ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            }
            case "EXTEND" -> {
                if(c.days()==null || c.days()<1 || c.days()>36500)throw new IllegalArgumentException("有效天数应为 1–36500");
                LocalDateTime from=l.getExpiresAt()==null || l.getExpiresAt().isBefore(now())?now():l.getExpiresAt();
                l.setExpiresAt(from.plusDays(c.days()));
            }
            case "KEEP" -> {}
            default -> throw new IllegalArgumentException("有效期模式无效");
        }
        if(c.passwordEnabled()) {
            if(c.password()!=null && !c.password().isEmpty()) l.setPasswordHash(crypto.hash(c.password()));
            else if(!l.isPasswordEnabled() || l.getPasswordHash()==null) throw new IllegalArgumentException("开启密码保护时密码不能为空");
        } else l.setPasswordHash(null);
        l.setPasswordEnabled(c.passwordEnabled());l.setForceDownload(c.forceDownload());
        l.setMaxDownloads(c.maxDownloads());l.setUpdatedAt(now());l.setVersion(l.getVersion()+1);
    }
    private boolean valid(Link l,Node node) {
        if(l==null || l.getRevokedAt()!=null || (l.getExpiresAt()!=null && !l.getExpiresAt().isAfter(now())) ||
           (l.getMaxDownloads()!=null && l.getDownloadCount()>=l.getMaxDownloads()) ||
           node==null || !Objects.equals(node.getRevision(),l.getContentRevision()) ||
           !Objects.equals(node.getUuid(),l.getFileUuid()) || !Objects.equals(node.getSpaceId(),l.getSpaceId()))return false;
        try { requireNode(l.getSourceType(),node,l.getCreatorId(),true);return true; }
        catch(ForbiddenException | NotFoundException | Expired ex){return false;}
    }
    private void requireNode(String type,Node n,long user,boolean edit) {
        User u=users.getById(user);
        if(u==null || !Integer.valueOf(1).equals(u.getStatus()) ||
                (u.getAccountStatus()!=null && !"ACTIVE".equals(u.getAccountStatus())))throw new Expired();
        access.require(user,UserPermissionKeys.CLOUD_DRIVE);
        if(n==null || n.getStatus()!=1 || !"ACTIVE".equals(n.getLifecycleState()))throw new Expired();
        if("SPACE".equals(type)) spaceAccess.requireNodeAction(n.getSpaceId(),n.getId(),user,
                edit?SpaceFileAction.SHARE:SpaceFileAction.READ);
        else authorization.require(AccessSubject.user(user),ResourceType.USER_PRIVATE,n.getOwnerId(),
                edit?ResourceAction.WRITE:ResourceAction.READ);
        Node parent=n;Set<Long> seen=new HashSet<>();
        while(parent.getParentId()!=null && parent.getParentId()>0) {
            if(!seen.add(parent.getId()))throw new Expired();
            Node next=repo.node(type,parent.getParentId());
            if(next==null || next.getStatus()!=1 || !"ACTIVE".equals(next.getLifecycleState()) ||
               !Objects.equals(next.getSpaceId(),n.getSpaceId()) ||
               ("PERSONAL".equals(type) && !Objects.equals(next.getOwnerId(),n.getOwnerId())))throw new Expired();
            parent=next;
        }
        if(n.getDir()==0 && !repo.physicalExists(n.getUuid()))throw new Expired();
    }
    private View view(Link l,Node n) {
        return new View(l.getId(),l.getShortCode(),l.getSourceType(),l.getSourceId(),l.getSpaceId(),l.getCreatorId(),
                n==null?"文件已删除":n.getName(),n!=null&&n.getDir()==1,
                l.getExpiresAt()==null?null:l.getExpiresAt().atOffset(ZoneOffset.UTC).toString(),
                l.getMaxDownloads(),l.getDownloadCount(),l.isPasswordEnabled(),l.isForceDownload(),
                valid(l,n)?"ACTIVE":"EXPIRED",l.getVersion());
    }
    public Page<View> list(Long creator,int page,int size) {
        checkPage(page,size);
        return new Page<>(repo.list(creator,(page-1)*size,size).stream()
                .map(l->view(l,repo.node(l.getSourceType(),l.getSourceId()))).toList(),repo.count(creator),page,size);
    }
    public static void checkPage(int page,int size) {
        if(page<1 || page>1_000_000 || size<1 || size>100)throw new IllegalArgumentException("分页参数无效");
    }
    public Open open(String rawCode,Long user,String ip) {
        String code=rawCode.replaceAll("[^a-zA-Z0-9]","");if(code.length()>64)code=code.substring(0,64);
        Link l=rawCode.matches("[a-zA-Z0-9]{8}")?repo.byCode(rawCode):null;
        Node n=l==null?null:repo.node(l.getSourceType(),l.getSourceId());
        boolean active=valid(l,n);
        String state=!active?"EXPIRED":l.isPasswordEnabled()?"PASSWORD_REQUIRED":"READY";
        long visit=repo.visit(l,code,user,ip,state,now());
        if(!active)return expired();
        String visitToken=crypto.token(code,visit,l.getVersion(),"visit");
        if(l.isPasswordEnabled())return new Open(state,null,null,false,null,false,visitToken,null);
        return ready(l,n,visit,visitToken);
    }
    public Open verify(String code,Verify request) {
        Link l=repo.byCode(code);Node n=l==null?null:repo.node(l.getSourceType(),l.getSourceId());
        if(!valid(l,n))return expired();
        long visit=((Number)crypto.verify(request.visitToken(),code,l.getVersion(),"visit").get("visit")).longValue();
        if(!crypto.matches(request.password(),l.getPasswordHash())) {
            repo.visitOutcome(visit,"PASSWORD_FAILED");
            return new Open("PASSWORD_REQUIRED","密码错误",null,false,null,false,request.visitToken(),null);
        }
        repo.visitOutcome(visit,"VERIFIED");
        return ready(l,n,visit,request.visitToken());
    }
    private Open ready(Link l,Node n,long visit,String token) {
        return new Open("READY",null,n.getName(),n.getDir()==1,n.getSize(),l.isForceDownload(),token,
                crypto.token(l.getShortCode(),visit,l.getVersion(),"download"));
    }
    public static Open expired() {return new Open("EXPIRED","文件已过期",null,false,null,false,null,null);}

    public record Download(String name,boolean directory,Path manifest) implements AutoCloseable {
        public void close() throws IOException { Files.deleteIfExists(manifest); }
    }
    public Download approve(String code,String credential) throws IOException {
        Path manifest=Files.createTempFile("ylcloud-share-", ".manifest");
        try {
            return tx.execute(status -> {
                Link first=repo.byCode(code);if(first==null)throw new Expired();
                repo.lockNode(first.getSourceType(),first.getSourceId());
                Link l=repo.byId(first.getId(),true);
                Node n=repo.node(l.getSourceType(),l.getSourceId());
                if(!valid(l,n))throw new Expired();
                crypto.verify(credential,code,l.getVersion(),"download");
                try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(manifest)))) {
                    if(n.getDir()==1) manifest(l,n,"",out,new HashSet<>(),0);
                    else entry(out,n.getName(),n);
                } catch(IOException e){throw new UncheckedIOException(e);}
                if(!repo.approve(l.getId(),now()))throw new Expired();
                return new Download(n.getName(),n.getDir()==1,manifest);
            });
        } catch(RuntimeException e) {Files.deleteIfExists(manifest);throw e;}
    }
    private void manifest(Link l,Node parent,String prefix,DataOutputStream out,Set<Long> ancestors,int depth) throws IOException {
        if(depth>512 || !ancestors.add(parent.getId()))throw new IllegalArgumentException("文件夹层级无效");
        long after=0;Set<String> names=new HashSet<>();
        while(true) {
            List<Node> batch=repo.children(l.getSourceType(),parent.getId(),after);
            if(batch.isEmpty())break;
            for(Node child:batch) {
                after=child.getId();
                if(!Objects.equals(child.getSpaceId(),parent.getSpaceId()) ||
                        ("PERSONAL".equals(l.getSourceType())&&!Objects.equals(child.getOwnerId(),parent.getOwnerId())))continue;
                try {requireNode(l.getSourceType(),child,l.getCreatorId(),false);}
                catch(ForbiddenException | NotFoundException | Expired e){continue;}
                if(child.getDir()==0 && child.getReplacedAt()!=null && !child.getReplacedAt().isBefore(l.getCreatedAt()))continue;
                String name=safeName(child.getName());
                String original=name;
                for(int suffix=1; !names.add(name); suffix++)name=child.getId()+"-"+suffix+"-"+original;
                String path=prefix+name;
                if(child.getDir()==1) {
                    out.writeUTF(path+"/");out.writeUTF("");out.writeUTF("");
                    manifest(l,child,path+"/",out,ancestors,depth+1);
                } else entry(out,path,child);
            }
        }
        ancestors.remove(parent.getId());
    }
    static String safeName(String name) {
        String s=name.replaceAll("[\\\\/:\\p{Cntrl}]","_");
        return s.isBlank()||s.equals(".")||s.equals("..")?"file":s;
    }
    private void entry(DataOutputStream out,String name,Node n) throws IOException {
        var versions=db.queryForList("SELECT minio_version_id FROM file_version WHERE file_uuid=? AND is_current=1 AND status=1 ORDER BY id DESC LIMIT 1 FOR SHARE",String.class,n.getUuid());
        String version;
        try { version=versions.isEmpty()?storage.getCurrentObjectVersionId(n.getUuid()):versions.get(0); }
        catch(Exception e){throw new IOException("无法读取文件版本",e);}
        out.writeUTF(name);out.writeUTF(n.getUuid());out.writeUTF(version==null?"":version);
    }
    public void transfer(Download d,OutputStream target) throws Exception {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(d.manifest())))) {
            if(d.directory()) {
                try (java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(target)) {
                while(in.available()>0) {
                    String path=in.readUTF(),uuid=in.readUTF(),version=in.readUTF();
                    zip.putNextEntry(new java.util.zip.ZipEntry(path));
                    if(!uuid.isEmpty())try(InputStream data=storage.getObjectStream(uuid,version.isEmpty()?null:version)){data.transferTo(zip);}
                    zip.closeEntry();
                }
                zip.finish();zip.flush();
                }
            } else {
                in.readUTF();String uuid=in.readUTF(),version=in.readUTF();
                try(InputStream data=storage.getObjectStream(uuid,version.isEmpty()?null:version)){data.transferTo(target);}
            }
        }
    }
    @Scheduled(cron="0 20 3 * * *",zone="UTC")
    public void cleanup() {
        LocalDateTime cutoff=now().minusDays(30);int removed=0,batch;
        do {batch=repo.cleanup(cutoff);removed+=batch;}while(batch==1000);
        log.info("share visit cleanup removed={}",removed);
    }
}
