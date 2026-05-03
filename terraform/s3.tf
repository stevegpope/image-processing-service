resource "aws_s3_bucket" "raw" {
  bucket = "${local.name}-raw-${random_id.suffix.hex}"
}

resource "aws_s3_bucket" "processed" {
  bucket = "${local.name}-processed-${random_id.suffix.hex}"
}

resource "random_id" "suffix" {
  byte_length = 4
}

# Block all public access
resource "aws_s3_bucket_public_access_block" "raw_block" {
  bucket                  = aws_s3_bucket.raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "processed_block" {
  bucket                  = aws_s3_bucket.processed.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Expiration
resource "aws_s3_bucket_lifecycle_configuration" "processed_cleanup" {
  bucket = aws_s3_bucket.processed.id

  rule {
    id     = "auto-delete-processed-images"
    status = "Enabled"

    filter {}

    # Delete objects after 1 day
    expiration {
      days = 1
    }

    # Cleanup incomplete multipart uploads to save money
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# Encryption
resource "aws_s3_bucket_server_side_encryption_configuration" "raw_enc" {
  bucket = aws_s3_bucket.raw.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "processed_enc" {
  bucket = aws_s3_bucket.processed.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
