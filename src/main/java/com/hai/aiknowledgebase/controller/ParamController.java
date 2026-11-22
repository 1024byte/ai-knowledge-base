package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.ParamApplyRequest;
import com.hai.aiknowledgebase.dto.ParamItemVO;
import com.hai.aiknowledgebase.dto.ParamResetRequest;
import com.hai.aiknowledgebase.dto.ParamRollbackRequest;
import com.hai.aiknowledgebase.dto.ParamSaveRequest;
import com.hai.aiknowledgebase.entity.ParamHistory;
import com.hai.aiknowledgebase.service.ParamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <h2>参数管理接口</h2>
 *
 * <p>将检索调优相关参数统一配置，形成「调参 → 保存草稿 → 审核应用 → 生产生效」闭环。</p>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/params?kbId=</td><td>参数列表（元数据 + 草稿 + 已应用 + 生效值）</td></tr>
 *   <tr><td>POST</td><td>/api/params/save</td><td>保存草稿（登录即可）</td></tr>
 *   <tr><td>POST</td><td>/api/params/apply</td><td>应用草稿（需管理员）</td></tr>
 *   <tr><td>POST</td><td>/api/params/rollback</td><td>回退到默认（需管理员）</td></tr>
 *   <tr><td>POST</td><td>/api/params/reset</td><td>清空作用域全部配置（需管理员）</td></tr>
 *   <tr><td>GET</td><td>/api/params/history?kbId=&amp;paramKey=</td><td>操作历史</td></tr>
 * </table>
 *
 * <h3>作用域约定</h3>
 * <p>{@code kbId} 为空表示全局（GLOBAL）作用域，非空表示知识库级（KB）覆盖。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/params")
@RequiredArgsConstructor
public class ParamController {

    private final ParamService paramService;

    /**
     * <h3>查询参数列表</h3>
     *
     * @param kbId 知识库 ID（可空，空表示全局作用域）
     * @return 参数列表（含元数据、草稿值、已应用值、生效值）
     */
    @GetMapping
    public Result<List<ParamItemVO>> list(@RequestParam(required = false) Long kbId) {
        log.info("查询参数列表: kbId={}", kbId);
        return Result.success(paramService.list(kbId));
    }

    /**
     * <h3>保存草稿</h3>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/params/save
     * {
     *   "kbId": 1,
     *   "paramKey": "retrieval.vectorMinScore",
     *   "paramValue": "0.65"
     * }
     * }</pre>
     */
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody ParamSaveRequest request) {
        log.info("保存参数草稿: kbId={}, key={}, value={}",
                request.getKbId(), request.getParamKey(), request.getParamValue());
        paramService.save(request.getKbId(), request.getParamKey(), request.getParamValue());
        return Result.success();
    }

    /**
     * <h3>应用草稿（草稿 → 生产生效）</h3>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/params/apply
     * { "kbId": 1, "paramKey": "retrieval.vectorMinScore" }
     * }</pre>
     */
    @PostMapping("/apply")
    public Result<Void> apply(@Valid @RequestBody ParamApplyRequest request) {
        log.info("应用参数: kbId={}, key={}", request.getKbId(), request.getParamKey());
        paramService.apply(request.getKbId(), request.getParamKey());
        return Result.success();
    }

    /**
     * <h3>回退参数到默认值</h3>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/params/rollback
     * { "kbId": 1, "paramKey": "retrieval.vectorMinScore" }
     * }</pre>
     */
    @PostMapping("/rollback")
    public Result<Void> rollback(@Valid @RequestBody ParamRollbackRequest request) {
        log.info("回退参数: kbId={}, key={}", request.getKbId(), request.getParamKey());
        paramService.rollback(request.getKbId(), request.getParamKey());
        return Result.success();
    }

    /**
     * <h3>清空本作用域全部配置</h3>
     *
     * <p>请求体可为空（表示重置全局），或携带 {@code kbId} 重置指定知识库。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/params/reset
     * { "kbId": 1 }
     * }</pre>
     */
    @PostMapping("/reset")
    public Result<Void> reset(@RequestBody(required = false) ParamResetRequest request) {
        Long kbId = request == null ? null : request.getKbId();
        log.info("重置参数: kbId={}", kbId);
        paramService.reset(kbId);
        return Result.success();
    }

    /**
     * <h3>查询操作历史</h3>
     *
     * @param kbId     知识库 ID（可空，空表示全局作用域）
     * @param paramKey 参数键（可空，空表示查询该作用域全部历史）
     * @return 历史记录（按操作时间倒序）
     */
    @GetMapping("/history")
    public Result<List<ParamHistory>> history(@RequestParam(required = false) Long kbId,
                                              @RequestParam(required = false) String paramKey) {
        log.info("查询参数历史: kbId={}, key={}", kbId, paramKey);
        return Result.success(paramService.history(kbId, paramKey));
    }
}