# Deployment script for Drono App
# This script builds the debug APK and installs it on the specified devices

Write-Host "Starting Drono App deployment..." -ForegroundColor Cyan

# Change to the Android app directory
Set-Location -Path "android-app\IMadeThatBitchFamous"

# Build the debug APK
Write-Host "Building debug APK..." -ForegroundColor Yellow
& .\gradlew assembleDebug

# Check if build was successful
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "Build successful!" -ForegroundColor Green

# Get list of connected devices
$devices = & adb devices
$deviceCount = 0

# Install on specific devices
$targetDevices = @("R38N9014KDM", "R9WR310F4GJ")

foreach ($device in $targetDevices) {
    # Check if device is connected
    if ($devices -match $device) {
        Write-Host "Installing on device $device..." -ForegroundColor Yellow
        & adb -s $device install -r .\app\build\outputs\apk\debug\app-debug.apk
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Installation successful on $device!" -ForegroundColor Green
            $deviceCount++
        } else {
            Write-Host "Installation failed on $device with exit code $LASTEXITCODE" -ForegroundColor Red
        }
    } else {
        Write-Host "Device $device not connected. Skipping installation." -ForegroundColor Red
    }
}

# Return to root directory
Set-Location -Path "..\..\"

# Report results
if ($deviceCount -eq $targetDevices.Count) {
    Write-Host "Deployment completed successfully on all devices!" -ForegroundColor Cyan
} elseif ($deviceCount -gt 0) {
    Write-Host "Deployment completed successfully on $deviceCount out of $($targetDevices.Count) devices." -ForegroundColor Yellow
} else {
    Write-Host "Deployment failed on all devices." -ForegroundColor Red
} 