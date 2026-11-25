param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'stop', 'settings', 'status', 'reset')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker zakonczyl polecenie bledem ($LASTEXITCODE)."
    }
}

$project = 'sllapp-offline'
$composeArguments = @(
    'compose', '--project-name', $project,
    '--env-file', 'offline/images.env', '-f', 'compose.offline.yaml'
)

function Invoke-Compose {
    Invoke-Docker @composeArguments @args
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Brak polecenia docker. Zainstaluj Docker Desktop z Compose.'
    }
    $engine = & docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker nie dziala. Uruchom Docker Desktop i sprobuj ponownie.'
    }
    if ($engine -ne 'linux') {
        throw 'Przelacz Docker Desktop w tryb kontenerow linuksowych.'
    }
    Invoke-Docker compose version | Out-Null

    foreach ($file in @('compose.offline.yaml', 'offline/images.env')) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Brakuje pliku $file. Rozpakuj pelny ZIP ponownie."
        }
    }
    Remove-Item Env:SLLAPP_IMAGE_TAG, Env:SLLAPP_PLATFORM -ErrorAction SilentlyContinue

    switch ($Action) {
        'start' {
            foreach ($file in @('offline/application.yml', 'model/artifacts/model.joblib')) {
                if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                    throw "Brakuje pliku $file. Sprawdz README.md."
                }
            }
            $required = @(Invoke-Compose config --images)
            $available = @(Invoke-Docker image ls --format '{{.Repository}}:{{.Tag}}')
            $missing = @($required | Where-Object { $_ -notin $available })
            if ($missing.Count -gt 0) {
                $archive = 'offline/images.tar.gz'
                if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
                    throw "Brakuje $archive. Rozpakuj pelny ZIP ponownie."
                }
                Write-Host 'Importowanie obrazow. Pierwsze uruchomienie moze potrwac.'
                Invoke-Docker image load -i $archive
            }
            Invoke-Compose up --wait --wait-timeout 120
            Start-Process 'http://127.0.0.1:8088'
        }
        'stop' {
            Invoke-Compose stop
            Write-Host 'Gra zatrzymana. Ranking zachowany.'
        }
        'settings' {
            if (-not (Test-Path -LiteralPath 'offline/application.yml' -PathType Leaf)) {
                throw 'Brakuje offline/application.yml. Sprawdz README.md.'
            }
            $running = @(Invoke-Compose ps --status running --services)
            if ('frontend' -notin $running -or 'model' -notin $running) {
                throw 'Najpierw wlacz gre przez Uruchom-gre.bat.'
            }
            $backend = Invoke-Compose ps -a -q backend
            if (-not $backend) {
                throw 'Brak kontenera backendu. Uruchom gre z tej paczki.'
            }
            $backendImage = Invoke-Docker inspect --format '{{.Config.Image}}' $backend
            $packageImages = @(Invoke-Compose config --images)
            if ($backendImage -notin $packageImages) {
                throw 'Dziala backend z innej paczki. Uzyj skryptu z uruchomionej wersji.'
            }
            try {
                Invoke-Compose up --wait --wait-timeout 120 --force-recreate --no-deps backend
            } catch {
                Invoke-Compose logs --tail 40 backend
                throw 'Backend nie wczytal ustawien. Sprawdz logi powyzej i README.md.'
            }
            Write-Host 'Ustawienia zastosowane. Rozpocznij nowa sesje.'
        }
        'status' {
            Invoke-Compose ps -a
            Invoke-Compose logs --tail 40
        }
        'reset' {
            $volume = "${project}_ranking"
            $volumes = @(Invoke-Docker volume ls --format '{{.Name}}')
            if ($volume -notin $volumes) {
                Write-Host 'Brak zapisanej bazy offline.'
                break
            }
            $volumeInfo = Invoke-Docker volume inspect $volume | Out-String | ConvertFrom-Json
            if ($volumeInfo.Labels.'com.docker.compose.project' -cne $project -or
                $volumeInfo.Labels.'com.docker.compose.volume' -cne 'ranking') {
                throw 'Wolumen nie nalezy do projektu offline. Przerwano reset.'
            }
            Write-Host 'Reset zatrzyma gre i trwale usunie zapisane wyniki offline.'
            if ((Read-Host 'Wpisz RESET, aby potwierdzic') -cne 'RESET') {
                Write-Host 'Anulowano. Ranking zachowany.'
                break
            }
            Invoke-Compose down
            Invoke-Docker volume rm $volume
            Write-Host 'Ranking wyczyszczony. Uruchom gre ponownie.'
        }
    }
} catch {
    Write-Host ''
    Write-Host $_.Exception.Message
    Write-Host 'Otworz README.md lub uruchom Sprawdz-stan.bat, aby znalezc przyczyne.'
    exit 1
}
