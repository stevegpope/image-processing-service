# ==============================================================================
# DeployTo-Dev.ps1
# Automates Maven Build, Terraform Apply, and AWS CodeDeploy Canary Traffic Shift
# ==============================================================================

# Ensure script stops running if an error occurs
$ErrorActionPreference = "Stop"

# Configuration Variables
$ProjectName = "image-processor-dev"
$LambdaFunctionName = "image-processor-dev-processor"
$DeploymentGroupName = "image-processor-group"
$RelativeArtifactPath = ".\target\image-processor-1.0.0.jar"
$ArtifactPath = [System.IO.Path]::GetFullPath($RelativeArtifactPath)

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Starting Deployment to Dev Environment" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# Maven Build
Write-Host "Building Java Fat JAR..." -ForegroundColor Yellow
mvn clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed!" }

# Safety Check: Verify the artifact was actually created
if (-not (Test-Path $ArtifactPath)) {
    throw "Artifact not found at $ArtifactPath after Maven build!"
}

# Terraform Apply
Write-Host "Applying Terraform Infrastructure..." -ForegroundColor Yellow
Set-Location -Path .\terraform

terraform apply -auto-approve `
  -var="environment=dev" `
  -var="lambda_artifact=$ArtifactPath"

if ($LASTEXITCODE -ne 0) {
    Set-Location -Path ..
    throw "Terraform apply failed!"
}

# Capture outputs from Terraform
$TerraformOutputs = terraform output -json | ConvertFrom-Json
$NewVersion = $TerraformOutputs.processor_version.value
Set-Location -Path ..

# Resolving versions deterministically
Write-Host "Resolving versions deterministically..." -ForegroundColor Yellow

$AliasResponse = aws lambda get-alias `
  --function-name $LambdaFunctionName `
  --name "live" | ConvertFrom-Json
$CurrentVersion = $AliasResponse.FunctionVersion

Write-Host "Current active version on alias 'live': $CurrentVersion" -ForegroundColor Gray
Write-Host "New version published by Terraform: $NewVersion" -ForegroundColor Green

if ($CurrentVersion -eq $NewVersion) {
    Write-Host "Current version matches new version. No deployment needed." -ForegroundColor Cyan
    exit 0
}

# Trigger CodeDeploy
Write-Host "Triggering CodeDeploy..." -ForegroundColor Yellow

$AppSpec = @{
    version   = "0.0"
    Resources = @(
        @{
            myLambdaFunction = @{
                Type       = "AWS::Lambda::Function"
                Properties = @{
                    Name           = $LambdaFunctionName
                    Alias          = "live"
                    CurrentVersion = $CurrentVersion
                    TargetVersion  = $NewVersion
                }
            }
        }
    )
} | ConvertTo-Json -Depth 10 -Compress

$Revision = @{
    revisionType   = "AppSpecContent"
    appSpecContent = @{
        content = $AppSpec
    }
} | ConvertTo-Json -Depth 10 -Compress

$Revision | Set-Content -Path revision.json

$DeployResponse = aws deploy create-deployment `
  --application-name $ProjectName `
  --deployment-group-name $DeploymentGroupName `
  --revision file://revision.json | ConvertFrom-Json

Remove-Item revision.json

$DeploymentId = $DeployResponse.deploymentId
Write-Host "Deployment triggered successfully!" -ForegroundColor Green
Write-Host "Deployment ID: $DeploymentId" -ForegroundColor Cyan

# Monitor Progress
Write-Host "Polling CodeDeploy status..." -ForegroundColor Yellow
aws deploy wait deployment-successful --deployment-id $DeploymentId

Write-Host "Deployment completed successfully!" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
