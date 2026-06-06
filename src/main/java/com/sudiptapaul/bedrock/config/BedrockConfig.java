package com.sudiptapaul.bedrock.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Slf4j
@Configuration
public class BedrockConfig {

    @Value("${aws.region}")
    private String awsRegion;

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        log.info("Initialising BedrockRuntimeClient in region: {}", awsRegion);
        return BedrockRuntimeClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public BedrockAgentRuntimeClient bedrockAgentRuntimeClient() {
        log.info("Initialising BedrockAgentRuntimeClient in region: {}", awsRegion);
        return BedrockAgentRuntimeClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        log.info("Initialising DynamoDbClient in region: {}", awsRegion);
        return DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
