package org.poe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ij.IJ;
import ij.ImagePlus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ProcessImageHandler implements RequestHandler<SQSEvent, String> {
    private final S3Client s3 = S3Client.create();

    private final String PROCESSED_BUCKET = System.getenv("PROCESSED_BUCKET");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final ImageStatusRepository repository = new ImageStatusRepository();

    @Override
    public String handleRequest(SQSEvent event, Context context) {

        InvocationContext ctx = InvocationContext.fromContext(context,"process");
        File localFile = null;
        File outputFile = null;
        String imageId = null;

        try {
            // Since batch_size = 1, we only care about the first record
            SQSEvent.SQSMessage msg = event.getRecords().get(0);

            String bodyJson = msg.getBody();
            JsonNode rootNode = objectMapper.readTree(bodyJson);
            imageId = GetImageId(rootNode);
            ctx.setImageId(imageId);

            ctx.log(InvocationContext.ProcessingImage);

            JsonNode recordsNode = rootNode.get("Records");

            if (recordsNode == null || !recordsNode.isArray() || recordsNode.isEmpty()) {
                ctx.log("Error: Malformed S3 event. Missing Records array.");
                return "SKIPPED_MALFORMED";
            }

            JsonNode record = recordsNode.get(0);
            String sourceBucket = record.get("s3").get("bucket").get("name").asText();
            String rawKey = record.get("s3").get("object").get("key").asText();
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);

            // Safety check
            if (sourceBucket.equals(PROCESSED_BUCKET)) {
                ctx.log("Skipping already processed file", Map.of("key", key));
                return "SKIPPED_DUPLICATE";
            }

            String localPath = "/tmp/" + key.replace("/", "_");
            String outputFileName = "processed-" + key.replace("/", "_");
            String outputPath = "/tmp/" + outputFileName;

            localFile = new File(localPath);
            outputFile = new File(outputPath);

            ctx.log("download");

            // 1. Download
            s3.getObject(GetObjectRequest.builder().bucket(sourceBucket).key(key).build(),
                    localFile.toPath());

            ctx.log("process");

            // 2. Process
            ctx.log("process");

            // 1. Open the image without passing through any GUI methods
            ImagePlus imp = IJ.openImage(localPath);
            if (imp == null) {
                ctx.log("Error: ImageJ could not open file", Map.of("localPath", localPath));
                return "FAILED_IMAGE_LOAD";
            }

            ctx.log("contrast");
            // Headless-safe alternative to IJ.run(imp, "Enhance Contrast", "saturated=0.35")
            ij.plugin.ContrastEnhancer enhancer = new ij.plugin.ContrastEnhancer();

            // In ImageJ, we pass the ImagePlus and the saturated double directly into stretchHistogram
            enhancer.stretchHistogram(imp, 0.35);

            ctx.log("LUT");
            // Headless-safe alternative to IJ.run(imp, "Apply LUT", "")
            // This bakes the visual Contrast/LUT changes strictly into the raw pixel array
            ij.process.ImageProcessor ip = imp.getProcessor();
            ip.resetMinAndMax(); // Recalculates display bounds

            // ✨ Brighten the image by 15%
            ctx.log("brighten");
            ip.multiply(1.15); // 👈 Multiplies all pixel values by 1.15 to brighten

            // To properly bake it, map the changes using the processor directly
            // instead of createRGBImage / java.awt.Image transitions
            imp.setProcessor(ip.duplicate());

            ctx.log("save");
            String extensionWithDot = repository.getExtension(imageId).get();
            String extensionWithoutDot = extensionWithDot.substring(1);
            IJ.saveAs(imp, extensionWithoutDot, outputPath);

            ctx.log("upload");

            // 3. Upload (S3 saveAs often appends extension automatically, so we ensure the key is clean)
            String uploadKey = key.endsWith(extensionWithDot) ? key : key + extensionWithDot;
            s3.putObject(PutObjectRequest.builder().bucket(PROCESSED_BUCKET).key(uploadKey).build(),
                    RequestBody.fromFile(outputFile));

            // 4. Cleanup Source S3 Object
            s3.deleteObject(DeleteObjectRequest.builder().bucket(sourceBucket).key(key).build());

            repository.updateStatus(imageId, ImageStatusRepository.COMPLETED);
            ctx.log("processed");

        } catch (Exception e) {
            ctx.log("Fatal Error during processing", Map.of("error", e.toString()));

            if (imageId != null) {
                repository.updateStatus(imageId, ImageStatusRepository.ERROR);
            }

            // 🚀 Throwing ensures the message returns to SQS for retry
            throw new RuntimeException("Image processing failed", e);
        } finally {
            // 🚀 Ensure local /tmp files are deleted regardless of success or failure
            DeleteFile(localFile, ctx);
            DeleteFile(outputFile, ctx);
        }

        return "SUCCESS";
    }

    private void DeleteFile(File file, InvocationContext ctx)
    {
        if (file != null && file.exists()){
            if (!file.delete())
            {
                ctx.log("Warning, failed to delete " + file.getName());
            }
        }
    }

    private String GetImageId(JsonNode rootNode)
    {
        String objectKey = rootNode.path("Records")
                .get(0)
                .path("s3")
                .path("object")
                .path("key")
                .asText();

        return objectKey.substring(objectKey.lastIndexOf("/") + 1, objectKey.lastIndexOf("."));
    }
}
