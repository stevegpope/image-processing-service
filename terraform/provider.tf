terraform {
  required_version = ">= 1.6.0"

  backend "s3" {
    bucket       = "image-processor-terraform-state-634972095615"
    key          = "terraform.tfstate"
    region       = "us-east-1"
    use_lockfile = true
    encrypt      = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
  }
}

provider "aws" {
  region = var.region
}

# Ensure we do not smash the wrong environment by accident
resource "null_resource" "workspace_guard" {
  lifecycle {
    precondition {
      condition     = terraform.workspace == var.environment
      error_message = "Workspace (${terraform.workspace}) must match environment (${var.environment})"
    }
  }
}
