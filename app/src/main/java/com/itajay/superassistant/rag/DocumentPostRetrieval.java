package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索后处理：对多路、多查询召回的候选文档先去重，再做精排（rerank）。
 */
@Component
public class DocumentPostRetrieval {

    private final DocumentReranker documentReranker;

    public DocumentPostRetrieval(DocumentReranker documentReranker) {
        this.documentReranker = documentReranker;
    }

    /**
     * 去重 + 精排。
     *
     * @param query      原始（压缩后的）查询
     * @param candidates 多路、多查询召回的候选文档
     * @return 精排后的文档列表
     */
    public List<Document> doPostProcess(Query query, List<Document> candidates) {
        List<Document> deduplicated = DocumentFusion.deduplicate(candidates);
        return documentReranker.rerank(query, deduplicated);
    }
}
