package com.itajay.superassistant.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class VectorConfig {
    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusServiceClient, EmbeddingModel embeddingModel){
        return MilvusVectorStore
                .builder(milvusServiceClient,embeddingModel)
                .build();
    }

    @Bean
    public MilvusServiceClient milvusServiceClient(){
        ConnectParam build = ConnectParam.newBuilder()
                .withHost("localhost")
                .withPort(9090)
                .build();

        return new MilvusServiceClient(build);
    }
}
