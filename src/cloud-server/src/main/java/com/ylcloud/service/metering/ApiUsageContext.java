package com.ylcloud.service.metering;

import com.ylcloud.Exception.BaseException;
import java.util.List;

/** Explicit scope; async workers restore this only from server-owned message columns. */
public final class ApiUsageContext implements AutoCloseable {
    private static final ThreadLocal<ApiUsageContext> CURRENT=new ThreadLocal<>();
    public final long userId,keyId;
    public final String requestId;
    public final Long messageId;
    private final ApiUsageContext previous;
    private List<Long> spaces=List.of();
    private boolean agent;
    private BaseException failure;
    private ApiUsageContext(long userId,long keyId,String requestId,Long messageId) {
        this.userId=userId; this.keyId=keyId; this.requestId=requestId; this.messageId=messageId;
        previous=CURRENT.get(); CURRENT.set(this);
    }
    public static ApiUsageContext open(long userId,long keyId,String requestId,Long messageId) {
        return new ApiUsageContext(userId,keyId,requestId,messageId);
    }
    public static ApiUsageContext current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
    public void authorize(List<Long> spaces,boolean agent) {
        if(spaces==null || spaces.isEmpty() || spaces.stream().anyMatch(id->id==null || id<=0))
            throw new BaseException(403,"无法核验模型调用的知识库授权范围");
        this.spaces=List.copyOf(spaces); this.agent=agent;
    }
    public List<Long> spaces() { return spaces; }
    public boolean agent() { return agent; }
    public BaseException fail(BaseException error) { if(failure==null) failure=error; return failure; }
    public void check() { if(failure!=null) throw failure; }
    @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
}
