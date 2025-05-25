package com.factcheck.app.entity;

import java.time.LocalDateTime;

public class FactCheckRecord {
    
    private Long id;                    // 主键ID
    private String taskId;              // 任务ID
    private String claim;               // 待核查的声明
    private String mediaFileName;       // 媒体文件名
    private Boolean directVerify;       // 是否直接验证
    private String apiKey;              // API密钥（已遮蔽）
    private String cseId;               // 搜索引擎ID
    private String status;              // 状态：processing, completed, error
    private String result;              // 核查结果（JSON格式）
    private String errorMessage;        // 错误信息
    private LocalDateTime createTime;   // 创建时间
    private LocalDateTime updateTime;   // 更新时间
    
    // 默认构造函数
    public FactCheckRecord() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
    
    // 带参构造函数
    public FactCheckRecord(String taskId, String claim) {
        this();
        this.taskId = taskId;
        this.claim = claim;
        this.status = "processing";
    }
    
    // Getter and Setter methods
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
    
    public String getClaim() {
        return claim;
    }
    
    public void setClaim(String claim) {
        this.claim = claim;
    }
    
    public String getMediaFileName() {
        return mediaFileName;
    }
    
    public void setMediaFileName(String mediaFileName) {
        this.mediaFileName = mediaFileName;
    }
    
    public Boolean getDirectVerify() {
        return directVerify;
    }
    
    public void setDirectVerify(Boolean directVerify) {
        this.directVerify = directVerify;
    }
    
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public String getCseId() {
        return cseId;
    }
    
    public void setCseId(String cseId) {
        this.cseId = cseId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
        this.updateTime = LocalDateTime.now(); // 状态更新时自动更新时间
    }
    
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
        this.updateTime = LocalDateTime.now();
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.updateTime = LocalDateTime.now();
    }
    
    public LocalDateTime getCreateTime() {
        return createTime;
    }
    
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
    
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }
    
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
    
    public boolean isFinished() {
        return "completed".equals(status) || "error".equals(status);
    }
    
    public boolean isCompleted() {
        return "completed".equals(status);
    }
    
    public boolean hasError() {
        return "error".equals(status);
    }

    public boolean isProcessing() {
        return "processing".equals(status);
    }
    
    public Long getDurationSeconds() {
        if (createTime != null && updateTime != null && isFinished()) {
            return java.time.Duration.between(createTime, updateTime).getSeconds();
        }
        return null;
    }

    public boolean hasMediaFile() {
        return mediaFileName != null && !mediaFileName.trim().isEmpty();
    }
    
    @Override
    public String toString() {
        return "FactCheckRecord{" +
                "id=" + id +
                ", taskId='" + taskId + '\'' +
                ", claim='" + (claim != null && claim.length() > 50 ? claim.substring(0, 50) + "..." : claim) + '\'' +
                ", status='" + status + '\'' +
                ", hasMedia=" + hasMediaFile() +
                ", directVerify=" + directVerify +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        FactCheckRecord that = (FactCheckRecord) o;
        
        if (id != null ? !id.equals(that.id) : that.id != null) return false;
        return taskId != null ? taskId.equals(that.taskId) : that.taskId == null;
    }
    
    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (taskId != null ? taskId.hashCode() : 0);
        return result;
    }
}
