package com.itajay.superassistant.rag;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Lucene 的 BM25 关键字索引。
 * <p>
 * 使用 {@link SmartChineseAnalyzer} 做中文分词，{@link BM25Similarity} 计算相关性，
 * 与向量库（Milvus）配合，构成"向量 + 关键字"的多路召回。
 * 索引通过 {@link FSDirectory} 落盘，应用重启后无需重新导入。
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    public static final String CONTENT_FIELD = "content";
    private static final String ID_FIELD = "id";

    private final Analyzer analyzer;
    private final FSDirectory directory;
    private final IndexWriter writer;
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    public Bm25Index(@Value("${rag.retrieval.bm25.index-path:./data/bm25-index}") String indexPath) throws IOException {
        this.analyzer = new SmartChineseAnalyzer();
        Path path = Path.of(indexPath);
        this.directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setSimilarity(new BM25Similarity());
        this.writer = new IndexWriter(directory, config);
        log.info("BM25 index opened at {}", path.toAbsolutePath());
    }

    /**
     * 将文档分块写入 BM25 索引。
     */
    public synchronized void add(List<org.springframework.ai.document.Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            List<Document> luceneDocs = new ArrayList<>(chunks.size());
            for (org.springframework.ai.document.Document chunk : chunks) {
                String text = chunk.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                Document doc = new Document();
                doc.add(new StringField(ID_FIELD, nextId(), Field.Store.YES));
                doc.add(new TextField(CONTENT_FIELD, text, Field.Store.YES));
                luceneDocs.add(doc);
            }
            writer.addDocuments(luceneDocs);
            writer.commit();
            log.debug("BM25 indexed {} chunks", luceneDocs.size());
        } catch (IOException e) {
            log.error("Failed to index chunks into BM25 index", e);
        }
    }

    /**
     * 关键字检索：用与索引一致的分析器对查询分词，构造 BooleanQuery（SHOULD）按 BM25 打分。
     *
     * @return 按 BM25 相关性降序的文档
     */
    public synchronized List<org.springframework.ai.document.Document> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            Set<String> terms = analyzeQuery(queryText);
            if (terms.isEmpty()) {
                return List.of();
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (String term : terms) {
                builder.add(new TermQuery(new Term(CONTENT_FIELD, term)), BooleanClause.Occur.SHOULD);
            }
            BooleanQuery query = builder.build();

            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            TopDocs topDocs = searcher.search(query, topK);

            List<org.springframework.ai.document.Document> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document luceneDoc = reader.storedFields().document(scoreDoc.doc);
                String text = luceneDoc.get(CONTENT_FIELD);
                if (text != null) {
                    results.add(new org.springframework.ai.document.Document(text));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed for query '{}': {}", queryText, e.getMessage());
            return List.of();
        }
    }

    private Set<String> analyzeQuery(String text) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(CONTENT_FIELD, text)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(attr.toString());
            }
            stream.end();
        }
        return terms;
    }

    private String nextId() {
        return UUID.randomUUID() + "-" + idGenerator.incrementAndGet();
    }

    @PreDestroy
    public synchronized void close() {
        try {
            writer.close();
            directory.close();
        } catch (IOException e) {
            log.warn("Failed to close BM25 index", e);
        }
    }
}
