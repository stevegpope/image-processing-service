package org.poe;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.services.lambda.runtime.Context;

import java.util.HashMap;
import java.util.Map;

public class InvocationContext {

    public static final String RequestReceived = "request_received";
    public static final String ProcessingImage = "processing_image";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -----------------------------
    // STATE (per invocation)
    // -----------------------------

    private final String requestId;
    private final String stage;
    private final LambdaLogger logger;
    private String imageId;

    public InvocationContext(
            Context context,
            String stage,
            String requestId) {
        this.stage = stage;
        this.requestId = requestId;
        this.logger = context.getLogger();
    }

    // -----------------------------
    // FACTORY METHODS
    // -----------------------------

    public static InvocationContext fromContext(
            Context context,
            String stage) {

        String requestId = extractRequestId(context);

        return new InvocationContext(context, stage, requestId);
    }

    // -----------------------------
    // LOGGING
    // -----------------------------

    public void log(String event) {
        log(event, null);
    }

    public void log(String event, Map<String, Object> extra) {
        try {
            Map<String, Object> log = new HashMap<>();

            log.put("event", event);
            log.put("stage", stage);
            log.put("requestId", requestId);
            log.put("imageId", imageId);
            if (imageId == null || imageId.isEmpty())
            {
                log.put("imageId", "empty");
            }

            if (extra != null) {
                log.put("extra", extra);
            }

            logger.log(MAPPER.writeValueAsString(log));

        } catch (Exception e) {
            logger.log("{\"log_error\":\"failed_to_serialize_log\"}");
        }
    }

    // -----------------------------
    // GETTERS
    // -----------------------------

    public String getRequestId() { return requestId; }
    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public String getStage() { return stage; }

    // -----------------------------
    // STATIC HELPERS
    // -----------------------------

    private static String extractRequestId(Context context) {
        if (context == null) return "none";

        try {
            return context.getAwsRequestId();
        } catch (Exception e) {
            return e.toString();
        }
    }
}