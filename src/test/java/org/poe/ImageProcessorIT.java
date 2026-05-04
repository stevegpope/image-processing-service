package org.poe;

import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.Api;
import software.amazon.awssdk.services.apigatewayv2.model.GetApisRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetApisResponse;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

public class ImageProcessorIT {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessorIT.class);
    private static String apiBaseUrl;
    private static final String ENVIRONMENT = System.getProperty("environment", "dev");
    private static final String AWS_REGION = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    @BeforeAll
    static void setup() {
        apiBaseUrl = System.getProperty("apiBaseUrl");
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            apiBaseUrl = discoverApiBaseUrl();
        }
        log.info("Using API Base URL: {}", apiBaseUrl);
        RestAssured.baseURI = apiBaseUrl;
        RestAssured.config = RestAssured.config()
                .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false));
    }

    private static String discoverApiBaseUrl() {
        String apiName = "image-processor-" + ENVIRONMENT + "-api";
        log.info("Discovering API Gateway: {}", apiName);

        var builder = ApiGatewayV2Client.builder()
                .region(Region.of(AWS_REGION));

        String motoUrl = System.getenv("MOTO_URL");
        if (motoUrl != null && !motoUrl.isEmpty()) {
            log.info("Overriding API Gateway endpoint to: {}", motoUrl);
            builder.endpointOverride(URI.create(motoUrl));
        }

        try (ApiGatewayV2Client client = builder.build()) {
            GetApisResponse response = client.getApis(GetApisRequest.builder().build());
            return response.items().stream()
                    .filter(api -> api.name().equals(apiName))
                    .findFirst()
                    .map(Api::apiEndpoint)
                    .orElseThrow(() -> new RuntimeException("Could not find API Gateway with name: " + apiName));
        }
    }

    @Test
    void testFullImageProcessingPipeline() throws Exception {
        Path testImagePath = Paths.get("test", "test.jpg");
        File testImageFile = testImagePath.toFile();
        assertTrue(testImageFile.exists(), "Test image not found at: " + testImageFile.getAbsolutePath());

        // 1. REQUEST UPLOAD URL
        log.info("[1/5] Requesting upload URL...");
        Response uploadUrlResponse = given()
                .header("Content-Type", "application/json")
                .body("{}")
                .when()
                .post("/upload-url?contentType=image/jpeg");

        if (uploadUrlResponse.statusCode() != 200) {
            log.error("Upload URL request failed with status {}: {}", uploadUrlResponse.statusCode(), uploadUrlResponse.body().asString());
        }

        uploadUrlResponse.then().statusCode(200);

        String uploadS3Url = uploadUrlResponse.jsonPath().getString("uploadUrl");
        String imageId = uploadUrlResponse.jsonPath().getString("imageId");
        assertNotNull(uploadS3Url);
        assertNotNull(imageId);
        log.info("Success! ImageId: {}", imageId);

        // 2. UPLOAD IMAGE TO S3
        log.info("[2/5] Uploading image...");
        given()
                .header("Content-Type", "image/jpeg")
                .body(testImageFile)
                .when()
                .put(uploadS3Url)
                .then()
                .statusCode(200);
        log.info("Upload complete.");

        // 3. POLL FOR STATUS
        log.info("[3/5] Polling for processing status...");
        String status = "PROCESSING";
        int maxRetries = 30;
        for (int i = 1; i <= maxRetries; i++) {
            Response statusResponse = given()
                    .when()
                    .get("/status?imageId=" + imageId)
                    .then()
                    .extract().response();

            if (statusResponse.statusCode() == 200) {
                status = statusResponse.jsonPath().getString("status");
                log.info("Attempt {}/{}: Status = {}", i, maxRetries, status);
                if ("COMPLETED".equals(status)) {
                    break;
                }
                if ("FAILED".equals(status)) {
                    fail("Processing failed for image " + imageId);
                }
            } else {
                log.info("Attempt {}/{}: Waiting for record to appear... (Status: {})", i, maxRetries, statusResponse.statusCode());
            }
            Thread.sleep(2000);
        }
        assertEquals("COMPLETED", status, "Timed out waiting for image processing.");

        // 4. GET DOWNLOAD URL
        log.info("[4/5] Requesting download URL...");
        Response downloadUrlResponse = given()
                .when()
                .post("/download-url?imageId=" + imageId)
                .then()
                .statusCode(200)
                .extract().response();

        String downloadUrl = downloadUrlResponse.jsonPath().getString("downloadUrl");
        assertNotNull(downloadUrl);
        log.info("Got download URL.");

        // 5. DOWNLOAD RESULT
        log.info("[5/5] Downloading result...");
        byte[] downloadedImage = given()
                .when()
                .get(downloadUrl)
                .then()
                .statusCode(200)
                .extract().asByteArray();

        assertNotNull(downloadedImage);
        assertTrue(downloadedImage.length > 0);

        Path outputPath = Paths.get("test", "processed-test-java.jpg");
        java.nio.file.Files.write(outputPath, downloadedImage);
        log.info("Success! Processed image saved to: {}", outputPath.toAbsolutePath());
        log.info("--- Test Passed ---");
    }
}
