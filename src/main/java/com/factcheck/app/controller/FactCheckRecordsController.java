package com.factcheck.app.controller;

import com.factcheck.app.entity.FactCheckRecord;
import com.factcheck.app.mapper.FactCheckMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class FactCheckRecordsController {
    
    private static final Logger logger = LoggerFactory.getLogger(FactCheckRecordsController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private FactCheckMapper factCheckMapper;
    
    /**
     * 核查记录管理页面
     */
    @GetMapping("/records")
    public String recordsPage(Model model) {
        model.addAttribute("title", "事实核查记录管理");
        return "admin/records";
    }
    
    /**
     * 获取核查记录列表API
     */
    @GetMapping("/api/records")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        
        try {
            // 计算偏移量
            int offset = (page - 1) * size;
            
            // 构建查询条件
            Map<String, Object> params = new HashMap<>();
            params.put("offset", offset);
            params.put("limit", size);
            params.put("sortBy", sortBy);
            params.put("sortOrder", sortOrder.toLowerCase());
            
            if (status != null && !status.trim().isEmpty()) {
                params.put("status", status.trim());
            }
            
            if (search != null && !search.trim().isEmpty()) {
                params.put("search", "%" + search.trim() + "%");
            }
            
            // 获取记录列表和总数
            List<FactCheckRecord> records = factCheckMapper.findRecordsWithPagination(params);
            int totalCount = factCheckMapper.countRecordsWithFilter(params);
            
            // 处理记录数据
            List<Map<String, Object>> processedRecords = records.stream()
                .map(this::processRecord)
                .collect(Collectors.toList());
            
            // 计算分页信息
            int totalPages = (int) Math.ceil((double) totalCount / size);
            
            Map<String, Object> response = new HashMap<>();
            response.put("records", processedRecords);
            response.put("pagination", Map.of(
                "current", page,
                "size", size,
                "total", totalCount,
                "totalPages", totalPages,
                "hasNext", page < totalPages,
                "hasPrev", page > 1
            ));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("获取核查记录失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "获取记录失败: " + e.getMessage()));
        }
    }
    
    /**
     * 获取单个记录详情
     */
    @GetMapping("/api/records/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRecordDetail(@PathVariable Long id) {
        try {
            FactCheckRecord record = factCheckMapper.findById(id);
            if (record == null) {
                return ResponseEntity.notFound().build();
            }
            
            Map<String, Object> response = processRecord(record);
            
            // 解析结果JSON
            if (record.getResult() != null && !record.getResult().trim().isEmpty()) {
                try {
                    Object resultData = objectMapper.readValue(record.getResult(), Object.class);
                    response.put("resultData", resultData);
                } catch (Exception e) {
                    logger.warn("解析结果JSON失败: {}", e.getMessage());
                    response.put("resultData", Map.of("raw", record.getResult()));
                }
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("获取记录详情失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "获取记录详情失败: " + e.getMessage()));
        }
    }
    
    /**
     * 删除记录
     */
    @DeleteMapping("/api/records/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteRecord(@PathVariable Long id) {
        try {
            FactCheckRecord record = factCheckMapper.findById(id);
            if (record == null) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "记录不存在"));
            }
            
            factCheckMapper.deleteById(id);
            
            logger.info("删除核查记录: ID={}, Claim={}", id, record.getClaim());
            
            return ResponseEntity.ok(Map.of(
                "message", "记录删除成功",
                "id", id
            ));
            
        } catch (Exception e) {
            logger.error("删除记录失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "删除记录失败: " + e.getMessage()));
        }
    }
    
    /**
     * 批量删除记录
     */
    @DeleteMapping("/api/records/batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteRecords(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> ids = (List<Long>) request.get("ids");
            
            if (ids == null || ids.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "请选择要删除的记录"));
            }
            
            int deletedCount = 0;
            for (Long id : ids) {
                try {
                    factCheckMapper.deleteById(id);
                    deletedCount++;
                } catch (Exception e) {
                    logger.warn("删除记录 {} 失败: {}", id, e.getMessage());
                }
            }
            
            logger.info("批量删除核查记录: 请求删除 {}, 实际删除 {}", ids.size(), deletedCount);
            
            return ResponseEntity.ok(Map.of(
                "message", String.format("成功删除 %d 条记录", deletedCount),
                "deletedCount", deletedCount,
                "requestedCount", ids.size()
            ));
            
        } catch (Exception e) {
            logger.error("批量删除记录失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "批量删除失败: " + e.getMessage()));
        }
    }
    
    /**
     * 获取统计信息
     */
    @GetMapping("/api/records/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            // 总记录数
            int totalRecords = factCheckMapper.countAll();
            stats.put("totalRecords", totalRecords);
            
            // 按状态统计
            Map<String, Integer> statusStats = factCheckMapper.getStatusStats();
            stats.put("statusStats", statusStats);
            
            // 今日新增
            int todayCount = factCheckMapper.countTodayRecords();
            stats.put("todayCount", todayCount);
            
            // 本周新增
            int weekCount = factCheckMapper.countWeekRecords();
            stats.put("weekCount", weekCount);
            
            // 成功率
            int completedCount = statusStats.getOrDefault("completed", 0);
            double successRate = totalRecords > 0 ? (double) completedCount / totalRecords * 100 : 0;
            stats.put("successRate", Math.round(successRate * 100.0) / 100.0);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            logger.error("获取统计信息失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "获取统计信息失败: " + e.getMessage()));
        }
    }
    
    /**
     * 重新执行核查任务
     */
    @PostMapping("/api/records/{id}/retry")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> retryTask(@PathVariable Long id) {
        try {
            FactCheckRecord record = factCheckMapper.findById(id);
            if (record == null) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "记录不存在"));
            }
            
            // 更新状态为处理中
            factCheckMapper.updateStatus(id, "processing", null);
            
            logger.info("重新执行核查任务: ID={}, Claim={}", id, record.getClaim());
            
            return ResponseEntity.ok(Map.of(
                "message", "任务已重新提交",
                "id", id,
                "status", "processing"
            ));
            
        } catch (Exception e) {
            logger.error("重新执行任务失败", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "重新执行任务失败: " + e.getMessage()));
        }
    }
    
    /**
     * 处理记录数据，添加额外信息
     */
    private Map<String, Object> processRecord(FactCheckRecord record) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("id", record.getId());
        result.put("taskId", record.getTaskId());
        result.put("claim", record.getClaim());
        result.put("status", record.getStatus());
        result.put("mediaFileName", record.getMediaFileName());
        result.put("directVerify", record.getDirectVerify());
        result.put("errorMessage", record.getErrorMessage());
        
        // 格式化时间
        if (record.getCreateTime() != null) {
            result.put("createTime", record.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            result.put("createTimeRaw", record.getCreateTime());
        }
        
        if (record.getUpdateTime() != null) {
            result.put("updateTime", record.getUpdateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            result.put("updateTimeRaw", record.getUpdateTime());
        }
        
        // 状态标签和颜色
        result.put("statusInfo", getStatusInfo(record.getStatus()));
        
        // 声明预览（限制长度）
        String claimPreview = record.getClaim();
        if (claimPreview != null && claimPreview.length() > 100) {
            claimPreview = claimPreview.substring(0, 100) + "...";
        }
        result.put("claimPreview", claimPreview);
        
        // 是否有媒体文件
        result.put("hasMedia", record.getMediaFileName() != null && !record.getMediaFileName().trim().isEmpty());
        
        // 处理时长（如果已完成）
        if (record.getCreateTime() != null && record.getUpdateTime() != null) {
            long duration = java.time.Duration.between(record.getCreateTime(), record.getUpdateTime()).getSeconds();
            result.put("duration", formatDuration(duration));
            result.put("durationSeconds", duration);
        }
        
        return result;
    }
    
    /**
     * 获取状态信息
     */
    private Map<String, String> getStatusInfo(String status) {
        Map<String, String> statusInfo = new HashMap<>();
        
        switch (status) {
            case "processing":
                statusInfo.put("label", "处理中");
                statusInfo.put("color", "warning");
                statusInfo.put("icon", "loading");
                break;
            case "completed":
                statusInfo.put("label", "已完成");
                statusInfo.put("color", "success");
                statusInfo.put("icon", "check-circle");
                break;
            case "error":
                statusInfo.put("label", "失败");
                statusInfo.put("color", "danger");
                statusInfo.put("icon", "x-circle");
                break;
            default:
                statusInfo.put("label", "未知");
                statusInfo.put("color", "secondary");
                statusInfo.put("icon", "help-circle");
        }
        
        return statusInfo;
    }
    
    /**
     * 格式化时长
     */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            return hours + "时" + minutes + "分" + secs + "秒";
        }
    }
}