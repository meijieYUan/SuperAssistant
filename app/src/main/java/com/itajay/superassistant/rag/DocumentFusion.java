package com.itajay.superassistant.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路召回结果融合工具：
 * <ul>
 *   <li>Reciprocal Rank Fusion (RRF)：将多条按相关性排序的召回列表融合为单一排序列表；</li>
 *   <li>去重：跨查询 / 跨路召回后，按文档文本去重。</li>
 * </ul>
 */
public final class DocumentFusion {

    private static final int DEFAULT_RRF_K = 60;

    private DocumentFusion() {
    }

    /**
     * 对多条已排序召回列表做 RRF 融合。
     *
     * @param rankedLists 多条召回列表，每条内部顺序即为召回顺序
     * @param topK        融合后返回的最大候选数
     * @return 按 RRF 分数降序的候选文档
     */
    public static List<Document> reciprocalRankFusion(List<List<Document>> rankedLists, int topK) {
        return reciprocalRankFusion(rankedLists, topK, DEFAULT_RRF_K);
    }

    public static List<Document> reciprocalRankFusion(List<List<Document>> rankedLists, int topK, int k) {
        Map<String, RankedDoc> accumulator = new LinkedHashMap<>();
        for (List<Document> rankedList : rankedLists) {
            if (rankedList == null) {
                continue;
            }
            for (int rank = 0; rank < rankedList.size(); rank++) {
                Document doc = rankedList.get(rank);
                String key = docKey(doc);
                double score = 1.0 / (k + rank + 1);
                RankedDoc existing = accumulator.get(key);
                if (existing == null) {
                    accumulator.put(key, new RankedDoc(doc, score));
                } else {
                    existing.score += score;
                }
            }
        }
        return accumulator.values().stream()
                .sorted(Comparator.comparingDouble((RankedDoc r) -> r.score).reversed())
                .limit(topK)
                .map(r -> r.doc)
                .toList();
    }

    /**
     * 按文本去重，保持首次出现顺序。
     */
    public static List<Document> deduplicate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, Document> seen = new LinkedHashMap<>();
        for (Document doc : documents) {
            seen.putIfAbsent(docKey(doc), doc);
        }
        return new ArrayList<>(seen.values());
    }

    private static String docKey(Document doc) {
        String text = doc == null ? null : doc.getText();
        return text == null ? "" : text.strip();
    }

    private static final class RankedDoc {
        final Document doc;
        double score;

        RankedDoc(Document doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }
}
