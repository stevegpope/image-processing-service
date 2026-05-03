variable "region" {
  description = "The AWS region to deploy all resources into."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "A prefix used for naming resources to ensure uniqueness."
  type        = string
  default     = "image-processor"
}

variable "lambda_artifact" {
  description = "Path to the built Lambda artifact, supplied on command-line"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type = string
}