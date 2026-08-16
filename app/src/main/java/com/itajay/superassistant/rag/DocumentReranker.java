package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;

/**
 * 精排（Rerank）接口：对融合后的候选文档按与查询的相关性重新排序。
 */
public interface DocumentReranker {

    /**
     * 对候选文档进行精排，返回按相关性降序、数量不超过 topK 的文档列表。
     */
    List<Document> rerank(Query query, List<Document> documents);
}
