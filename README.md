# Serverless Asynchronous Image Processor

This project is a high-scalability, event-driven image processing pipeline built on AWS. It was developed as a learning project to practice decoupled architectures on AWS.
---
## Architecture Overview

The system moves away from traditional synchronous "wait-for-response" uploads, instead utilizing an Asynchronous Request-Response Pattern.

* Direct-to-S3 Uploads: Client requests a Pre-Signed URL via API Gateway and Lambda, allowing secure, direct uploads to S3 without taxing server memory or bandwidth.
* Event-Driven Triggers: S3 triggers an event upon successful upload, pushing a message into Amazon SQS.
* Decoupled Processing: A dedicated Processor Lambda consumes SQS messages. If processing fails, SQS handles retries automatically without impacting the client.
* State Management & Polling: While the background worker processes the image, the client polls a lightweight DynamoDB table for status updates.
---
## Architectural Benefits

This approach has several benefits:
* Massive Scalability: By using SQS as a buffer, the system can handle thousands of simultaneous uploads without "bottlenecking" the processing Lambda.
* Cost Efficiency (Pay-per-use): There are no idle servers. We only pay for the milliseconds the code is running and the storage used.
* Least Privilege Security: Using granular IAM roles, the API layer can never delete files, and the Processor layer cannot interact with user authentication—minimizing the blast radius of any potential exploit.
* Resiliency: If the processing Lambda crashes, the SQS message remains in the queue. The system is "self-healing," ensuring no user data is lost during intermittent failures. Upon ultimate failure it is pushed into a dead letter queue.
* Optimized TTL (Time-to-Live): DynamoDB TTL is utilized to automatically purge old polling records, keeping the database lean and cost-effective without manual maintenance.
---
## Tech Stack

* Infrastructure: Terraform (IaC)
* Runtime: Java 17
* Gateway: Api Gateway
* Database: Amazon DynamoDB
* Compute: AWS Lambda
* Storage: Amazon S3
* Messaging: Amazon SQS

---
## Observability

* Failure Notifications: We use CloudWatch Alarms to immediately flag if the image processor crashes or if messages are piling up in the Dead Letter Queue (DLQ).
* Proactive Alerting: By monitoring the Errors metric and ApproximateNumberOfMessagesVisible, we know the system has failed before the user even reports it.
* Log Governance: Dedicated Log Groups for each Lambda function provide deep visibility into execution flow, with a 3-day retention policy to keep CloudWatch costs low while still allowing for effective debugging.
* Structured Context: Using the custom InvocationContext in our Java code, we ensure every log entry is searchable by imageId (our correlationId), making it easy to trace a single file's journey from upload to completion.

---
## Image Editing Process
Nothing too fancy, it is just a tech demo.
- adjust the intensity range of the image
- adjust the image brightness/contrast by stretching pixels so 0.35% of them become pure black or white
- recalculate the display boundaries to ensure the new contrast levels are visible in the final file.
- increases the value of every single pixel by 15% to globally brighten the image

## Sample Image
![Sample Image](./test/comparison.jpg)
