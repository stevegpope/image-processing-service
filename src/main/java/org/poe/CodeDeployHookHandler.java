package org.poe;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.PutLifecycleEventHookExecutionStatusRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.core.SdkBytes;

import java.util.Map;

/**
 * CodeDeploy Hook for Lambda Blue/Green deployments.
 * This runs BEFORE traffic is shifted to the new version.
 */
public class CodeDeployHookHandler implements RequestHandler<Map<String, Object>, String> {

    private final CodeDeployClient cd = CodeDeployClient.create();
    private final LambdaClient lambda = LambdaClient.create();
    private final String functionToTest = System.getenv("TARGET_FUNCTION_NAME");

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        String deploymentId = (String) event.get("DeploymentId");
        String lifecycleEventHookExecutionId = (String) event.get("LifecycleEventHookExecutionId");

        System.out.println("Starting BeforeAllowTraffic hook for deployment: " + deploymentId);

        String status = "Succeeded";

        try {
            // Smoke test: Invoke the function we are deploying.
            // In a real scenario, you'd extract the TargetVersion from CodeDeploy or use an alias.
            // Here we just test if the function is responsive.
            
            System.out.println("Performing smoke test on: " + functionToTest);
            
            // Mock SQS event with an S3 test event payload
            String mockEvent = "{\"Records\": [{\"body\": \"{\\\"Event\\\": \\\"s3:TestEvent\\\"}\"}]}";
            
            InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                    .functionName(functionToTest)
                    .payload(SdkBytes.fromUtf8String(mockEvent))
                    .build());

            String result = response.payload().asUtf8String();
            System.out.println("Smoke test result: " + result);

            if (response.statusCode() != 200 || result.contains("Error")) {
                status = "Failed";
            }

        } catch (Exception e) {
            System.err.println("Validation failed: " + e.getMessage());
            status = "Failed";
        }

        System.out.println("Reporting status: " + status);

        cd.putLifecycleEventHookExecutionStatus(PutLifecycleEventHookExecutionStatusRequest.builder()
                .deploymentId(deploymentId)
                .lifecycleEventHookExecutionId(lifecycleEventHookExecutionId)
                .status(status)
                .build());

        return status;
    }
}
