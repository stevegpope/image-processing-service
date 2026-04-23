resource "aws_dynamodb_table" "image_status" {
  name         = "${var.project}-image-status"
  billing_mode = "PAY_PER_REQUEST" # Serverless on-demand scaling
  hash_key     = "imageId"

  # Strong production safety guards
  deletion_protection_enabled = true
  point_in_time_recovery {
    enabled = true
  }

  attribute {
    name = "imageId"
    type = "S"
  }

  # Auto-deletes records to keep the table clean
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  # Production encryption using AWS managed keys
  server_side_encryption {
    enabled     = true
    kms_key_arn = null # Uses the default AWS-managed 'aws/dynamodb' key
  }

  tags = {
    Environment = "production"
    Project     = var.project
  }
}
