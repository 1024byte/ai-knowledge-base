package com.hai.aiknowledgebase.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("document_hash")
public class DocumentHash {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String hashValue;

    private String hashType;  // "BYTE" 或 "TEXT"

    private String fileName;

    private Long documentId;

    private OffsetDateTime createdAt = OffsetDateTime.now();
}