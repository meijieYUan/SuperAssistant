package com.itajay.superassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;

    private final TokenTextSplitter tokenTextSplitter;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.tokenTextSplitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(500)
                .withKeepSeparator(true)
                .build();
    }

    public int addSource(Resource resource) {
        log.info("Processing resource: {}", resource.getFilename());

        List<Document> documents = readDocuments(resource);
        log.info("Read {} raw documents from {}", documents.size(), resource.getFilename());

        if (documents.isEmpty()) {
            log.warn("No documents extracted from {}", resource.getFilename());
            return 0;
        }

        List<Document> chunks = splitDocuments(documents);
        log.info("Split into {} chunks from {}", chunks.size(), resource.getFilename());

        storeDocuments(chunks);
        log.info("Stored {} chunks to vector store", chunks.size());

        return chunks.size();
    }

    public int addSources(List<Resource> resources) {
        List<Document> allChunks = new ArrayList<>();

        for (Resource resource : resources) {
            List<Document> documents = readDocuments(resource);
            if (!documents.isEmpty()) {
                allChunks.addAll(splitDocuments(documents));
            }
        }

        if (!allChunks.isEmpty()) {
            storeDocuments(allChunks);
            log.info("Batch stored {} chunks from {} resources", allChunks.size(), resources.size());
        }
        return allChunks.size();
    }

    private List<Document> readDocuments(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            log.warn("Resource has no filename, falling back to TextReader");
            return new TextReader(resource).read();
        }

        String lowerName = filename.toLowerCase();

        try {
            if (lowerName.endsWith(".pdf")) {
                return new ParagraphPdfDocumentReader(resource).read();

            } else if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
                return new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig()).read();

            } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".log")
                    || lowerName.endsWith(".java") || lowerName.endsWith(".py")
                    || lowerName.endsWith(".xml") || lowerName.endsWith(".json")
                    || lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")
                    || lowerName.endsWith(".html") || lowerName.endsWith(".css")
                    || lowerName.endsWith(".js") || lowerName.endsWith(".sql")) {
                return new TextReader(resource).read();

            } else {
                log.info("Unknown file type '{}', trying TextReader", filename);
                return new TextReader(resource).read();
            }
        } catch (Exception e) {
            log.error("Failed to read resource: {}", filename, e);
            return List.of();
        }
    }

    private List<Document> splitDocuments(List<Document> documents) {
        return tokenTextSplitter.split(documents);
    }

    private void storeDocuments(List<Document> chunks) {
        vectorStore.add(chunks);
    }
}