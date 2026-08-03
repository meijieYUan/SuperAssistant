package com.itajay.superassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itajay.superassistant.entity.PlanTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlanTaskMapper extends BaseMapper<PlanTask> {

    @Select("""
        SELECT * FROM plan_task
        WHERE thread_id = #{threadId}
        ORDER BY id DESC
        LIMIT 1
        """)
    PlanTask findLatestByThreadId(@Param("threadId") String threadId);
}
