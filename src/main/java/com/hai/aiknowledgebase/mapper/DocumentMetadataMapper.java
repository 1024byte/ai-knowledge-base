package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentMetadataMapper extends BaseMapper<DocumentMetadata> {

    /**
     * 统计各分类的文件数量（复杂查询，使用 XML 或注解）
     */
    @Select("SELECT category, COUNT(*) as count FROM document_metadata WHERE status = 'active' GROUP BY category")
    List<CategoryStatistics> countByCategory();

    /**
     * 根据文件名查询（可能有多个同名文件）
     */
    @Select("SELECT * FROM document_metadata WHERE file_name = #{fileName} AND status = 'active' ORDER BY id")
    List<DocumentMetadata> selectByFileName(@Param("fileName") String fileName);



    @Data
    public class CategoryStatistics {
        private String category;
        private Integer count;
    }

}

