package com.factcheck.app.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public class FactCheckRecord {
    private Long id;
    private String claim;
    private String mediaFileName;
    private Boolean directVerify;
    private String apiKey;
    private String cseId;
    private String taskId;
    private String status; // processing, completed, error
    private String result;
    private String errorMessage;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    // 构造函数
    public FactCheckRecord() {}

    // Getter和Setter方法
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClaim() { return claim; }
    public void setClaim(String claim) { this.claim = claim; }

    public String getMediaFileName() { return mediaFileName; }
    public void setMediaFileName(String mediaFileName) { this.mediaFileName = mediaFileName; }

    public Boolean getDirectVerify() { return directVerify; }
    public void setDirectVerify(Boolean directVerify) { this.directVerify = directVerify; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getCseId() { return cseId; }
    public void setCseId(String cseId) { this.cseId = cseId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
