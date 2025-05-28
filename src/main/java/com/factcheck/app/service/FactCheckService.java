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

    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public String submitTask(String claim, MultipartFile mediaFile, Boolean directVerify, String apiKey, String cseId) throws Exception {
        String taskId = UUID.randomUUID().toString().replace("-", "");

        final String mediaFilePath = (mediaFile != null && !mediaFile.isEmpty()) 
            ? saveMediaFile(mediaFile, taskId) 
            : null;

        FactCheckRecord record = new FactCheckRecord();
        record.setClaim(claim);
        record.setMediaFileName(mediaFile != null ? mediaFile.getOriginalFilename() : null);
        record.setDirectVerify(directVerify);
        record.setApiKey(maskApiKey(apiKey));
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
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".") 
            ? originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
        String filename = taskId + "_media" + extension;
        
        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath);
        
        return filePath.toString();
    }
    
    private void executePythonScript(String taskId, String claim, String mediaFilePath, 
                                   Boolean directVerify, String apiKey, String cseId) throws Exception {
        
        String pythonCommand = findPythonCommand();
        
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.add("-u");
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
        
        command.add("--debug");
        
        updateTaskStatusInCache(taskId, "processing", "正在启动Python客户端...", null);
        
        logger.info("执行命令: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        
        Map<String, String> env = pb.environment();
        env.put("PYTHONPATH", "/app");
        env.put("GOOGLE_API_KEY", apiKey != null ? apiKey : "");
        env.put("GOOGLE_CSE_ID", cseId != null ? cseId : "");
        env.put("PYTHONUNBUFFERED", "1");  // 禁用Python输出缓冲
        
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
                    
                    if (System.currentTimeMillis() - lastOutputTime > 600000) {
                        logger.warn("Python进程长时间无输出");
                        break;
                    }
                }
            } catch (IOException e) {
                logger.error("读取Python输出时出错: {}", e.getMessage());
            }
            return output.toString();
        }, executor);
        
        CompletableFuture<Void> monitorFuture = CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                while (process.isAlive()) {
                    Thread.sleep(30000);
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    logger.info("Python进程运行中，已运行 {} 秒", elapsed);
                    updateTaskStatusInCache(taskId, "processing", 
                        String.format("正在处理中，已运行 %d 分钟", elapsed / 60), 
                        "Python进程正常运行");
                    
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
            int exitCode = process.waitFor();
            String output = outputFuture.get(10, TimeUnit.SECONDS);
            
            logger.info("Python进程退出，退出码: {}", exitCode);
            
            if (exitCode == 0) {
                handleSuccessfulCompletion(taskId, output, directVerify, claim);
            } else {
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
            try {
                handleSuccessfulCompletion(taskId, "", directVerify, claim);
            } catch (Exception ex) {
                updateTaskStatus(taskId, "error", null, "处理完成但读取结果超时");
            }
            
        } finally {
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
            String resultFile = "/tmp/result_" + taskId + ".json";
            String resultContent = null;
            
            try {
                resultContent = Files.readString(Paths.get(resultFile));
                logger.info("成功从文件读取结果: {}", resultFile);
            } catch (IOException e) {
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
        
        Map<String, Object> cachedStatus = taskStatusCache.get(taskId);
        if (cachedStatus != null) {
            return cachedStatus;
        }
        
        FactCheckRecord record = factCheckMapper.findByTaskId(taskId);
        if (record == null) {
            response.put("status", "error");
            response.put("error", "任务不存在");
            return response;
        }
        
        response.put("status", record.getStatus());
        
        if ("completed".equals(record.getStatus())) {
            try {
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
        factCheckMapper.updateByTaskId(taskId, status, result, errorMessage);
        
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
            if (rawResult.trim().startsWith("{") && rawResult.trim().endsWith("}")) {
                return rawResult;
            }
            
            String[] lines = rawResult.split("\n");
            String assistantResponse = null;
            boolean foundAssistant = false;
            
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.startsWith("assistant")) {
                    assistantResponse = line.substring("assistant".length()).trim();
                    foundAssistant = true;
                    break;
                } else if (foundAssistant && !line.isEmpty() && 
                          !line.startsWith("system") && 
                          !line.startsWith("user")) {
                    assistantResponse = line;
                }
            }
            
            if (assistantResponse == null) {
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
