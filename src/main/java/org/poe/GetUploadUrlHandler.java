package org.poe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.*;

public class GetUploadUrlHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final S3Presigner presigner = S3Presigner.create();
    private final String BUCKET_NAME = System.getenv("UPLOAD_BUCKET");
    private static final ImageStatusRepository repository = new ImageStatusRepository();

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/heic"
    );

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        InvocationContext ctx = InvocationContext.fromContext(context,"upload");

        try {
            String imageId = UUID.randomUUID().toString();

            ctx.setImageId(imageId);
            ctx.log(InvocationContext.RequestReceived);

            Map<String, String> params = Optional.ofNullable(request.getQueryStringParameters())
                    .orElse(Collections.emptyMap());

            String contentType = params.get("contentType");

            if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
                if (contentType != null) {
                    ctx.log("upload validation failed", Map.of("contentType", contentType));
                }
                return badRequest();
            }

            String extension = getExtensionFromContentType(contentType);
            String objectKey = imageId + extension;

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(objectKey)
                    .contentType(contentType)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .putObjectRequest(putRequest)
                    .build();

            String uploadUrl = presigner.presignPutObject(presignRequest).url().toString();

            ctx.log("presigned_url_generated", Map.of("objectKey", objectKey));

            repository.createEntry(imageId, extension);

            // Build response JSON (Safely avoiding custom string concat bugs)
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("uploadUrl", uploadUrl);
            responseMap.put("imageId", imageId);
            responseMap.put("maxSize", MAX_FILE_SIZE);

            String bodyJson = new ObjectMapper().writeValueAsString(responseMap);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(bodyJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            ctx.log("Error: " + e);
            throw new RuntimeException(e);
        }
    }

    private String getExtensionFromContentType(String contentType) {
        switch (contentType) {
            case "image/jpeg" -> {
                return ".jpg";
            }
            case "image/png" -> {
                return ".png";
            }
            case "image/webp" -> {
                return ".webp";
            }
            case "image/gif" -> {
                return ".gif";
            }
            case "image/heic" -> {
                return ".heic";
            }
            default -> {
                return "";
            }
        }
    }

    private APIGatewayProxyResponseEvent badRequest() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody("{\"error\": \"" + "Invalid or unsupported contentType" + "\"}");
    }
}
