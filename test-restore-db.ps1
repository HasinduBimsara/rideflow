param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$backupPath = (Resolve-Path -LiteralPath $BackupFile).Path

if ((Get-Item -LiteralPath $backupPath).PSIsContainer) {
    throw "BackupFile must be a file."
}

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$testDb = "rides_restore_$suffix"
$containerPath = "/tmp/restore-$suffix.dump"
$databaseCreated = $false

try {
    docker compose cp $backupPath "postgres:$containerPath"

    if ($LASTEXITCODE -ne 0) {
        throw "Copying the backup failed."
    }

    docker compose exec -T postgres createdb `
        -U ride -T template0 $testDb

    if ($LASTEXITCODE -ne 0) {
        throw "Creating the test database failed."
    }

    $databaseCreated = $true

    docker compose exec -T postgres pg_restore `
        -U ride -d $testDb --exit-on-error $containerPath

    if ($LASTEXITCODE -ne 0) {
        throw "Restoring the backup failed."
    }

    $query = @"
SELECT 'rides' AS table_name, COUNT(*) FROM rides
UNION ALL
SELECT 'payments', COUNT(*) FROM payments
UNION ALL
SELECT 'trip_history', COUNT(*) FROM trip_history;
"@

    docker compose exec -T postgres psql `
        -U ride -d $testDb -v ON_ERROR_STOP=1 -c $query

    if ($LASTEXITCODE -ne 0) {
        throw "Checking restored tables failed."
    }

    Write-Host "Restore test passed." -ForegroundColor Green
}
finally {
    if ($databaseCreated) {
        docker compose exec -T postgres dropdb -U ride $testDb

        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove test database: $testDb"
        }
    }

    docker compose exec -T postgres rm -f $containerPath
}