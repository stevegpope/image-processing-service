resource "aws_codedeploy_app" "lambda" {
  name             = local.name
  compute_platform = "Lambda"
}

resource "aws_codedeploy_deployment_group" "lambda" {
  app_name              = aws_codedeploy_app.lambda.name
  deployment_group_name = "image-processor-group"
  service_role_arn      = aws_iam_role.codedeploy.arn

  deployment_config_name = var.environment == "dev" ? "CodeDeployDefault.LambdaAllAtOnce" : "CodeDeployDefault.LambdaCanary10Percent5Minutes"

  deployment_style {
    deployment_type   = "BLUE_GREEN"
    deployment_option = "WITH_TRAFFIC_CONTROL"
  }

  alarm_configuration {
    enabled = true
    alarms  = [aws_cloudwatch_composite_alarm.deployment_health.alarm_name]
  }
}