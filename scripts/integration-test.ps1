param(
    [string]$Environment = "dev",
    [string]$ImagePath = (Join-Path $PSScriptRoot "..\test\test.jpg"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\test\processed-test.jpg")
)

$ErrorActionPreference = "Stop"

# =========================
# CONFIG
# =========================

$apiName = "image-processor-$Environment-api"
Write-Host "--- Image Processor Integration Test ---" -ForegroundColor Cyan
Write-Host "Environment: $Environment"
Write-Host "API Name:    $apiName"

# Get API Gateway endpoint dynamically
$api = aws apigatewayv2 get-apis `
    --query "Items[?Name=='$apiName']" `
    --output json | ConvertFrom-Json

if ($api -is [Array]) {
    $api = $api | Select-Object -First 1
}

if (-not $api) {
    Write-Error "Could not find API Gateway with name: $apiName"
    exit 1
}

$apiBase = $api.ApiEndpoint
Write-Host "API Base URL: $apiBase"

if (-not (Test-Path $ImagePath)) {
    Write-Error "Test image not found at: $ImagePath"
    exit 1
}

# =========================
# 1. REQUEST UPLOAD URL
# =========================

$uploadUrl = "$apiBase/upload-url?contentType=image/jpeg"
Write-Host "`n[1/5] Requesting upload URL..." -ForegroundColor Yellow
Write-Host "URL: $uploadUrl"

$uploadResponse = Invoke-RestMethod `
    -Method Post `
    -Uri $uploadUrl `
    -ContentType "application/json" `
    -Body "{}"

$uploadS3Url = $uploadResponse.uploadUrl
$imageId = $uploadResponse.imageId

Write-Host "Success! ImageId: $imageId"

# =========================
# 2. UPLOAD IMAGE TO S3
# =========================

Write-Host "`n[2/5] Uploading image..." -ForegroundColor Yellow
Write-Host "File: $ImagePath"

$headers = @{
    "Content-Type" = "image/jpeg"
}

$response = Invoke-RestMethod `
    -Method Put `
    -Uri $uploadS3Url `
    -Headers $headers `
    -InFile $ImagePath

Write-Host "Upload complete."

# =========================
# 3. POLL FOR STATUS
# =========================

$statusUrl = "$apiBase/status?imageId=$imageId"
Write-Host "`n[3/5] Polling for processing status..." -ForegroundColor Yellow
Write-Host "URL: $statusUrl"

$maxRetries = 30
$completed = $false

for ($i = 1; $i -le $maxRetries; $i++) {
    try {
        $statusResponse = Invoke-RestMethod -Method Get -Uri $statusUrl
        $status = $statusResponse.status
        Write-Host "Attempt $i/${maxRetries}: Status = $status"

        if ($status -eq "COMPLETED") {
            $completed = $true
            break
        }
        if ($status -eq "FAILED") {
            Write-Error "Processing failed for image $imageId"
            exit 1
        }
    }
    catch {
        Write-Host "Waiting for record to appear... ($($_.Exception.Message))" -ForegroundColor Gray
    }
    Start-Sleep -Seconds 2
}

if (-not $completed) {
    Write-Error "Timed out waiting for image processing."
    exit 1
}

# =========================
# 4. GET DOWNLOAD URL
# =========================

Write-Host "`n[4/5] Requesting download URL..." -ForegroundColor Yellow
$downloadResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$apiBase/download-url?imageId=$imageId"

$downloadUrl = $downloadResponse.downloadUrl
Write-Host "Got download URL."

# =========================
# 5. DOWNLOAD RESULT
# =========================

Write-Host "`n[5/5] Downloading result..." -ForegroundColor Yellow
Invoke-WebRequest -Uri $downloadUrl -OutFile $OutputPath

if (Test-Path $OutputPath) {
    Write-Host "Success! Processed image saved to: $OutputPath" -ForegroundColor Green
} else {
    Write-Error "Failed to download processed image."
    exit 1
}

Write-Host "`n--- Test Passed ---" -ForegroundColor Green
