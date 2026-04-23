package org.poe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GetDownloadUrlHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final S3Presigner presigner = S3Presigner.create();
    private final String PROCESSED_BUCKET = System.getenv("PROCESSED_BUCKET");
    private static final ImageStatusRepository repository = new ImageStatusRepository();

    // Basic allowlist to prevent traversal / abuse patterns
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "heic");

    // Prevent path traversal / weird keys
    private static final String KEY_SANITIZE_REGEX = "^[a-zA-Z0-9/._-]+$";

    // Limit exposure window
    private static final Duration URL_EXPIRY = Duration.ofMinutes(10);

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {
            InvocationContext ctx = InvocationContext.fromContext(context,"download");

            // Extract imageId from path parameters: /status/{imageId}
            Map<String, String> params = request.getQueryStringParameters();
            String imageId = params.get("imageId");
            if (imageId == null || imageId.isBlank() || !isValidUUID(imageId)) {
                ctx.log("missing_image_id");
                return response(400, "Missing or invalid image id");
            }

            ctx.setImageId(imageId);
            ctx.log(InvocationContext.RequestReceived);

            if (PROCESSED_BUCKET == null || PROCESSED_BUCKET.isEmpty()) {
                return response(500, "{\"error\":\"Server misconfigured\"}");
            }

            var extension = repository.getExtension(imageId);
            var fileName = imageId + extension.get();

            // 🔒 Prevent path traversal / injection-style keys
            if (!fileName.matches(KEY_SANITIZE_REGEX)) {
                return response(400, "{\"error\":\"Invalid fileName format\"}");
            }

            // Enforce prefix structure (extra hardening)
            if (fileName.startsWith("/") || fileName.contains("..")) {
                return response(400, "{\"error\":\"Invalid file path\"}");
            }

            // Build safe S3 request
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(PROCESSED_BUCKET)
                    .key(fileName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(URL_EXPIRY)
                    .getObjectRequest(getRequest)
                    .build();

            String downloadUrl = presigner.presignGetObject(presignRequest)
                    .url()
                    .toString();

            // Avoid leaking structure unnecessarily
            return response(200, "{\"downloadUrl\":\"" + downloadUrl + "\",\"expiresIn\":600}");

        } catch (Exception e) {
            // Don’t leak internals to client
            context.getLogger().log("ERROR generating download URL: " + e);
            return response(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private static APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Cache-Control", "no-store"
                ))
                .withBody(body);
    }

    public static boolean isValidUUID(String uuid) {
        // This is essentially 'TryParse'
        if (uuid == null || !uuid.matches("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")) {
            return false;
        }
        return true;
    }
}