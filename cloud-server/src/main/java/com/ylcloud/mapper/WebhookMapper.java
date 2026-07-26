package com.ylcloud.mapper;

import com.ylcloud.entity.WebhookDelivery;
import com.ylcloud.entity.WebhookEvent;
import com.ylcloud.entity.WebhookSubscription;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WebhookMapper {
    String SUBSCRIPTION_COLUMNS = "subscription_id as id,user_id as userId,api_key_id as apiKeyId," +
            "subscription_name as name,target_url as targetUrl,event_types as eventTypes," +
            "include_content as includeContent,subscription_status as status,secret_cipher as secretCipher," +
            "previous_secret_cipher as previousSecretCipher,previous_secret_valid_until as previousSecretValidUntil," +
            "last_delivery_at as lastDeliveryAt,createtime as createTime,updatetime as updateTime";
    String EVENT_COLUMNS = "event_id as eventId,event_key as eventKey,user_id as userId,event_type as eventType," +
            "resource_type as resourceType,resource_id as resourceId,resource_version as resourceVersion," +
            "file_id as fileId,space_id as spaceId,minimal_payload_json as minimalPayloadJson," +
            "content_payload_json as contentPayloadJson,occurred_at as occurredAt,createtime as createTime";
    String DELIVERY_COLUMNS = "delivery_id as id,event_id as eventId,subscription_id as subscriptionId," +
            "delivery_status as status,attempt_count as attemptCount,next_attempt_at as nextAttemptAt," +
            "lease_token as leaseToken,lease_until as leaseUntil,response_status as responseStatus," +
            "last_error as lastError,delivered_at as deliveredAt,createtime as createTime,updatetime as updateTime";

    @Insert("insert into webhook_subscription(user_id,api_key_id,subscription_name,target_url,event_types," +
            "include_content,subscription_status,secret_cipher,createtime,updatetime) values(" +
            "#{userId},#{apiKeyId},#{name},#{targetUrl},#{eventTypes},#{includeContent},#{status},#{secretCipher}," +
            "#{createTime},#{updateTime})")
    @Options(useGeneratedKeys = true,keyProperty = "id",keyColumn = "subscription_id")
    int insertSubscription(WebhookSubscription value);

    @Select("select " + SUBSCRIPTION_COLUMNS + " from webhook_subscription where subscription_id=#{id} and user_id=#{userId}")
    WebhookSubscription getOwnedSubscription(@Param("id") Long id,@Param("userId") Long userId);

    @Select("select " + SUBSCRIPTION_COLUMNS + " from webhook_subscription where subscription_id=#{id}")
    WebhookSubscription getSubscription(@Param("id") Long id);

    @Select("select " + SUBSCRIPTION_COLUMNS + " from webhook_subscription where user_id=#{userId} order by subscription_id desc")
    List<WebhookSubscription> listOwnedSubscriptions(@Param("userId") Long userId);

    @Select("select " + SUBSCRIPTION_COLUMNS + " from webhook_subscription where user_id=#{userId} and subscription_status='ACTIVE'")
    List<WebhookSubscription> listActiveSubscriptions(@Param("userId") Long userId);

    @Update("update webhook_subscription set subscription_status='DISABLED',updatetime=#{now} " +
            "where subscription_id=#{id} and user_id=#{userId} and subscription_status='ACTIVE'")
    int disableSubscription(@Param("id") Long id,@Param("userId") Long userId,@Param("now") LocalDateTime now);

    @Update("update webhook_subscription set previous_secret_cipher=secret_cipher," +
            "previous_secret_valid_until=#{previousUntil},secret_cipher=#{secretCipher},updatetime=#{now} " +
            "where subscription_id=#{id} and user_id=#{userId} and subscription_status='ACTIVE'")
    int rotateSecret(@Param("id") Long id,@Param("userId") Long userId,@Param("secretCipher") String secretCipher,
                     @Param("previousUntil") LocalDateTime previousUntil,@Param("now") LocalDateTime now);

    @Insert("insert ignore into webhook_event(event_id,event_key,user_id,event_type,resource_type,resource_id," +
            "resource_version,file_id,space_id,minimal_payload_json,content_payload_json,occurred_at,createtime) values(" +
            "#{eventId},#{eventKey},#{userId},#{eventType},#{resourceType},#{resourceId},#{resourceVersion}," +
            "#{fileId},#{spaceId},#{minimalPayloadJson},#{contentPayloadJson},#{occurredAt},#{createTime})")
    int insertEvent(WebhookEvent event);

    @Select("select " + EVENT_COLUMNS + " from webhook_event where event_key=#{eventKey}")
    WebhookEvent getEventByKey(@Param("eventKey") String eventKey);

    @Select("select " + EVENT_COLUMNS + " from webhook_event where event_id=#{eventId}")
    WebhookEvent getEvent(@Param("eventId") String eventId);

    @Insert("insert ignore into webhook_delivery(event_id,subscription_id,delivery_status,attempt_count," +
            "next_attempt_at,createtime,updatetime) values(#{eventId},#{subscriptionId},'PENDING',0,#{now},#{now},#{now})")
    int insertDelivery(@Param("eventId") String eventId,@Param("subscriptionId") Long subscriptionId,
                       @Param("now") LocalDateTime now);

    @Select("select delivery_id from webhook_delivery where (delivery_status='PENDING' or " +
            "(delivery_status='DELIVERING' and lease_until<#{now})) and next_attempt_at<=#{now} " +
            "order by next_attempt_at,delivery_id limit #{limit}")
    List<Long> listClaimableDeliveries(@Param("now") LocalDateTime now,@Param("limit") int limit);

    @Update("update webhook_delivery set delivery_status='DELIVERING',lease_token=#{token},lease_until=#{until}," +
            "attempt_count=attempt_count+1,updatetime=#{now} where delivery_id=#{id} and " +
            "(delivery_status='PENDING' or (delivery_status='DELIVERING' and lease_until<#{now}))")
    int claimDelivery(@Param("id") Long id,@Param("token") String token,@Param("until") LocalDateTime until,
                      @Param("now") LocalDateTime now);

    @Select("select " + DELIVERY_COLUMNS + " from webhook_delivery where delivery_id=#{id} " +
            "and delivery_status='DELIVERING' and lease_token=#{token}")
    WebhookDelivery getClaimedDelivery(@Param("id") Long id,@Param("token") String token);

    @Update("update webhook_delivery set delivery_status='SUCCEEDED',response_status=#{status},delivered_at=#{now}," +
            "lease_token=null,lease_until=null,last_error=null,updatetime=#{now} where delivery_id=#{id} and lease_token=#{token}")
    int markDelivered(@Param("id") Long id,@Param("token") String token,@Param("status") int status,
                      @Param("now") LocalDateTime now);

    @Update("update webhook_subscription set last_delivery_at=#{now},updatetime=#{now} where subscription_id=#{id}")
    int touchSubscription(@Param("id") Long id,@Param("now") LocalDateTime now);

    @Update("update webhook_delivery set delivery_status='PENDING',next_attempt_at=#{nextAt},response_status=#{status}," +
            "last_error=#{error},lease_token=null,lease_until=null,updatetime=#{now} " +
            "where delivery_id=#{id} and lease_token=#{token}")
    int releaseDelivery(@Param("id") Long id,@Param("token") String token,@Param("nextAt") LocalDateTime nextAt,
                        @Param("status") Integer status,@Param("error") String error,@Param("now") LocalDateTime now);

    @Update("update webhook_delivery set delivery_status='DEAD',response_status=#{status},last_error=#{error}," +
            "lease_token=null,lease_until=null,updatetime=#{now} where delivery_id=#{id} and lease_token=#{token}")
    int markDead(@Param("id") Long id,@Param("token") String token,@Param("status") Integer status,
                 @Param("error") String error,@Param("now") LocalDateTime now);
}
