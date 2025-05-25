package com.factcheck.app.mapper;

import com.factcheck.app.entity.FactCheckRecord;

import java.util.List;
import java.util.Map;

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

@Select("<script>" +
        "SELECT * FROM fact_check_records " +
        "WHERE 1=1 " +
        "<if test='status != null'>AND status = #{status}</if> " +
        "<if test='search != null'>AND (claim LIKE #{search} OR task_id LIKE #{search})</if> " +
        "ORDER BY " +
        "<choose>" +
        "<when test='sortBy == \"createTime\"'>create_time</when>" +
        "<when test='sortBy == \"updateTime\"'>update_time</when>" +
        "<when test='sortBy == \"status\"'>status</when>" +
        "<when test='sortBy == \"claim\"'>claim</when>" +
        "<otherwise>create_time</otherwise>" +
        "</choose> " +
        "<choose>" +
        "<when test='sortOrder == \"asc\"'>ASC</when>" +
        "<otherwise>DESC</otherwise>" +
        "</choose> " +
        "LIMIT #{limit} OFFSET #{offset}" +
        "</script>")
List<FactCheckRecord> findRecordsWithPagination(Map<String, Object> params);

@Select("<script>" +
        "SELECT COUNT(*) FROM fact_check_records " +
        "WHERE 1=1 " +
        "<if test='status != null'>AND status = #{status}</if> " +
        "<if test='search != null'>AND (claim LIKE #{search} OR task_id LIKE #{search})</if>" +
        "</script>")
int countRecordsWithFilter(Map<String, Object> params);

@Select("SELECT * FROM fact_check_records WHERE id = #{id}")
FactCheckRecord findById(Long id);

@Delete("DELETE FROM fact_check_records WHERE id = #{id}")
int deleteById(Long id);

@Select("SELECT COUNT(*) FROM fact_check_records")
int countAll();

@MapKey("status")
@Select("SELECT status, COUNT(*) as count FROM fact_check_records GROUP BY status")
Map<String, Integer> getStatusStats();

@Select("SELECT COUNT(*) FROM fact_check_records WHERE DATE(create_time) = CURDATE()")
int countTodayRecords();

@Select("SELECT COUNT(*) FROM fact_check_records WHERE YEARWEEK(create_time, 1) = YEARWEEK(CURDATE(), 1)")
int countWeekRecords();

@Update("UPDATE fact_check_records SET status = #{status}, error_message = #{errorMessage}, update_time = NOW() WHERE id = #{id}")
int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("errorMessage") String errorMessage);   
}

