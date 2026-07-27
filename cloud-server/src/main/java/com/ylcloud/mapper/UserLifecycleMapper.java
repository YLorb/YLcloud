package com.ylcloud.mapper;

import com.ylcloud.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserLifecycleMapper {

    @Select("select user_id as id, username, password, nickname, root_id as rootID, email, avatar, status, role, " +
            "deployment_owner as deploymentOwner, account_status as accountStatus, cancelled_at as cancelledAt, " +
            "cancel_requested_by as cancelRequestedBy, recoverable_until as recoverableUntil, " +
            "purging_started_at as purgingStartedAt, purged_at as purgedAt, " +
            "create_time as createTime, update_time as updateTime " +
            "from users where user_id = #{userId} for update")
    User lockById(@Param("userId") Long userId);

    @Select("select user_id as id, username, account_status as accountStatus, cancelled_at as cancelledAt, " +
            "recoverable_until as recoverableUntil, purging_started_at as purgingStartedAt, purged_at as purgedAt " +
            "from users where user_id = #{userId}")
    User getAccountStatus(@Param("userId") Long userId);

    @Update("update users set account_status = 'CANCELLED', cancelled_at = #{cancelledAt}, " +
            "cancel_requested_by = #{requestedBy}, recoverable_until = #{recoverableUntil}, " +
            "status = 0, update_time = #{updateTime} " +
            "where user_id = #{userId} and account_status = 'ACTIVE'")
    int markCancelled(@Param("userId") Long userId,
                      @Param("cancelledAt") LocalDateTime cancelledAt,
                      @Param("requestedBy") Long requestedBy,
                      @Param("recoverableUntil") LocalDateTime recoverableUntil,
                      @Param("updateTime") LocalDateTime updateTime);

    @Update("update users set account_status = 'ACTIVE', cancelled_at = null, cancel_requested_by = null, " +
            "recoverable_until = null, status = 1, update_time = #{updateTime} " +
            "where user_id = #{userId} and account_status = 'CANCELLED'")
    int recoverFromCancelled(@Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    @Update("update users set account_status = 'PURGING', purging_started_at = #{purgingStartedAt}, " +
            "recoverable_until = null, update_time = #{updateTime} " +
            "where user_id = #{userId} and account_status = 'CANCELLED'")
    int markPurging(@Param("userId") Long userId,
                    @Param("purgingStartedAt") LocalDateTime purgingStartedAt,
                    @Param("updateTime") LocalDateTime updateTime);

    @Update("update users set account_status = 'PURGED', purged_at = #{purgedAt}, update_time = #{updateTime} " +
            "where user_id = #{userId} and account_status = 'PURGING'")
    int markPurged(@Param("userId") Long userId,
                   @Param("purgedAt") LocalDateTime purgedAt,
                   @Param("updateTime") LocalDateTime updateTime);

    @Select("select user_id from users where account_status = 'CANCELLED' and recoverable_until < #{now} " +
            "order by recoverable_until asc limit #{limit}")
    List<Long> listExpiredCancelledUsers(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("select count(1) from spaces s join space_member sm on s.id = sm.space_id " +
            "where sm.user_id = #{userId} and sm.role = 'OWNER' and sm.status = 1 " +
            "and s.status = 1 and s.type = 'TEAM' and s.lifecycle_state = 'ACTIVE'")
    int countOwnedTeams(@Param("userId") Long userId);

    @Select("select s.id from spaces s join space_member sm on s.id = sm.space_id " +
            "where sm.user_id = #{userId} and sm.role = 'OWNER' and sm.status = 1 " +
            "and s.status = 1 and s.type = 'TEAM' and s.lifecycle_state = 'ACTIVE'")
    List<Long> listOwnedTeamIds(@Param("userId") Long userId);

    @Select("select count(1) from space_member where user_id = #{userId} and status = 1")
    int countSpaceMemberships(@Param("userId") Long userId);
}
