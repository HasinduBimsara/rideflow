$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force .\backups | Out-Null

$backupName = "rides-$(Get-Date -Format 'yyyyMMdd-HHmmss')-$([guid]::NewGuid().ToString('N').Substring(0,8)).dump"
$containerPath = "/tmp/$backupName"
$localPath = Join-Path $PSScriptRoot "backups\$backupName"

try {
    docker compose exec -T postgres pg_dump `
        -U ride -d rides -Fc -f $containerPath

    if ($LASTEXITCODE -ne 0) {
        throw "Database backup failed."
    }

    docker compose cp "postgres:$containerPath" $localPath

    if ($LASTEXITCODE -ne 0) {
        throw "Copying the backup failed."
    }

    if (!(Test-Path $localPath) -or (Get-Item $localPath).Length -eq 0) {
        throw "Backup file is missing or empty."
    }

    Write-Host "Backup created: $localPath" -ForegroundColor Green
}
finally {
    docker compose exec -T postgres rm -f $containerPath
}