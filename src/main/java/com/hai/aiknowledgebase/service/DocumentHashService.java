package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.hai.aiknowledgebase.entity.DocumentHash;
import com.hai.aiknowledgebase.mapper.DocumentHashMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentHashService {

    private final DocumentHashMapper documentHashMapper;
    private final Cache<String, String> documentHashCache;  // 注入 Caffeine

    /**
     * ============================================
     * 1. 缓存预热（应用启动时自动执行）
     * ============================================
     * 采用「按时间倒序 + limit」策略，加载最新的 10000 条哈希值
     * 优点：不依赖分页插件，无 COUNT 查询，性能最高
     */
    @PostConstruct
    public void warmUpCache() {
        long startTime = System.currentTimeMillis();

        // 构建查询条件：只查 hash_value 字段，按创建时间倒序，只取前 10000 条
        LambdaQueryWrapper<DocumentHash> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(DocumentHash::getHashValue)
                .orderByDesc(DocumentHash::getCreatedAt)
                .last("LIMIT 10000");  // PostgreSQL / MySQL 都支持

        List<DocumentHash> list = documentHashMapper.selectList(wrapper);

        int loadCount = 0;
        for (DocumentHash item : list) {
            // 注意：此时 item 的其他字段为 null，只有 hashValue 有值
            documentHashCache.put(item.getHashValue(), "1");
            loadCount++;
        }

        long cost = System.currentTimeMillis() - startTime;
        log.info("缓存预热完成：成功加载 {} 条最新哈希值，耗时 {} ms", loadCount, cost);
    }


    /**
     * 检查哈希值是否已存在（先查缓存，再查数据库）
     */
    public boolean exists(String hashValue) {
        // 1. 查 Caffeine 缓存
        String cached = documentHashCache.getIfPresent(hashValue);
        if (cached != null) {
            log.debug("Cache hit for hash: {}", hashValue);
            return true;
        }

        // 2. 缓存未命中，查数据库
        LambdaQueryWrapper<DocumentHash> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentHash::getHashValue, hashValue);
        boolean exists = documentHashMapper.exists(wrapper);
        if (exists) {
            // 将数据库存在的哈希值放入缓存，加速后续访问
            documentHashCache.put(hashValue, "1");
            log.debug("Cache miss but DB found, put into cache: {}", hashValue);
        }
        return exists;
    }

    /**
     * 保存哈希值（必须在文档成功入库后调用）
     */
    @Transactional
    public void save(String hashValue, String hashType, String fileName,Long docId) {
        try {
            DocumentHash entity = new DocumentHash();
            entity.setHashValue(hashValue);
            entity.setHashType(hashType);
            entity.setFileName(fileName);
            entity.setDocumentId(docId);
            documentHashMapper.insert(entity);

            documentHashCache.put(hashValue, "1");
            log.info("Saved hash: {} (type: {})", hashValue, hashType);
        } catch (DuplicateKeyException e) {
            // 唯一约束冲突，说明其他线程已经插入了
            log.warn("Hash {} already exists (concurrent insert), skip saving.", hashValue);
        }finally {
            // 无论插入成功还是失败，都更新缓存
            documentHashCache.put(hashValue, "1");
        }
    }

    /**
     * 根据文件名删除哈希记录（同时清理缓存）
     */
    @Transactional
    public void deleteByFileName(Long docId) {
        LambdaQueryWrapper<DocumentHash> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentHash::getDocumentId, docId);
        List<DocumentHash> hashes = documentHashMapper.selectList(wrapper);
        if (hashes.isEmpty()) {
            log.info("未找到文件对应的哈希记录: {}", docId);
            return;
        }
        for (DocumentHash hash : hashes) {
            documentHashCache.invalidate(hash.getHashValue());
        }
        documentHashMapper.delete(wrapper);
        log.info("已删除文件对应的哈希记录: documentId={}, count={}", docId, hashes.size());
    }
}
