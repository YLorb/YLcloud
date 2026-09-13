package com.ylcloud.mapper;

import com.ylcloud.entity.AccountRecoveryLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AccountRecoveryLogMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into account_recovery_log(user_id, recovered_by, previous_status, recovery_reason, created_at) " +
            "values(#{userId}, #{recoveredBy}, #{previousStatus}, #{recoveryReason}, #{createdAt})")
    int insert(AccountRecoveryLog log);

    @Select("select id, user_id as userId, recovered_by as recoveredBy, previous_status as previousStatus, " +
            "recovery_reason as recoveryReason, created_at as createdAt " +
            "from account_recovery_log where user_id = #{userId} order by created_at desc")
    List<AccountRecoveryLog> listByUserId(@Param("userId") Long userId);
}
