# =========================
# CONFIG
# =========================

# Get API Gateway endpoint dynamically
$api = aws apigatewayv2 get-apis `
    --query "Items[?Name=='image-processor-api'] | [0]" `
    --output json | ConvertFrom-Json

$apiBase = $api.ApiEndpoint

Write-Host "API Base URL:"
Write-Host $apiBase

$uploadUrl = "$apiBase/upload-url"

Write-Host "Upload endpoint:"
Write-Host $uploadUrl

$testImagePath = Join-Path $PSScriptRoot "test.jpg"

# =========================
# 1. REQUEST UPLOAD URL
# =========================

$finalUrl = $uploadUrl + "?contentType=image/jpeg"
Write-Host "Requesting upload URL...$finalUrl"

$uploadResponse = Invoke-RestMethod `
    -Method Post `
    -Uri $finalUrl `
    -ContentType "application/json" `
    -Body "{}"

if (-not $uploadResponse -or -not $uploadResponse.uploadUrl) {
    throw "Failed to get upload URL"
}

$uploadS3Url = $uploadResponse.uploadUrl
$imageId = $uploadResponse.imageId

Write-Host "Got upload URL"
Write-Host $uploadS3Url
Write-Host "ImageId: $imageId"

# =========================
# 2. UPLOAD IMAGE TO S3 (PRESIGNED URL)
# =========================

Write-Host "Uploading image..."

$headers = @{
    "Content-Type"        = "image/jpeg"
}

$response = Invoke-RestMethod `
    -Method Put `
    -Uri $uploadS3Url `
    -Headers $headers `
    -InFile $testImagePath

Write-Host "Response:"
Write-Host $response

if ($response -and $response.StatusCode -ne 200) {
    throw "Failed to upload image"
}

Write-Host "Upload complete"

$finalUrl = "$apiBase/status?imageId=$imageId"
Write-Host "Calling status URL...$finalUrl"

for ($i = 0; $i -lt 20; $i++) {

    try {

        $response = Invoke-RestMethod `
            -Method Get `
            -Uri $finalUrl

        Write-Host "Status: $response"

        if ($response.status -eq "COMPLETED") {
            break
        }
    }
    catch {
        # 1. Print the standard error message
        Write-Host "Message: $($_.Exception.Message)" -ForegroundColor Yellow

        # 2. If it is an HTTP error, this prints the response body from AWS
        if ($_.Exception.GetType().Name -eq "WebException" -and $_.Exception.Response) {
            $stream = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $responseBody = $stream.ReadToEnd()
            Write-Host "Server Response: $responseBody" -ForegroundColor Cyan
        }
    }

    Start-Sleep -Seconds 1
}

# =========================
# 4. GET DOWNLOAD URL
# =========================

Write-Host "Requesting processed image URL..."

$downloadResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$apiBase/download-url?imageId=$imageId"

$downloadUrl = $downloadResponse.downloadUrl

Write-Host "Download URL:"
Write-Host $downloadUrl

# =========================
# 5. DOWNLOAD RESULT
# =========================

$outputPath = ".\processed.jpg"

Invoke-WebRequest -Uri $downloadUrl -OutFile $outputPath

Write-Host "Saved processed image to $outputPath"

# =========================
# DONE
# =========================

Write-Host "Pipeline complete"