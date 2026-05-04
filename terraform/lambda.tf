############################
# PROCESSOR
############################

resource "aws_lambda_function" "processor" {
  function_name = "${local.name}-processor"
  role          = aws_iam_role.processor_role.arn
  handler       = "org.poe.ProcessImageHandler::handleRequest"
  runtime       = "java17"
  timeout       = 30
  memory_size   = 1024

  filename         = var.lambda_artifact
  source_code_hash = filebase64sha256(var.lambda_artifact)

  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      RAW_BUCKET       = aws_s3_bucket.raw.bucket
      PROCESSED_BUCKET = aws_s3_bucket.processed.bucket
      TABLE_NAME       = aws_dynamodb_table.image_status.name
    }
  }
}

resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.processor.function_name
  function_version = aws_lambda_function.processor.version

  lifecycle {
    ignore_changes = [
      function_version,
    ]
  }
}

resource "aws_lambda_event_source_mapping" "sqs_trigger" {
  event_source_arn = aws_sqs_queue.processing.arn
  function_name    = aws_lambda_alias.live.arn
  batch_size       = 1
}

############################
# GENERATE UPLOAD URL
############################

resource "aws_lambda_function" "upload" {
  function_name = "${local.name}-upload"

  role = aws_iam_role.upload_role.arn

  runtime = "java17"

  # 🔑 IMPORTANT: format is package.ClassName::method
  handler = "org.poe.GetUploadUrlHandler::handleRequest"

  filename         = var.lambda_artifact
  source_code_hash = filebase64sha256(var.lambda_artifact)

  timeout     = 10
  memory_size = 512

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      UPLOAD_BUCKET = aws_s3_bucket.raw.bucket
      TABLE_NAME    = aws_dynamodb_table.image_status.name
    }
  }

  # Helps cold starts a bit + observability
  tracing_config {
    mode = "Active"
  }

  # Optional but recommended
  ephemeral_storage {
    size = 512
  }
}


############################
# GENERATE DOWNLOAD URL
############################

resource "aws_lambda_function" "download" {
  function_name = "${local.name}-download"

  role = aws_iam_role.download_role.arn

  runtime = "java17"

  # 🔑 IMPORTANT: format is package.ClassName::method
  handler = "org.poe.GetDownloadUrlHandler::handleRequest"

  filename         = var.lambda_artifact
  source_code_hash = filebase64sha256(var.lambda_artifact)

  timeout     = 10
  memory_size = 512

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      PROCESSED_BUCKET = aws_s3_bucket.processed.bucket
      TABLE_NAME       = aws_dynamodb_table.image_status.name
    }
  }

  # Helps cold starts a bit + observability
  tracing_config {
    mode = "Active"
  }

  # Optional but recommended
  ephemeral_storage {
    size = 512
  }
}


############################
# GET STATUS
############################

resource "aws_lambda_function" "status" {
  function_name = "${local.name}-status"

  role = aws_iam_role.status_role.arn

  runtime = "java17"

  # 🔑 IMPORTANT: format is package.ClassName::method
  handler = "org.poe.GetStatusHandler::handleRequest"

  filename         = var.lambda_artifact
  source_code_hash = filebase64sha256(var.lambda_artifact)

  timeout     = 10
  memory_size = 512

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      TABLE_NAME = aws_dynamodb_table.image_status.name
    }
  }

  tracing_config {
    mode = "Active"
  }

  ephemeral_storage {
    size = 512
  }
}

############################
# VALIDATION HOOK
############################

resource "aws_lambda_function" "validation" {
  function_name = "${local.name}-validation"
  role          = aws_iam_role.validation_role.arn
  handler       = "org.poe.CodeDeployHookHandler::handleRequest"
  runtime       = "java17"
  timeout       = 30
  memory_size   = 512

  filename         = var.lambda_artifact
  source_code_hash = filebase64sha256(var.lambda_artifact)

  environment {
    variables = {
      TARGET_FUNCTION_NAME = aws_lambda_function.processor.function_name
    }
  }
}
