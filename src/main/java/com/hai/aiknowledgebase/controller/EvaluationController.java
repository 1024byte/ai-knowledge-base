package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.eval.EvalCaseRequest;
import com.hai.aiknowledgebase.dto.eval.EvalDatasetVO;
import com.hai.aiknowledgebase.dto.eval.EvalRunCaseDetailVO;
import com.hai.aiknowledgebase.dto.eval.EvalRunCreateRequest;
import com.hai.aiknowledgebase.dto.eval.EvalRunDetailVO;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.EvalDatasetService;
import com.hai.aiknowledgebase.service.EvalRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 检索评测集、Case 与运行接口。 */
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;

    @GetMapping("/datasets")
    public Result<List<EvalDatasetVO>> datasets(@RequestParam(required = false) Long kbId,
                                                @RequestParam(required = false) String keyword) {
        return Result.success(datasetService.list(kbId, keyword));
    }

    @GetMapping("/datasets/{id}")
    public Result<EvalDatasetVO.Detail> dataset(@PathVariable Long id) {
        return Result.success(datasetService.detail(id));
    }

    @PostMapping("/datasets")
    public Result<Map<String, Long>> createDataset(@RequestBody DatasetRequest request) {
        return Result.success(Map.of("id", datasetService.create(
                request.kbId(), request.name(), request.description())));
    }

    @PutMapping("/datasets/{id}")
    public Result<Void> updateDataset(@PathVariable Long id, @RequestBody DatasetRequest request) {
        datasetService.update(id, request.name(), request.description());
        return Result.success();
    }

    @DeleteMapping("/datasets/{id}")
    public Result<Void> deleteDataset(@PathVariable Long id) {
        datasetService.delete(id);
        return Result.success();
    }

    @PostMapping("/datasets/{id}/cases")
    public Result<Map<String, Long>> addCase(@PathVariable Long id, @RequestBody EvalCaseRequest request) {
        return Result.success(Map.of("id", datasetService.addCase(id, request)));
    }

    @PostMapping("/datasets/{id}/cases/batch")
    public Result<Map<String, Object>> addCases(@PathVariable Long id,
                                                @RequestBody EvalCaseRequest.Batch request) {
        return Result.success(datasetService.addCasesBatch(id, request.getCases()));
    }

    @PutMapping("/datasets/{id}/cases/{caseId}")
    public Result<Void> updateCase(@PathVariable Long id, @PathVariable Long caseId,
                                   @RequestBody EvalCaseRequest request) {
        datasetService.updateCase(id, caseId, request);
        return Result.success();
    }

    @DeleteMapping("/datasets/{id}/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable Long id, @PathVariable Long caseId) {
        datasetService.deleteCase(id, caseId);
        return Result.success();
    }

    @PostMapping("/datasets/{id}/import")
    public Result<Map<String, Object>> importCases(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        return Result.success(datasetService.importCases(id, file));
    }

    @GetMapping("/datasets/{id}/export")
    public ResponseEntity<String> exportDataset(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=evaluation-dataset-" + id + ".json")
                .body(datasetService.exportDataset(id));
    }

    @PostMapping("/runs")
    public Result<Map<String, Long>> createRun(@RequestBody EvalRunCreateRequest request) {
        return Result.success(Map.of("id", runService.create(request)));
    }

    @GetMapping("/runs")
    public Result<Map<String, Object>> runs(@RequestParam(required = false) Long datasetId,
                                            @RequestParam(required = false) Long kbId,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return Result.success(runService.list(datasetId, kbId, state, startAt, endAt, page, size));
    }

    @GetMapping("/runs/{id}")
    public Result<EvalRunDetailVO> run(@PathVariable Long id) {
        return Result.success(runService.detail(id));
    }

    @PostMapping("/runs/{id}/cancel")
    public Result<Void> cancelRun(@PathVariable Long id) {
        runService.cancel(id);
        return Result.success();
    }

    @DeleteMapping("/runs/{id}")
    public Result<Void> deleteRun(@PathVariable Long id) {
        runService.delete(id);
        return Result.success();
    }

    @GetMapping("/runs/{id}/cases")
    public Result<Map<String, Object>> runCases(@PathVariable Long id,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) Boolean onlyMissed,
                                                @RequestParam(required = false) Boolean onlyFailed,
                                                @RequestParam(required = false) String tag) {
        return Result.success(runService.cases(id, page, size, onlyMissed, onlyFailed, tag));
    }

    @GetMapping("/runs/{id}/cases/{caseId}")
    public Result<EvalRunCaseDetailVO> runCase(@PathVariable Long id, @PathVariable Long caseId) {
        return Result.success(runService.caseDetail(id, caseId));
    }

    @GetMapping("/runs/{id}/export")
    public ResponseEntity<byte[]> exportRun(@PathVariable Long id,
                                            @RequestParam(defaultValue = "json") String format) {
        String normalized = format.toLowerCase();
        byte[] content;
        MediaType mediaType;
        String extension;
        if ("json".equals(normalized)) {
            content = runService.exportJson(id).getBytes(StandardCharsets.UTF_8);
            mediaType = MediaType.APPLICATION_JSON;
            extension = "json";
        } else if ("csv".equals(normalized)) {
            content = runService.exportCsv(id);
            mediaType = new MediaType("text", "csv", StandardCharsets.UTF_8);
            extension = "csv";
        } else {
            throw new BusinessException(400, "format 仅支持 json 或 csv");
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=evaluation-run-" + id + "." + extension)
                .body(content);
    }

    /** 创建与修改评测集共用的请求体；修改时忽略 kbId。 */
    public record DatasetRequest(Long kbId, String name, String description) {
    }
}
