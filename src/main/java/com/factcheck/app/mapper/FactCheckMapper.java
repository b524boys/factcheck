package com.factcheck.app.mapper;

import com.factcheck.app.entity.FactCheckRecord;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FactCheckMapper {
    
    @Insert("INSERT INTO fact_check_records (claim, media_file_name, direct_verify, api_key, cse_id, task_id, status, create_time, update_time) " +
            "VALUES (#{claim}, #{mediaFileName}, #{directVerify}, #{apiKey}, #{cseId}, #{taskId}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FactCheckRecord record);
    
    @Select("SELECT * FROM fact_check_records WHERE task_id = #{taskId}")
    FactCheckRecord findByTaskId(String taskId);
    
    @Update("UPDATE fact_check_records SET status = #{status}, result = #{result}, error_message = #{errorMessage}, update_time = NOW() WHERE task_id = #{taskId}")
    int updateByTaskId(@Param("taskId") String taskId, @Param("status") String status, 
                       @Param("result") String result, @Param("errorMessage") String errorMessage);
}
