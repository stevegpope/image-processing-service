# Lambda assume role
data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "logging_base" {
  statement {
    actions   = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents"
    ]
    resources = ["*"]
  }

  # Bucket-level permissions (The "Bucket" itself)
  statement {
    actions = ["s3:ListBucket"]
    resources = [
      aws_s3_bucket.raw.arn,
      aws_s3_bucket.processed.arn
    ]
  }
}
# Get Upload Url
resource "aws_iam_role" "upload_role" {
  name               = "${local.name}-upload-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "upload_policy" {
  source_policy_documents = [data.aws_iam_policy_document.logging_base.json]

  statement {
    actions = [
      "s3:PutObject"
    ]
    resources = [
      "${aws_s3_bucket.raw.arn}/*"
    ]
  }

  statement {
    actions   = [
      "dynamodb:PutItem"
    ]
    resources = [aws_dynamodb_table.image_status.arn]
  }
}

resource "aws_iam_role_policy" "upload_policy_attach" {
  role   = aws_iam_role.upload_role.id
  policy = data.aws_iam_policy_document.upload_policy.json
}

# Get Download Url
resource "aws_iam_role" "download_role" {
  name               = "${local.name}-download-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "download_policy" {
  source_policy_documents = [data.aws_iam_policy_document.logging_base.json]

  statement {
    actions = [
      "s3:GetObject"
    ]
    resources = [
      "${aws_s3_bucket.processed.arn}/*"
    ]
  }

  statement {
    actions   = [
      "dynamodb:GetItem"
    ]
    resources = [aws_dynamodb_table.image_status.arn]
  }
}

resource "aws_iam_role_policy" "download_policy_attach" {
  role   = aws_iam_role.download_role.id
  policy = data.aws_iam_policy_document.download_policy.json
}

# Processor
resource "aws_iam_role" "processor_role" {
  name               = "${local.name}-processor-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "processor_policy" {
  source_policy_documents = [data.aws_iam_policy_document.logging_base.json]

  statement {
    actions = [
      "s3:GetObject",
      "s3:PutObject"
    ]
    resources = [
      "${aws_s3_bucket.processed.arn}/*"
    ]
  }

  statement {
    actions = [
      "s3:GetObject",
      "s3:DeleteObject"
    ]
    resources = [
      "${aws_s3_bucket.raw.arn}/*"
    ]
  }

  statement {
    actions   = [
      "dynamodb:GetItem",
      "dynamodb:UpdateItem"
    ]
    resources = [aws_dynamodb_table.image_status.arn]
  }

  statement {
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes"
    ]
    resources = [aws_sqs_queue.processing.arn]
  }
}

resource "aws_iam_role_policy" "processor_policy_attach" {
  role   = aws_iam_role.processor_role.id
  policy = data.aws_iam_policy_document.processor_policy.json
}

# Get Status
resource "aws_iam_role" "status_role" {
  name               = "${local.name}-status-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "status_policy" {
  source_policy_documents = [data.aws_iam_policy_document.logging_base.json]

  statement {
    actions   = [
      "dynamodb:GetItem"
    ]
    resources = [aws_dynamodb_table.image_status.arn]
  }
}

resource "aws_iam_role_policy" "status_policy_attach" {
  role   = aws_iam_role.status_role.id
  policy = data.aws_iam_policy_document.status_policy.json
}

resource "aws_iam_role" "codedeploy" {
  name = "${local.name}-codedeploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "codedeploy.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "codedeploy" {
  role       = aws_iam_role.codedeploy.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSCodeDeployRoleForLambda"
}
