# Clean Project and Clear All Caches
# This script cleans Gradle build cache, Android Studio cache, and build artifacts

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Expressora Project Cache Cleaner" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Stop Gradle daemon
Write-Host "[1/6] Stopping Gradle daemon..." -ForegroundColor Yellow
& .\gradlew.bat --stop
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ✓ Gradle daemon stopped" -ForegroundColor Green
} else {
    Write-Host "  ⚠ Gradle daemon stop failed (may not be running)" -ForegroundColor Yellow
}
Write-Host ""

# Step 2: Clean Gradle build
Write-Host "[2/6] Cleaning Gradle build..." -ForegroundColor Yellow
& .\gradlew.bat clean
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ✓ Gradle build cleaned" -ForegroundColor Green
} else {
    Write-Host "  ✗ Gradle clean failed" -ForegroundColor Red
}
Write-Host ""

# Step 3: Delete build directories
Write-Host "[3/6] Deleting build directories..." -ForegroundColor Yellow
$buildDirs = @("app\build", "build")
foreach ($dir in $buildDirs) {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force $dir
        Write-Host "  ✓ Deleted $dir" -ForegroundColor Green
    } else {
        Write-Host "  - $dir does not exist" -ForegroundColor Gray
    }
}
Write-Host ""

# Step 4: Clean Android Studio cache
Write-Host "[4/6] Cleaning Android Studio cache..." -ForegroundColor Yellow
$ideaCacheDirs = @(
    ".idea\caches",
    ".idea\modules.xml",
    ".idea\workspace.xml",
    ".idea\compiler.xml",
    ".idea\gradle.xml",
    ".idea\misc.xml",
    ".idea\androidTestResultsUserPreferences.xml",
    ".idea\migrations.xml"
)
foreach ($item in $ideaCacheDirs) {
    if (Test-Path $item) {
        if (Test-Path $item -PathType Container) {
            Remove-Item -Recurse -Force $item
        } else {
            Remove-Item -Force $item
        }
        Write-Host "  ✓ Deleted $item" -ForegroundColor Green
    } else {
        Write-Host "  - $item does not exist" -ForegroundColor Gray
    }
}
Write-Host ""

# Step 5: Optional - Clean user-level Gradle cache (more aggressive)
Write-Host "[5/6] User-level Gradle cache..." -ForegroundColor Yellow
$userGradleCache = "$env:USERPROFILE\.gradle\caches"
if (Test-Path $userGradleCache) {
    Write-Host "  ⚠ User-level Gradle cache found at: $userGradleCache" -ForegroundColor Yellow
    Write-Host "  ⚠ This is optional and more aggressive. Skipping for safety." -ForegroundColor Yellow
    Write-Host "  ⚠ To clean it manually, run: Remove-Item -Recurse -Force '$userGradleCache'" -ForegroundColor Yellow
} else {
    Write-Host "  - User-level Gradle cache not found" -ForegroundColor Gray
}
Write-Host ""

# Step 6: Summary
Write-Host "[6/6] Summary" -ForegroundColor Yellow
Write-Host "  ✓ Gradle daemon stopped" -ForegroundColor Green
Write-Host "  ✓ Build directories cleaned" -ForegroundColor Green
Write-Host "  ✓ Android Studio cache cleaned" -ForegroundColor Green
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Cache cleaning complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host 'Next steps:' -ForegroundColor Yellow
Write-Host '  1. Clear app data on device:' -ForegroundColor White
Write-Host '     - Settings > Apps > Expressora > Storage > Clear Data' -ForegroundColor Gray
Write-Host '     - Or: adb shell pm clear com.example.expressora' -ForegroundColor Gray
Write-Host '  2. Rebuild the project in Android Studio' -ForegroundColor White
Write-Host '  3. Reinstall the app on your device' -ForegroundColor White
Write-Host ''

