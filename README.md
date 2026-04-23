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
