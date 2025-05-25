package com.factcheck.app.controller;

import com.factcheck.app.service.FactCheckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/fact-check")
@CrossOrigin(origins = "*")
public class FactCheckController {

    @Autowired
    private FactCheckService factCheckService;

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitFactCheck(
            @RequestParam("claim") String claim,
            @RequestParam(value = "media", required = false) MultipartFile mediaFile,
            @RequestParam(value = "directVerify", defaultValue = "false") Boolean directVerify,
            @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
            @RequestParam(value = "cseId", defaultValue = "") String cseId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            String taskId = factCheckService.submitTask(claim, mediaFile, directVerify, apiKey, cseId);
            response.put("success", true);
            response.put("taskId", taskId);
            response.put("message", "任务提交成功");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, Object> status = factCheckService.getTaskStatus(taskId);
            response.putAll(status);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }
}
