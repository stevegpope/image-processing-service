resource "aws_cloudwatch_metric_alarm" "dlq_messages" {
  alarm_name          = "${var.project}-dlq-messages"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 1

  dimensions = {
    QueueName = aws_sqs_queue.dlq.name
  }
}

resource "aws_cloudwatch_log_group" "processor_logs" {
  name              = "/aws/lambda/${aws_lambda_function.processor.function_name}"
  retention_in_days = 3
}

resource "aws_cloudwatch_log_group" "upload_logs" {
  name              = "/aws/lambda/${aws_lambda_function.upload.function_name}"
  retention_in_days = 3
}

resource "aws_cloudwatch_log_group" "download_logs" {
  name              = "/aws/lambda/${aws_lambda_function.download.function_name}"
  retention_in_days = 3
}

resource "aws_cloudwatch_log_group" "status_logs" {
  name              = "/aws/lambda/${aws_lambda_function.status.function_name}"
  retention_in_days = 3
}

############################################
# Locals
############################################

locals {
  function_name = aws_lambda_function.processor.function_name
  alias_name    = aws_lambda_alias.live.name

  # Used for alias-scoped metrics
  resource_name = "${local.function_name}:${local.alias_name}"
}

############################################
# Lambda Errors Alarm (PRIMARY SIGNAL)
############################################

resource "aws_cloudwatch_metric_alarm" "lambda_errors" {
  alarm_name          = "${local.function_name}-errors"
  alarm_description   = "Lambda errors detected (alias-scoped)"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  threshold           = 1

  namespace   = "AWS/Lambda"
  metric_name = "Errors"
  statistic   = "Sum"
  period      = 60

  dimensions = {
    FunctionName = local.function_name
    Resource     = local.resource_name
  }

  treat_missing_data = "notBreaching"
}

############################################
# Lambda Duration Alarm (PERFORMANCE REGRESSION)
############################################

resource "aws_cloudwatch_metric_alarm" "lambda_duration" {
  alarm_name          = "${local.function_name}-duration"
  alarm_description   = "Lambda duration too high (possible slowdown)"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 10000 # ms (TODO: adjust based on metrics)

  namespace   = "AWS/Lambda"
  metric_name = "Duration"
  statistic   = "Average"
  period      = 60

  dimensions = {
    FunctionName = local.function_name
    Resource     = local.resource_name
  }

  treat_missing_data = "notBreaching"
}

############################################
# Lambda Throttles Alarm (CAPACITY ISSUES)
############################################

resource "aws_cloudwatch_metric_alarm" "lambda_throttles" {
  alarm_name          = "${local.function_name}-throttles"
  alarm_description   = "Lambda throttling detected"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  threshold           = 1

  namespace   = "AWS/Lambda"
  metric_name = "Throttles"
  statistic   = "Sum"
  period      = 60

  dimensions = {
    FunctionName = local.function_name
    Resource     = local.resource_name
  }

  treat_missing_data = "notBreaching"
}

############################################
# Composite Alarm (SMART ROLLBACK SIGNAL)
############################################

resource "aws_cloudwatch_composite_alarm" "deployment_health" {
  alarm_name        = "${local.function_name}-deployment-health"
  alarm_description = "Triggers if errors OR throttles OR high duration"

  alarm_rule = join(" OR ", [
    "ALARM(${aws_cloudwatch_metric_alarm.lambda_errors.alarm_name})",
    "ALARM(${aws_cloudwatch_metric_alarm.lambda_throttles.alarm_name})",
    "ALARM(${aws_cloudwatch_metric_alarm.lambda_duration.alarm_name})"
  ])
}