-- 事实核查记录表
CREATE TABLE IF NOT EXISTS fact_check_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id VARCHAR(64) UNIQUE NOT NULL COMMENT '任务ID',
    claim TEXT NOT NULL COMMENT '待核查的声明',
    media_file_name VARCHAR(255) COMMENT '媒体文件名',
    direct_verify BOOLEAN DEFAULT FALSE COMMENT '是否直接验证',
    api_key VARCHAR(100) COMMENT 'API密钥（已遮蔽）',
    cse_id VARCHAR(100) COMMENT '搜索引擎ID',
    status VARCHAR(20) NOT NULL DEFAULT 'processing' COMMENT '状态：processing, completed, error',
    result LONGTEXT COMMENT '核查结果（JSON格式）',
    error_message TEXT COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time),
    INDEX idx_update_time (update_time),
    INDEX idx_status_create_time (status, create_time),
    
    -- 全文索引（用于搜索声明内容）
    FULLTEXT INDEX ft_idx_claim (claim)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事实核查记录表';

-- 添加一些示例数据（可选）
INSERT IGNORE INTO fact_check_records (task_id, claim, status, create_time, update_time) VALUES
('example-task-1', 'DeepSeek is an advanced AI system developed by Chinese researchers.', 'completed', '2025-05-25 10:00:00', '2025-05-25 10:05:00'),
('example-task-2', 'The Great Wall of China is visible from space.', 'completed', '2025-05-25 11:00:00', '2025-05-25 11:03:00'),
('example-task-3', 'Python is the most popular programming language in 2025.', 'processing', '2025-05-25 12:00:00', '2025-05-25 12:00:00'),
('example-task-4', 'Electric cars produce zero emissions.', 'error', '2025-05-25 13:00:00', '2025-05-25 13:02:00');

-- 更新示例数据的结果
UPDATE fact_check_records SET 
    result = '{"final_judgment": {"final_judgment": "correct", "confidence": 85}, "evidence": [{"source": "Wikipedia", "content": "DeepSeek is indeed an AI system developed by Chinese researchers."}]}'
WHERE task_id = 'example-task-1';

UPDATE fact_check_records SET 
    result = '{"final_judgment": {"final_judgment": "incorrect", "confidence": 92}, "evidence": [{"source": "NASA", "content": "The Great Wall is not visible from space with naked eye."}]}'
WHERE task_id = 'example-task-2';

UPDATE fact_check_records SET 
    error_message = 'Network timeout while connecting to search API'
WHERE task_id = 'example-task-4';

-- 创建统计视图（可选，用于快速获取统计信息）
CREATE OR REPLACE VIEW fact_check_stats AS
SELECT 
    COUNT(*) as total_records,
    SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) as completed_count,
    SUM(CASE WHEN status = 'processing' THEN 1 ELSE 0 END) as processing_count,
    SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) as error_count,
    SUM(CASE WHEN DATE(create_time) = CURDATE() THEN 1 ELSE 0 END) as today_count,
    SUM(CASE WHEN YEARWEEK(create_time, 1) = YEARWEEK(CURDATE(), 1) THEN 1 ELSE 0 END) as week_count,
    ROUND(SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as success_rate
FROM fact_check_records;

-- 创建清理过期记录的存储过程（可选）
DELIMITER //

CREATE PROCEDURE CleanupOldRecords(IN days_to_keep INT)
BEGIN
    DECLARE records_deleted INT DEFAULT 0;
    
    -- 删除指定天数之前的已完成记录
    DELETE FROM fact_check_records 
    WHERE status IN ('completed', 'error') 
    AND create_time < DATE_SUB(NOW(), INTERVAL days_to_keep DAY);
    
    SET records_deleted = ROW_COUNT();
    
    SELECT CONCAT('Deleted ', records_deleted, ' old records') as result;
END //

DELIMITER ;

-- 使用示例：删除30天前的记录
-- CALL CleanupOldRecords(30);

-- 性能优化：如果数据量很大，可以考虑分区表
-- ALTER TABLE fact_check_records 
-- PARTITION BY RANGE (YEAR(create_time)) (
--     PARTITION p2024 VALUES LESS THAN (2025),
--     PARTITION p2025 VALUES LESS THAN (2026),
--     PARTITION p2026 VALUES LESS THAN (2027),
--     PARTITION pmax VALUES LESS THAN MAXVALUE
-- );