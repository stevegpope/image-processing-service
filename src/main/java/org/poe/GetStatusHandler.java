package org.poe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

public class GetStatusHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ImageStatusRepository repository = new ImageStatusRepository();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        InvocationContext ctx = InvocationContext.fromContext(context, "status");

        try {
            // Extract imageId from path parameters: /status/{imageId}
            Map<String, String> params = request.getQueryStringParameters();
            String imageId = params.get("imageId");

            if (imageId == null || imageId.isBlank()) {
                ctx.log("missing_image_id");
                return createResponse(400, Map.of("error", "Missing imageId parameter"));
            }

            ctx.setImageId(imageId);
            ctx.log(InvocationContext.RequestReceived);

            // Query DynamoDB via the Repository
            Optional<String> status = repository.getStatus(imageId);

            if (status.isEmpty()) {
                ctx.log("status_not_found");
                return createResponse(404, Map.of("error", "Image processing record not found or expired"));
            }

            ctx.log("status_retrieved", Map.of("status", status.get()));

            return createResponse(200, Map.of(
                    "imageId", imageId,
                    "status", status.get()
            ));
        } catch (Exception e) {
            ctx.log("error_fetching_status", Map.of("error", e.getMessage()));

            // Fallback response if everything fails, ensuring we still don't throw checked exceptions
            return createErrorFallbackResponse();
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ))
                    .withBody(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            // Converts the checked exception into an unchecked RuntimeException
            throw new RuntimeException("Failed to serialize response JSON", e);
        }
    }

    private APIGatewayProxyResponseEvent createErrorFallbackResponse() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*"
                ))
                .withBody("{\"error\": \"Internal server error\"}");
    }
}
