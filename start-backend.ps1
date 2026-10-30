$ErrorActionPreference = "Stop"

$backendPath = Join-Path $PSScriptRoot "backend"
$previousLocation = Get-Location
$previousPassword = $env:RIDEFLOW_MQTT_PASSWORD
$previousListenerSetting = $env:SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP

try {
    if (-not (Test-Path (Join-Path $backendPath "mvnw.cmd"))) {
        throw "Cannot find backend\mvnw.cmd."
    }

    $listeners = Get-NetTCPConnection `
        -LocalPort 8081 `
        -State Listen `
        -ErrorAction SilentlyContinue

    if ($listeners) {
        throw "Port 8081 is already in use. Stop the existing backend first."
    }

    $secret = Read-Host "Enter ingestion MQTT password" -AsSecureString

    $env:RIDEFLOW_MQTT_PASSWORD =
        [System.Net.NetworkCredential]::new("", $secret).Password

    Remove-Variable secret

    if ([string]::IsNullOrEmpty($env:RIDEFLOW_MQTT_PASSWORD)) {
        throw "MQTT password cannot be empty."
    }

    # Start matching workers normally.
    Remove-Item Env:\SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP `
        -ErrorAction SilentlyContinue

    Set-Location $backendPath

    & .\mvnw.cmd spring-boot:run

    if ($LASTEXITCODE -ne 0) {
        throw "Backend stopped with exit code $LASTEXITCODE."
    }
}
finally {
    $env:RIDEFLOW_MQTT_PASSWORD = $previousPassword
    $env:SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP =
        $previousListenerSetting

    Set-Location $previousLocation
}