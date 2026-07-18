package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 查询改写配置加载器
 * 职责：从 JSON 文件加载同义词、固定映射、停用词，支持定时刷新
 */
@Slf4j
@Component
public class QueryRewriteConfigLoader {

    @Value("${query-rewrite.config.synonyms-path:query-rewrite/synonyms.json}")
    private String synonymsPath;

    @Value("${query-rewrite.config.fixed-mapping-path:query-rewrite/fixed-mapping.json}")
    private String fixedMappingPath;

    @Value("${query-rewrite.config.stopwords-path:query-rewrite/stopwords.json}")
    private String stopwordsPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 线程安全的引用（Copy-on-Write 模式）
    private final AtomicReference<Map<String, List<String>>> synonymDict = new AtomicReference<>(new HashMap<>());
    private final AtomicReference<Map<String, String>> fixedMapping = new AtomicReference<>(new HashMap<>());
    private final AtomicReference<Map<String, Double>> fixedMappingConfidence = new AtomicReference<>(new HashMap<>());
    private final AtomicReference<Set<String>> stopWords = new AtomicReference<>(new HashSet<>());

    // 记录各配置文件最后修改时间，用于增量判断
    private volatile long lastSynonymModify = 0;
    private volatile long lastFixedMappingModify = 0;
    private volatile long lastStopwordsModify = 0;

    // ==================== 公共读接口 ====================

    public Map<String, List<String>> getSynonymDict() {
        return Collections.unmodifiableMap(synonymDict.get());
    }

    public Map<String, String> getFixedMapping() {
        return Collections.unmodifiableMap(fixedMapping.get());
    }

    /**
     * 获取固定映射的置信度配置
     * key 为 from 值，value 为该条映射的 confidence
     */
    public Map<String, Double> getFixedMappingConfidence() {
        return fixedMappingConfidence.get();
    }

    public Set<String> getStopWords() {
        return Collections.unmodifiableSet(stopWords.get());
    }

    public boolean isStopWord(String word) {
        if (word == null || word.isEmpty()) return false;
        return stopWords.get().contains(word.trim().toLowerCase());
    }

    // ==================== 初始化 & 定时刷新 ====================

    @PostConstruct
    public void init() {
        log.info("初始化 QueryRewriteConfigLoader...");
        loadAll();
    }

    /**
     * 定时刷新：每5分钟检查一次文件修改时间
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void scheduledReload() {
        boolean anyLoaded = false;

        if (getFileModifyTime(synonymsPath) > lastSynonymModify) {
            anyLoaded |= loadSynonyms();
        }
        if (getFileModifyTime(fixedMappingPath) > lastFixedMappingModify) {
            anyLoaded |= loadFixedMapping();
        }
        if (getFileModifyTime(stopwordsPath) > lastStopwordsModify) {
            anyLoaded |= loadStopwords();
        }

        if (anyLoaded) {
            log.info("配置文件热加载完成（部分或全部）。当前状态：同义词[{}组]，映射[{}条]，停用词[{}个]",
                    synonymDict.get().size(), fixedMapping.get().size(), stopWords.get().size());
        }
    }

    /**
     * 手动触发热加载（供管理端调用）
     */
    public void reload() {
        log.info("手动触发热加载...");
        loadAll();
    }

    // ==================== 私有加载逻辑（统一返回 boolean） ====================

    private void loadAll() {
        loadSynonyms();
        loadFixedMapping();
        loadStopwords();
    }

    /**
     * 加载同义词
     * @return true 表示加载成功（包括加载到空配置），false 表示加载失败（保留旧数据）
     */
    private boolean loadSynonyms() {
        try {
            Map<String, List<String>> newDict = loadSynonymsFromFile(synonymsPath);

            // ✅ 情况一：文件读取成功（可能为空，也可能是真实数据）
            if (newDict != null) {
                synonymDict.set(newDict);
                lastSynonymModify = getFileModifyTime(synonymsPath);
                log.info("同义词配置加载成功，当前共 {} 组", newDict.size());
                return true;  // ✅ 修复：加载成功返回 true
            }

            // ❌ 情况二：文件不存在（返回了 null）
            if (synonymDict.get().isEmpty()) {
                // 首次启动，文件缺失，启用硬编码兜底
                synonymDict.set(getFallbackSynonyms());
                log.warn("同义词文件缺失，首次启动使用硬编码兜底数据，共 {} 组", synonymDict.get().size());
                return true;  // 虽然用了兜底，但服务正常启动，算“成功”
            } else {
                // 热加载时文件突然丢失，保留旧数据
                log.error("同义词文件突然消失！保留当前内存中的 {} 组旧数据，请尽快恢复文件", synonymDict.get().size());
                return false;  // 加载失败，因为文件没了
            }

        } catch (Exception e) {
            // ⚠️ 情况三：JSON解析失败
            log.error("同义词文件解析失败（JSON格式错误），保留当前内存数据不动", e);
            if (synonymDict.get().isEmpty()) {
                // 首次启动且文件坏了，用兜底
                synonymDict.set(getFallbackSynonyms());
                log.warn("首次启动且文件解析失败，使用硬编码兜底");
                return true;
            }
            return false;  // 热加载时解析失败，保留旧数据
        }
    }

    /**
     * 加载固定映射
     * @return true 表示加载成功（包括加载到空配置），false 表示加载失败（保留旧数据）
     */
    private boolean loadFixedMapping() {
        try {
            FixedMappingResult result = loadFixedMappingFromFile(fixedMappingPath);

            // ✅ 情况一：文件读取成功
            if (result != null) {
                fixedMapping.set(result.mapping);
                fixedMappingConfidence.set(result.confidence);
                lastFixedMappingModify = getFileModifyTime(fixedMappingPath);
                log.info("固定映射加载成功，共 {} 条", result.mapping.size());
                return true;  // ✅ 修复：增加返回
            }

            // ❌ 情况二：文件不存在
            if (fixedMapping.get().isEmpty()) {
                fixedMapping.set(getFallbackFixedMapping());
                fixedMappingConfidence.set(getFallbackFixedMappingConfidence());
                log.warn("固定映射文件缺失，首次启动使用硬编码兜底");
                return true;
            } else {
                log.error("固定映射文件突然消失！保留当前内存中的 {} 条旧数据", fixedMapping.get().size());
                return false;
            }

        } catch (Exception e) {
            log.error("固定映射解析失败（JSON格式错误），保留当前内存数据", e);
            if (fixedMapping.get().isEmpty()) {
                fixedMapping.set(getFallbackFixedMapping());
                fixedMappingConfidence.set(getFallbackFixedMappingConfidence());
                log.warn("首次启动且文件解析失败，使用硬编码兜底");
                return true;
            }
            return false;
        }
    }

    /**
     * 加载停用词
     * @return true 表示加载成功（包括加载到空配置），false 表示加载失败（保留旧数据）
     */
    private boolean loadStopwords() {
        try {
            Set<String> newStopWords = loadStopwordsFromFile(stopwordsPath);

            // ✅ 情况一：文件读取成功（注意：null 代表文件缺失，空Set代表运维想清空）
            if (newStopWords != null) {
                stopWords.set(newStopWords);
                lastStopwordsModify = getFileModifyTime(stopwordsPath);
                log.info("停用词加载成功，共 {} 个", newStopWords.size());
                return true;  // ✅ 修复：增加返回
            }

            // ❌ 情况二：文件不存在
            if (stopWords.get().isEmpty()) {
                stopWords.set(getFallbackStopWords());
                log.warn("停用词文件缺失，首次启动使用硬编码兜底，共 {} 个", stopWords.get().size());
                return true;
            } else {
                log.error("停用词文件突然消失！保留当前内存中的 {} 个旧数据", stopWords.get().size());
                return false;
            }

        } catch (Exception e) {
            log.error("停用词解析失败（JSON格式错误），保留当前内存数据", e);
            if (stopWords.get().isEmpty()) {
                stopWords.set(getFallbackStopWords());
                log.warn("首次启动且文件解析失败，使用硬编码兜底");
                return true;
            }
            return false;
        }
    }

    // ==================== 文件解析核心（底层只搬运，不决策） ====================

    /**
     * 从 classpath 加载同义词
     * @return 解析成功返回 Map（可能为空），文件不存在返回 null，解析失败抛出异常
     */
    private Map<String, List<String>> loadSynonymsFromFile(String classpath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                log.error("同义词文件不存在: {}", classpath);
                return null;  // ✅ 文件缺失返回 null
            }

            JsonNode root = objectMapper.readTree(is);
            JsonNode entries = root.get("entries");
            Map<String, List<String>> dict = new HashMap<>();
            if (entries != null && entries.isArray()) {
                for (JsonNode entry : entries) {
                    boolean enabled = entry.has("enabled") ? entry.get("enabled").asBoolean() : true;
                    if (!enabled) continue;
                    String key = entry.get("key").asText();
                    List<String> synonyms = new ArrayList<>();
                    entry.get("synonyms").forEach(node -> synonyms.add(node.asText()));
                    dict.put(key, synonyms);
                }
            }
            // ✅ 不管是否为空，都返回 dict（不在这里返回 fallback）
            return dict;
        }
    }

    /**
     * 从 classpath 加载固定映射
     * @return 解析成功返回 FixedMappingResult（可能为空），文件不存在返回 null，解析失败抛出异常
     */
    private FixedMappingResult loadFixedMappingFromFile(String classpath) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream(classpath);
        if (is == null) {
            log.error("固定映射文件不存在: {}", classpath);
            return null;  // ✅ 修复：文件缺失返回 null，而不是 fallback
        }

        JsonNode root = objectMapper.readTree(is);
        JsonNode entries = root.get("entries");
        Map<String, String> mapping = new HashMap<>();
        Map<String, Double> confidence = new HashMap<>();
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                boolean enabled = entry.has("enabled") ? entry.get("enabled").asBoolean() : true;
                if (!enabled) continue;
                String from = entry.get("from").asText();
                String to = entry.get("to").asText();
                double conf = entry.has("confidence") ? entry.get("confidence").asDouble() : 0.95;
                mapping.put(from, to);
                confidence.put(from, conf);
            }
        }
        // ✅ 修复：不管是否为空，都返回 result（不在这里返回 fallback）
        return new FixedMappingResult(mapping, confidence);
    }

    /**
     * 从 classpath 加载停用词
     * @return 解析成功返回 Set（可能为空），文件不存在返回 null，解析失败抛出异常
     */
    private Set<String> loadStopwordsFromFile(String classpath) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream(classpath);
        if (is == null) {
            log.error("停用词文件不存在: {}", classpath);
            return null;  // ✅ 修复：文件缺失返回 null，而不是 fallback
        }

        JsonNode root = objectMapper.readTree(is);
        Set<String> stopWordsSet = new HashSet<>();
        if (root.has("zh")) {
            root.get("zh").forEach(node -> stopWordsSet.add(node.asText()));
        }
        if (root.has("en")) {
            root.get("en").forEach(node -> stopWordsSet.add(node.asText().toLowerCase()));
        }
        // ✅ 修复：不管是否为空，都返回 stopWordsSet（不在这里返回 fallback）
        return stopWordsSet;
    }

    // ==================== 兜底硬编码（保证服务不中断） ====================

    private Map<String, List<String>> getFallbackSynonyms() {
        Map<String, List<String>> fallback = new HashMap<>();
        fallback.put("换工作", Arrays.asList("职业转换", "跳槽", "转行"));
        fallback.put("薪资", Arrays.asList("工资", "薪酬", "收入"));
        fallback.put("配置", Arrays.asList("设置", "参数"));
        return fallback;
    }

    private Map<String, String> getFallbackFixedMapping() {
        Map<String, String> fallback = new HashMap<>();
        fallback.put("专升本", "专升本 3500词");
        fallback.put("讲稿", "讲义");
        fallback.put("API", "API 接口");
        return fallback;
    }

    private Map<String, Double> getFallbackFixedMappingConfidence() {
        Map<String, Double> fallback = new HashMap<>();
        fallback.put("专升本", 0.95);
        fallback.put("讲稿", 0.95);
        fallback.put("API", 0.90);
        return fallback;
    }

    private Set<String> getFallbackStopWords() {
        return new HashSet<>(Arrays.asList("的", "了", "在", "是", "我", "有", "和", "就",
                "the", "a", "an", "is", "are", "was", "were"));
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 classpath 文件的最后修改时间
     * 注意：如果文件在 jar 包内，无法获取修改时间，返回 0（此时热加载不会触发）
     * 生产环境建议将配置文件放在外部目录，而不是打包在 jar 内
     */
    private long getFileModifyTime(String classpath) {
        try {
            java.net.URL url = getClass().getClassLoader().getResource(classpath);
            if (url != null && "file".equals(url.getProtocol())) {
                Path path = Paths.get(url.toURI());
                return Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception e) {
            // 忽略，文件不在文件系统（如在jar包中），返回0表示无法检测
        }
        return 0;
    }

    /**
     * 固定映射加载结果（包含映射关系和置信度）
     */
    private static class FixedMappingResult {
        final Map<String, String> mapping;
        final Map<String, Double> confidence;

        FixedMappingResult(Map<String, String> mapping, Map<String, Double> confidence) {
            this.mapping = mapping;
            this.confidence = confidence;
        }
    }
}