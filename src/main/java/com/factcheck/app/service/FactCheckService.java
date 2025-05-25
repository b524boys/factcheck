package com.factcheck.app.service;

import com.factcheck.app.entity.FactCheckRecord;
import com.factcheck.app.mapper.FactCheckMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class FactCheckService {
    
    private static final Logger logger = LoggerFactory.getLogger(FactCheckService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private FactCheckMapper factCheckMapper;
    
    @Value("${fact-check.python.script-path:/app/main.py}")
    private String pythonScriptPath;
    
    @Value("${fact-check.upload.dir:/app/uploads}")
    private String uploadDir;
    
    @Value("${fact-check.server.url:http://workspace.featurize.cn:35407}")
    private String serverUrl;
    
    // 用于存储任务状态的内存缓存
    private final Map<String, Map<String, Object>> taskStatusCache = new ConcurrentHashMap<>();
    
    // 缓存找到的Python命令，避免重复查找
    private String cachedPythonCommand = null;
    
    // 存储运行中的进程，用于监控和清理
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public String submitTask(String claim, MultipartFile mediaFile, Boolean directVerify, String apiKey, String cseId) throws Exception {
        // 生成任务ID
        String taskId = UUID.randomUUID().toString().replace("-", "");
        
        // 保存媒体文件（如果有）
        final String mediaFilePath = (mediaFile != null && !mediaFile.isEmpty()) 
            ? saveMediaFile(mediaFile, taskId) 
            : null;
        
        // 创建数据库记录
        FactCheckRecord record = new FactCheckRecord();
        record.setClaim(claim);
        record.setMediaFileName(mediaFile != null ? mediaFile.getOriginalFilename() : null);
        record.setDirectVerify(directVerify);
        record.setApiKey(maskApiKey(apiKey)); // 存储时遮蔽API密钥
        record.setCseId(cseId);
        record.setTaskId(taskId);
        record.setStatus("processing");
        
        factCheckMapper.insert(record);
        
        // 异步执行Python脚本
        CompletableFuture.runAsync(() -> {
            try {
                executePythonScript(taskId, claim, mediaFilePath, directVerify, apiKey, cseId);
            } catch (Exception e) {
                logger.error("执行Python脚本失败: {}", e.getMessage(), e);
                updateTaskStatus(taskId, "error", null, getDetailedErrorMessage(-1, e.getMessage()));
            }
        });
        
        return taskId;
    }
    
    private String saveMediaFile(MultipartFile file, String taskId) throws IOException {
        // 创建上传目录
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        // 生成文件名
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".") 
            ? originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
        String filename = taskId + "_media" + extension;
        
        // 保存文件
        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath);
        
        return filePath.toString();
    }
    
    private void executePythonScript(String taskId, String claim, String mediaFilePath, 
                                   Boolean directVerify, String apiKey, String cseId) throws Exception {
        
        // 使用动态查找的Python命令
        String pythonCommand = findPythonCommand();
        
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        // 添加Python参数来禁用输出缓冲
        command.add("-u");  // 无缓冲输出
        command.add(pythonScriptPath);
        command.add("--server");
        command.add(serverUrl);
        command.add("--claim");
        command.add(claim);
        command.add("--output");
        command.add("/tmp/result_" + taskId + ".json");
        
        if (mediaFilePath != null) {
            command.add("--media");
            command.add(mediaFilePath);
        }
        
        if (directVerify != null && directVerify) {
            command.add("--direct");
        }
        
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            command.add("--api-key");
            command.add(apiKey);
        }
        
        if (cseId != null && !cseId.trim().isEmpty()) {
            command.add("--search-engine-id");
            command.add(cseId);
        }
        
        // 添加调试参数
        command.add("--debug");
        
        // 更新状态为处理中
        updateTaskStatusInCache(taskId, "processing", "正在启动Python客户端...", null);
        
        logger.info("执行命令: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        // 设置工作目录
        pb.directory(new File(System.getProperty("user.dir")));
        
        // 设置环境变量
        Map<String, String> env = pb.environment();
        env.put("PYTHONPATH", "/app");
        env.put("GOOGLE_API_KEY", apiKey != null ? apiKey : "");
        env.put("GOOGLE_CSE_ID", cseId != null ? cseId : "");
        env.put("PYTHONUNBUFFERED", "1");  // 禁用Python输出缓冲
        
        // 重定向错误流到标准输出
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        runningProcesses.put(taskId, process);
        
        logger.info("Python进程已启动，PID可能为: {}", process.pid());
        updateTaskStatusInCache(taskId, "processing", "Python进程已启动", "正在初始化...");
        
        // 创建线程来实时读取输出
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long lastOutputTime = System.currentTimeMillis();
                
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("Python输出: {}", line);
                    lastOutputTime = System.currentTimeMillis();
                    
                    // 解析进度信息并更新状态
                    if (line.contains("查询中") || line.contains("等待") || line.contains("处理")) {
                        updateTaskStatusInCache(taskId, "processing", "正在处理...", line);
                    } else if (line.contains("提交任务成功")) {
                        updateTaskStatusInCache(taskId, "processing", "任务已提交到服务器", line);
                    } else if (line.contains("收到") && line.contains("查询")) {
                        updateTaskStatusInCache(taskId, "processing", "开始执行搜索查询", line);
                    } else if (line.contains("执行搜索查询")) {
                        updateTaskStatusInCache(taskId, "processing", "正在执行搜索查询", line);
                    } else if (line.contains("完成") || line.contains("结果")) {
                        updateTaskStatusInCache(taskId, "processing", "处理即将完成", line);
                    }
                    
                    // 检查是否长时间无输出（10分钟）
                    if (System.currentTimeMillis() - lastOutputTime > 600000) {
                        logger.warn("Python进程长时间无输出，可能卡住了");
                        break;
                    }
                }
            } catch (IOException e) {
                logger.error("读取Python输出时出错: {}", e.getMessage());
            }
            return output.toString();
        }, executor);
        
        // 创建一个监控线程来检查进程状态
        CompletableFuture<Void> monitorFuture = CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                while (process.isAlive()) {
                    Thread.sleep(30000); // 每30秒检查一次
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    logger.info("Python进程运行中，已运行 {} 秒", elapsed);
                    updateTaskStatusInCache(taskId, "processing", 
                        String.format("正在处理中，已运行 %d 分钟", elapsed / 60), 
                        "Python进程正常运行");
                    
                    // 如果进程运行超过30分钟，记录警告
                    if (elapsed > 1800) {
                        logger.warn("Python进程运行时间过长: {} 秒", elapsed);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("监控线程被中断");
            }
        }, executor);
        
        try {
            // 等待进程完成
            int exitCode = process.waitFor();
            String output = outputFuture.get(10, TimeUnit.SECONDS); // 等待输出读取完成
            
            logger.info("Python进程退出，退出码: {}", exitCode);
            
            if (exitCode == 0) {
                // 成功完成，读取结果文件
                handleSuccessfulCompletion(taskId, output, directVerify, claim);
            } else {
                // 处理失败
                String errorMsg = getDetailedErrorMessage(exitCode, output);
                updateTaskStatus(taskId, "error", null, errorMsg);
                logger.error("任务 {} 失败: {}", taskId, errorMsg);
            }
            
        } catch (InterruptedException e) {
            logger.error("Python进程被中断");
            process.destroyForcibly();
            updateTaskStatus(taskId, "error", null, "任务被中断");
            Thread.currentThread().interrupt();
            
        } catch (TimeoutException e) {
            logger.error("等待Python输出超时");
            // 进程可能完成了，但输出读取超时，尝试读取结果文件
            try {
                handleSuccessfulCompletion(taskId, "", directVerify, claim);
            } catch (Exception ex) {
                updateTaskStatus(taskId, "error", null, "处理完成但读取结果超时");
            }
            
        } finally {
            // 清理资源
            runningProcesses.remove(taskId);
            monitorFuture.cancel(true);
            executor.shutdown();
            
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void handleSuccessfulCompletion(String taskId, String output, Boolean directVerify, String claim) {
        try {
            // 首先尝试从指定路径读取结果文件
            String resultFile = "/tmp/result_" + taskId + ".json";
            String resultContent = null;
            
            try {
                resultContent = Files.readString(Paths.get(resultFile));
                logger.info("成功从文件读取结果: {}", resultFile);
            } catch (IOException e) {
                // 如果结果文件不存在，尝试从Windows路径
                String windowsResultFile = "C:/temp/result_" + taskId + ".json";
                try {
                    resultContent = Files.readString(Paths.get(windowsResultFile));
                    logger.info("成功从Windows路径读取结果: {}", windowsResultFile);
                } catch (IOException e2) {
                    logger.warn("结果文件不存在，使用输出作为结果");
                    resultContent = output.trim();
                    if (resultContent.isEmpty()) {
                        resultContent = "{}";
                    }
                }
            }
            
            // 如果是直接验证模式，过滤输出内容
            if (directVerify != null && directVerify) {
                resultContent = filterDirectVerificationResult(resultContent, claim);
            }
            
            updateTaskStatus(taskId, "completed", resultContent, null);
            logger.info("任务 {} 完成", taskId);
            
        } catch (Exception e) {
            logger.error("处理成功完成时出错: {}", e.getMessage(), e);
            updateTaskStatus(taskId, "error", null, "处理结果时出错: " + e.getMessage());
        }
    }
    
    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> response = new HashMap<>();
        
        // 先从缓存中获取
        Map<String, Object> cachedStatus = taskStatusCache.get(taskId);
        if (cachedStatus != null) {
            return cachedStatus;
        }
        
        // 从数据库获取
        FactCheckRecord record = factCheckMapper.findByTaskId(taskId);
        if (record == null) {
            response.put("status", "error");
            response.put("error", "任务不存在");
            return response;
        }
        
        response.put("status", record.getStatus());
        
        if ("completed".equals(record.getStatus())) {
            try {
                // 解析结果JSON
                if (record.getResult() != null && !record.getResult().trim().isEmpty()) {
                    Object resultData = objectMapper.readValue(record.getResult(), Object.class);
                    response.put("data", resultData);
                } else {
                    response.put("data", new HashMap<>());
                }
            } catch (Exception e) {
                logger.error("解析结果JSON失败: {}", e.getMessage());
                response.put("data", new HashMap<>());
            }
        } else if ("error".equals(record.getStatus())) {
            response.put("error", record.getErrorMessage());
        }
        
        return response;
    }
    
    private void updateTaskStatus(String taskId, String status, String result, String errorMessage) {
        // 更新数据库
        factCheckMapper.updateByTaskId(taskId, status, result, errorMessage);
        
        // 更新缓存
        Map<String, Object> statusInfo = new HashMap<>();
        statusInfo.put("status", status);
        if (result != null) {
            try {
                statusInfo.put("data", objectMapper.readValue(result, Object.class));
            } catch (Exception e) {
                statusInfo.put("data", new HashMap<>());
            }
        }
        if (errorMessage != null) {
            statusInfo.put("error", errorMessage);
        }
        taskStatusCache.put(taskId, statusInfo);
        
        logger.info("任务 {} 状态更新为: {}", taskId, status);
    }
    
    private void updateTaskStatusInCache(String taskId, String status, String step, String progress) {
        Map<String, Object> statusInfo = new HashMap<>();
        statusInfo.put("status", status);
        if (step != null) {
            statusInfo.put("step", step);
        }
        if (progress != null) {
            statusInfo.put("progress", progress);
        }
        taskStatusCache.put(taskId, statusInfo);
    }
    
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
    
    /**
     * 过滤直接验证结果，提取assistant的回复部分
     */
    private String filterDirectVerificationResult(String rawResult, String claim) {
        try {
            // 如果已经是JSON格式，直接返回
            if (rawResult.trim().startsWith("{") && rawResult.trim().endsWith("}")) {
                return rawResult;
            }
            
            // 查找最后一个"assistant"关键词后的内容
            String[] lines = rawResult.split("\n");
            String assistantResponse = null;
            boolean foundAssistant = false;
            
            // 从后往前查找assistant回复
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.startsWith("assistant")) {
                    // 提取assistant后的内容
                    assistantResponse = line.substring("assistant".length()).trim();
                    foundAssistant = true;
                    break;
                } else if (foundAssistant && !line.isEmpty() && 
                          !line.startsWith("system") && 
                          !line.startsWith("user")) {
                    // 如果已经找到assistant但当前行不是system或user，可能是多行回复
                    assistantResponse = line;
                }
            }
            
            // 如果没找到assistant回复，尝试其他方法
            if (assistantResponse == null) {
                // 查找包含判断词的行
                for (String line : lines) {
                    String cleanLine = line.trim().toLowerCase();
                    if (cleanLine.contains("correct") || cleanLine.contains("incorrect") || 
                        cleanLine.contains("unrelated") || cleanLine.contains("true") || 
                        cleanLine.contains("false")) {
                        assistantResponse = line.trim();
                        break;
                    }
                }
            }
            
            // 如果还是没找到，使用最后一行非空内容
            if (assistantResponse == null) {
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (!lines[i].trim().isEmpty()) {
                        assistantResponse = lines[i].trim();
                        break;
                    }
                }
            }
            
            // 构建JSON结果
            Map<String, Object> result = new HashMap<>();
            result.put("direct_verification", assistantResponse != null ? assistantResponse : "未知");
            result.put("claim", claim != null ? claim : "");
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            logger.error("过滤直接验证结果时出错: {}", e.getMessage());
            // 出错时返回简化的结果
            try {
                Map<String, Object> fallbackResult = new HashMap<>();
                fallbackResult.put("direct_verification", rawResult.length() > 100 ? 
                    rawResult.substring(rawResult.length() - 100) : rawResult);
                fallbackResult.put("claim", claim != null ? claim : "");
                return objectMapper.writeValueAsString(fallbackResult);
            } catch (Exception ex) {
                return "{\"direct_verification\":\"处理结果时发生错误\",\"claim\":\"" + 
                       (claim != null ? claim.replace("\"", "\\\"") : "") + "\"}";
            }
        }
    }
    
    /**
     * 查找可用的Python命令
     */
    private String findPythonCommand() {
        // 如果已经缓存了命令，直接返回
        if (cachedPythonCommand != null) {
            return cachedPythonCommand;
        }
        
        String[] possibleCommands = {"python", "python3", "py"};
        
        for (String cmd : possibleCommands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                Process process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                
                if (finished && process.exitValue() == 0) {
                    logger.info("找到Python命令: {}", cmd);
                    cachedPythonCommand = cmd;
                    return cmd;
                }
            } catch (Exception e) {
                logger.debug("尝试Python命令 {} 失败: {}", cmd, e.getMessage());
            }
        }
        
        logger.warn("未找到可用的Python命令，使用默认的 'python'");
        cachedPythonCommand = "python";
        return "python";
    }
    
    /**
     * 根据退出码获取详细的错误信息
     */
    private String getDetailedErrorMessage(int exitCode, String originalError) {
        StringBuilder errorMsg = new StringBuilder();
        
        switch (exitCode) {
            case 9009:
                errorMsg.append("Python命令未找到或无法执行。");
                errorMsg.append("请检查：1) Python是否已正确安装；2) Python是否已添加到系统PATH环境变量中；3) 尝试在命令行输入 'python --version' 验证。");
                break;
            case 1:
                errorMsg.append("Python脚本执行时发生错误。");
                if (!originalError.isEmpty()) {
                    errorMsg.append("错误详情: ").append(originalError);
                }
                break;
            case 2:
                errorMsg.append("Python脚本文件未找到或无法访问。");
                errorMsg.append("请检查main.py文件是否存在于正确位置: ").append(pythonScriptPath);
                break;
            case 126:
                errorMsg.append("Python脚本没有执行权限。");
                break;
            default:
                errorMsg.append("Python脚本执行失败，退出码: ").append(exitCode);
                if (!originalError.isEmpty()) {
                    errorMsg.append("。错误信息: ").append(originalError);
                }
        }
        
        return errorMsg.toString();
    }
}
