#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $PaperJar,
    [Parameter(Mandatory)][string] $StarCoreJar,
    [Parameter(Mandatory)][string] $StarWorldJar,
    [Parameter(Mandatory)][string] $FixtureJar,
    [string] $BootstrapDirectory,
    [Parameter(Mandatory)][string] $StarCoreConfig,
    [Parameter(Mandatory)][string] $StarWorldConfigDirectory,
    [Parameter(Mandatory)][string] $RuntimeDirectory,
    [ValidateRange(1024, 65535)][int] $Port
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-File([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (!(Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Expected a local file: $Path" }
    return $resolved
}

$PaperJar = Resolve-File $PaperJar
$StarCoreJar = Resolve-File $StarCoreJar
$StarWorldJar = Resolve-File $StarWorldJar
$FixtureJar = Resolve-File $FixtureJar
$StarCoreConfig = Resolve-File $StarCoreConfig
if ($BootstrapDirectory) { $BootstrapDirectory = (Resolve-Path -LiteralPath $BootstrapDirectory).Path }
$StarWorldConfigDirectory = (Resolve-Path -LiteralPath $StarWorldConfigDirectory).Path
$RuntimeDirectory = [IO.Path]::GetFullPath($RuntimeDirectory)
if ($RuntimeDirectory -eq [IO.Path]::GetPathRoot($RuntimeDirectory)) { throw 'A filesystem root cannot be a test runtime.' }
if (Test-Path -LiteralPath $RuntimeDirectory) { throw "Runtime must not already exist: $RuntimeDirectory" }

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } finally { $listener.Stop() }

New-Item -ItemType Directory -Path $RuntimeDirectory | Out-Null
foreach ($folder in @('cache', 'libraries', 'versions')) {
    $destination = Join-Path $RuntimeDirectory $folder
    if ($BootstrapDirectory) {
        $source = Join-Path $BootstrapDirectory $folder
        if (!(Test-Path -LiteralPath $source -PathType Container)) { throw "Missing Paper bootstrap folder: $source" }
        Copy-Item -LiteralPath $source -Destination $RuntimeDirectory -Recurse
    } else {
        New-Item -ItemType Directory -Path $destination | Out-Null
    }
}

$plugins = Join-Path $RuntimeDirectory 'plugins'
$core = Join-Path $plugins 'StarCore'
$world = Join-Path $plugins 'StarWorld'
New-Item -ItemType Directory -Path $core,$world | Out-Null
Copy-Item -LiteralPath $PaperJar -Destination (Join-Path $RuntimeDirectory 'paper.jar')
Copy-Item -LiteralPath $StarCoreJar -Destination (Join-Path $plugins 'StarCore.jar')
Copy-Item -LiteralPath $StarWorldJar -Destination (Join-Path $plugins 'StarWorld.jar')
Copy-Item -LiteralPath $FixtureJar -Destination (Join-Path $plugins 'StarWorldFixture.jar')
Copy-Item -LiteralPath $StarCoreConfig -Destination (Join-Path $core 'config.yml')
$definitions = Get-ChildItem -LiteralPath $StarWorldConfigDirectory -File -Filter '*.yml'
if (!($definitions.Name -contains 'climate.yml') -or !($definitions.Name -contains 'config.yml')) {
    throw 'StarWorld config source must contain climate.yml and config.yml.'
}
Copy-Item -LiteralPath $definitions.FullName -Destination $world

$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $RuntimeDirectory '.starworld-smoke-runtime'), "StarWorld isolated Paper smoke runtime v1`n", $utf8)
[IO.File]::WriteAllText((Join-Path $RuntimeDirectory 'eula.txt'), "eula=false`n", $utf8)
$properties = @"
server-ip=127.0.0.1
server-port=$Port
online-mode=false
enable-rcon=false
enable-query=false
enable-jmx-monitoring=false
max-players=4
view-distance=2
simulation-distance=2
spawn-protection=0
level-name=climate12-world
level-type=minecraft:flat
generate-structures=false
motd=StarWorld isolated climate acceptance
"@
[IO.File]::WriteAllText((Join-Path $RuntimeDirectory 'server.properties'), $properties + "`n", $utf8)

Write-Output "Prepared fresh localhost runtime: $RuntimeDirectory"
