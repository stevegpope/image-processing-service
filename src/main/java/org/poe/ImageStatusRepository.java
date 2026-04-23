package org.poe;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ImageStatusRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String ERROR = "ERROR";

    // Best practice: Initialize client once and reuse it across Lambda invocations
    public ImageStatusRepository() {
        String tableName = System.getenv("TABLE_NAME");
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalStateException("TABLE_NAME environment variable is missing!");
        }

        this.tableName = tableName;
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(Region.US_EAST_2)
                .build();
    }

    public void createEntry(String imageId, String extension) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("imageId", AttributeValue.builder().s(imageId).build());
        item.put("extension", AttributeValue.builder().s(extension).build());
        item.put("status", AttributeValue.builder().s(PROCESSING).build());

        // Set TTL for 6 hours
        long expiresAt = Instant.now().plusSeconds(21600).getEpochSecond();
        item.put("expiresAt", AttributeValue.builder().n(String.valueOf(expiresAt)).build());

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(this.tableName)
                .item(item)
                .build());
    }

    public void updateStatus(String imageId, String status) {
        long expiresAt = Instant.now().plusSeconds(21600).getEpochSecond();

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(this.tableName)
                .key(Map.of("imageId", AttributeValue.builder().s(imageId).build()))
                // #s handles the reserved word "status"
                .updateExpression("SET #s = :status, expiresAt = :ttl")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", AttributeValue.builder().s(status).build(),
                        ":ttl", AttributeValue.builder().n(String.valueOf(expiresAt)).build()
                ))
                .build());
    }

    /**
     * Retrieves the current status of an image.
     * Maps to the client polling Lambda.
     */
    public Optional<String> getStatus(String imageId) {
        var returnedItem = getStatusObject(imageId);
        return Optional.ofNullable(returnedItem.get("status").s());
    }

    public Optional<String> getExtension(String imageId) {
        Map<String, AttributeValue> item = getStatusObject(imageId);
        if (item == null || !item.containsKey("extension")) {
            return Optional.empty();
        }

        // .s() gets the raw string value without the AttributeValue wrapper
        return Optional.ofNullable(item.get("extension").s());
    }

    public Map<String, AttributeValue> getStatusObject(String imageId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("imageId", AttributeValue.builder().s(imageId).build());

        GetItemRequest request = GetItemRequest.builder()
                .tableName(this.tableName)
                .key(key)
                .build();

        Map<String, AttributeValue> returnedItem = dynamoDbClient.getItem(request).item();

        if (returnedItem == null || returnedItem.isEmpty()) {
            return null;
        }

        // Safety check for DynamoDB TTL lag: Ensure record hasn't expired yet
        if (returnedItem.containsKey("expiresAt")) {
            long expiresAt = Long.parseLong(returnedItem.get("expiresAt").n());
            if (Instant.now().getEpochSecond() > expiresAt) {
                return null;
            }
        }

        return returnedItem;
    }
}
