package com.ylcloud.service.metering;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.mapper.QuotaMapper;
import com.ylcloud.service.QuotaService;
import com.ylcloud.service.UserApiKeyService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApiTokenMeteringService {
    private final JdbcTemplate db;
    private final QuotaMapper quotas;
    private final QuotaService policies;
    private final UserApiKeyService keys;
    private final TransactionTemplate tx;
    public ApiTokenMeteringService(JdbcTemplate db,QuotaMapper quotas,QuotaService policies,
                                  UserApiKeyService keys,PlatformTransactionManager manager) {
        this.db=db; this.quotas=quotas; this.policies=policies; this.keys=keys;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }
    public String reserve(String operation,String model,long budget) {
        var context=Objects.requireNonNull(ApiUsageContext.current());
        context.check();
        try {
            if(budget<=0 || budget>4_000_000) throw new BaseException(413,"模型请求超出 Token 预留上限");
            var principal=keys.requireOwnedActivePrincipal(context.userId,context.keyId);
            if(context.spaces().isEmpty()) throw new BaseException(403,"模型调用缺少知识库授权上下文");
            context.spaces().forEach(space->keys.requireSpace(principal,space,context.agent()));
            return tx.execute(status->{
                LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
                LocalDate period=LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1);
                quotas.ensureUserAccount(context.userId,now);
                var account=quotas.getAccount("USER",context.userId);
                if(account==null) throw new BaseException(503,"配额账户不可用");
                // Serialize admission before checking pending calls. READ_COMMITTED observes the preceding
                // admission's commit even when another worker checked the same task concurrently.
                db.queryForObject("select account_id from quota_account where account_id=? for update",Long.class,account.getId());
                if(context.messageId!=null && db.queryForObject("select count(*) from api_key_token_usage where message_id=? and usage_status<>'SETTLED'",Long.class,context.messageId)>0)
                    throw new BaseException(503,"该任务存在待核对模型用量，请先核对流水");
                quotas.ensureUsage(account.getId(),period,now);
                Long configured=policies.policyForUser(context.userId).getMonthlyModelTokens();
                long limit=configured==null ? 0 : Math.max(0,configured);
                int changed=db.update("update quota_usage_period set model_tokens_reserved=model_tokens_reserved+?,updatetime=? where account_id=? and period_start=? and (?=0 or model_tokens+model_tokens_reserved+?<=?)",
                        budget,now,account.getId(),period,limit,budget,limit);
                if(changed!=1) throw new BaseException(429,"月度模型 Token 可用额度不足（含在途预留）");
                String id=UUID.randomUUID().toString();
                db.update("insert into api_key_token_usage(call_id,user_id,key_id,account_id,period_start,request_id,message_id,operation,model,usage_status,reserved_tokens,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?)",
                        id,context.userId,context.keyId,account.getId(),period,context.requestId,context.messageId,operation,safeModel(model),budget,now,now);
                return id;
            });
        } catch(BaseException failure) { throw context.fail(failure); }
        catch(RuntimeException failure) { throw context.fail(new BaseException(503,"Token 预留服务不可用")); }
    }
    public void settle(String callId,Integer prompt,Integer completion,Integer total) {
        settle(callId,null,prompt,completion,total);
    }
    public void settle(String callId,String model,Integer prompt,Integer completion,Integer total) {
        if(prompt==null || completion==null || total==null || prompt<0 || completion<0 || total<0 || (long)prompt+completion!=total) {
            unresolved(callId,"INVALID_OR_MISSING_USAGE");
            throw ApiUsageContext.current().fail(new BaseException(503,"模型未返回可信 usage，用量待核对"));
        }
        try { settleActual(callId,prompt,completion,total,null,null,model); }
        catch(RuntimeException failure) {
            throw ApiUsageContext.current().fail(new BaseException(503,"模型用量结算失败，已保留预留额度"));
        }
    }
    private void settleActual(String callId,long prompt,long completion,long total,Long operator,String note,String model) {
        tx.executeWithoutResult(status->{
            var rows=db.queryForList("select * from api_key_token_usage where call_id=? for update",callId);
            if(rows.isEmpty()) throw new BaseException(404,"用量流水不存在");
            var row=rows.get(0);
            if("SETTLED".equals(row.get("usage_status"))) {
                if(number(row,"prompt_tokens")!=prompt || number(row,"completion_tokens")!=completion || number(row,"total_tokens")!=total)
                    throw new BaseException(409,"流水已结算，用量不一致");
                return;
            }
            if(operator!=null && !"UNRESOLVED".equals(row.get("usage_status"))) throw new BaseException(409,"仅可核对未决流水");
            // Actual provider usage remains a fact even if it exceeded the conservative reservation.
            if(db.update("update quota_usage_period set model_tokens=model_tokens+?,model_tokens_reserved=model_tokens_reserved-?,updatetime=UTC_TIMESTAMP(6) where account_id=? and period_start=? and model_tokens_reserved>=?",
                    total,number(row,"reserved_tokens"),row.get("account_id"),row.get("period_start"),number(row,"reserved_tokens"))!=1)
                throw new BaseException(503,"用量账本不一致");
            db.update("update api_key_token_usage set usage_status='SETTLED',prompt_tokens=?,completion_tokens=?,total_tokens=?,resolved_by=?,resolution_note=?,model=coalesce(?,model),updated_at=UTC_TIMESTAMP(6) where call_id=?",
                    prompt,completion,total,operator,note,model==null ? null : safeModel(model),callId);
        });
    }
    public void unresolved(String callId,String code) {
        try { tx.executeWithoutResult(status->db.update("update api_key_token_usage set usage_status='UNRESOLVED',error_code=?,updated_at=UTC_TIMESTAMP(6) where call_id=? and usage_status='RESERVED'",code,callId)); }
        catch(RuntimeException ignored) { /* RESERVED is durable and still holds quota; scheduled reconciliation marks it later. */ }
    }
    @Scheduled(fixedDelay=300000)
    public void markAbandoned() {
        // No automatic refund: a crashed caller may already have incurred provider usage.
        db.update("update api_key_token_usage set usage_status='UNRESOLVED',error_code='CALLER_INTERRUPTED',updated_at=UTC_TIMESTAMP(6) where usage_status='RESERVED' and created_at<UTC_TIMESTAMP(6)-interval 1 day");
    }
    public List<Map<String,Object>> records(long userId,long keyId,int page,int size) {
        if(page<1 || page>100000 || size<1 || size>100) throw new BaseException("分页参数无效");
        return db.queryForList("select call_id,request_id,message_id,key_id,period_start,operation,model,usage_status,reserved_tokens,prompt_tokens,completion_tokens,total_tokens,error_code,created_at,updated_at from api_key_token_usage where user_id=? and key_id=? order by created_at desc,call_id desc limit ? offset ?",userId,keyId,size,(page-1)*size);
    }
    public List<Map<String,Object>> unresolvedRecords(int limit) {
        if(limit<1 || limit>100) throw new BaseException("limit 必须为 1-100");
        return db.queryForList("select * from api_key_token_usage where usage_status='UNRESOLVED' order by created_at,call_id limit ?",limit);
    }
    public void resolve(String callId,long prompt,long completion,String note,long operator) {
        if(prompt<0 || completion<0 || prompt>Integer.MAX_VALUE || completion>Integer.MAX_VALUE || note==null || note.isBlank() || note.length()>500)
            throw new BaseException("须提供非负实际用量及 1-500 字核对依据");
        settleActual(callId,prompt,completion,Math.addExact(prompt,completion),operator,note,null);
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static String safeModel(String model) { return model==null || model.isBlank() ? "default" : model.substring(0,Math.min(255,model.length())); }
}
