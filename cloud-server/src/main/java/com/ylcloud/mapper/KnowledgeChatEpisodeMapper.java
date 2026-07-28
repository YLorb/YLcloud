package com.ylcloud.mapper;

import com.ylcloud.VO.KnowledgeChatEpisodeVO;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeChatEpisodeMapper {
    @Insert("insert into knowledge_chat_episode(session_id,user_id,episode_no,start_sequence_no,end_sequence_no,title,summary,message_count,status,createtime,updatetime) " +
            "values(#{sessionId},#{userId},#{episodeNo},#{start},#{end},#{title},#{summary},#{count},1,#{now},#{now}) " +
            "on duplicate key update start_sequence_no=values(start_sequence_no),end_sequence_no=values(end_sequence_no),title=values(title),summary=values(summary),message_count=values(message_count),updatetime=values(updatetime)")
    int upsert(@Param("sessionId") Long sessionId,@Param("userId") Long userId,@Param("episodeNo") int episodeNo,
               @Param("start") Long start,@Param("end") Long end,@Param("title") String title,@Param("summary") String summary,
               @Param("count") int count,@Param("now") LocalDateTime now);

    @Select("select id,episode_no as episodeNo,start_sequence_no as startSequenceNo,end_sequence_no as endSequenceNo,title,summary,message_count as messageCount,updatetime " +
            "from knowledge_chat_episode where session_id=#{sessionId} and user_id=#{userId} and status=1 order by episode_no")
    List<KnowledgeChatEpisodeVO> list(@Param("sessionId") Long sessionId,@Param("userId") Long userId);

    @Update("update knowledge_chat_episode set status=0,updatetime=#{now} where session_id=#{sessionId} and user_id=#{userId}")
    int disable(@Param("sessionId") Long sessionId,@Param("userId") Long userId,@Param("now") LocalDateTime now);
}
