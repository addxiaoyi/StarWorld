#requires -Version 7.0
<#
.SYNOPSIS
Runs an isolated local Paper smoke test twice and verifies StarWorld's SQLite restart recovery.
.DESCRIPTION
Uses local Java, Paper and plugin JARs only. The runtime must already contain Paper's
Mojang bootstrap cache, or -BootstrapDirectory must name a previously prepared runtime.
This script never downloads assets. Existing nonempty runtimes require its exact sentinel.
Without -AgreeEula, an existing eula=true is respected; otherwise eula=false is preserved
and the Paper EULA gate is checked. -AgreeEula explicitly writes eula=true for this runtime.
No gameplay, CraftEngine, StarCore, or Folia integration is inferred from a startup check.
.EXAMPLE
./tools/smoke-paper.ps1 -JavaPath C:/jdk21/bin/java.exe -ServerJar C:/paper.jar `
  -PluginJar ./target/starworld-0.2.0.jar -RuntimeDirectory C:/starworld-test `
  -BootstrapDirectory C:/prepared-paper -AgreeEula
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $JavaPath,
    [Parameter(Mandatory)][string] $ServerJar,
    [Parameter(Mandatory)][string] $PluginJar,
    [Parameter(Mandatory)][string] $RuntimeDirectory,
    [string] $BootstrapDirectory,
    [ValidateRange(1024, 65535)][int] $Port = 25585,
    [ValidateRange(30, 600)][int] $StartupTimeoutSeconds = 180,
    [switch] $AgreeEula
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$sentinelText = 'StarWorld isolated Paper smoke runtime v1'

function Resolve-LocalFile([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (!(Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Expected a local file: $Path" }
    return $resolved
}
$JavaPath = Resolve-LocalFile $JavaPath
$ServerJar = Resolve-LocalFile $ServerJar
$PluginJar = Resolve-LocalFile $PluginJar
$RuntimeDirectory = [IO.Path]::GetFullPath($RuntimeDirectory)
if ($RuntimeDirectory -eq [IO.Path]::GetPathRoot($RuntimeDirectory)) { throw 'A filesystem root cannot be a smoke runtime.' }
if (Test-Path -LiteralPath $RuntimeDirectory -PathType Leaf) { throw 'RuntimeDirectory is a file.' }
$sentinel = Join-Path $RuntimeDirectory '.starworld-smoke-runtime'
if (Test-Path -LiteralPath $RuntimeDirectory) {
    $contents = @(Get-ChildItem -LiteralPath $RuntimeDirectory -Force)
    if ($contents.Count -gt 0 -and (!(Test-Path -LiteralPath $sentinel) -or
        [IO.File]::ReadAllText($sentinel).Trim() -ne $sentinelText)) {
        throw "Refusing to modify an unowned nonempty runtime: $RuntimeDirectory"
    }
} else { New-Item -ItemType Directory -Path $RuntimeDirectory | Out-Null }
[IO.File]::WriteAllText($sentinel, "$sentinelText`n", $utf8)
$lock = [IO.File]::Open((Join-Path $RuntimeDirectory '.smoke.lock'), [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    # Check availability before writing any configuration or starting Java.
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() } finally { $listener.Stop() }
    if ($BootstrapDirectory) {
        $BootstrapDirectory = (Resolve-Path -LiteralPath $BootstrapDirectory).Path
        foreach ($folder in @('cache', 'libraries', 'versions')) {
            $source = Join-Path $BootstrapDirectory $folder
            $destination = Join-Path $RuntimeDirectory $folder
            if (!(Test-Path -LiteralPath $source -PathType Container)) { throw "Missing bootstrap folder: $source" }
            if (!(Test-Path -LiteralPath $destination)) { Copy-Item -LiteralPath $source -Destination $destination -Recurse }
        }
    }
    # Paperclip's download context names the exact local Mojang asset and checksum.
    $archive = [IO.Compression.ZipFile]::OpenRead($ServerJar)
    try {
        $contextEntry = $archive.GetEntry('META-INF/download-context')
        if (!$contextEntry) { throw 'ServerJar is not a supported Paperclip JAR (download-context missing).' }
        $reader = [IO.StreamReader]::new($contextEntry.Open())
        try { $downloadContext = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    $parts = $downloadContext -split '\s+'
    $assetName = $parts | Where-Object { $_ -match '^[^/\\]+\.jar$' } | Select-Object -First 1
    $assetHash = $parts | Where-Object { $_ -match '^[a-fA-F0-9]{64}$' } | Select-Object -First 1
    if (!$assetName -or !$assetHash) { throw 'Unsupported Paperclip download-context format; refusing an implicit asset download.' }
    $asset = Join-Path $RuntimeDirectory "cache/$assetName"
    if (!(Test-Path -LiteralPath $asset) -or (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash -ne $assetHash) {
        throw "Missing verified local Paper bootstrap asset: $asset. Prepare it separately before running this script."
    }
    $serverDestination = Join-Path $RuntimeDirectory 'paper.jar'
    if ($ServerJar -ne $serverDestination) { Copy-Item -LiteralPath $ServerJar -Destination $serverDestination -Force }
    $pluginsDirectory = Join-Path $RuntimeDirectory 'plugins'
    New-Item -ItemType Directory -Path $pluginsDirectory -Force | Out-Null
    $pluginDestination = Join-Path $pluginsDirectory 'StarWorld.jar'
    if ($PluginJar -ne $pluginDestination) { Copy-Item -LiteralPath $PluginJar -Destination $pluginDestination -Force }
    # A reusable smoke runtime may include explicitly installed StarCore test dependencies.
    [IO.File]::WriteAllText((Join-Path $RuntimeDirectory 'server.properties'), @"
server-ip=127.0.0.1
server-port=$Port
online-mode=false
enable-rcon=false
enable-query=false
enable-jmx-monitoring=false
max-players=2
view-distance=2
simulation-distance=2
spawn-protection=0
level-name=smoke-world
level-type=minecraft:flat
generate-structures=false
motd=StarWorld isolated smoke test
"@ + "`n", $utf8)
    $eulaPath = Join-Path $RuntimeDirectory 'eula.txt'
    if ($AgreeEula) { [IO.File]::WriteAllText($eulaPath, "eula=true`n", $utf8) }
    elseif (!(Test-Path -LiteralPath $eulaPath)) { [IO.File]::WriteAllText($eulaPath, "eula=false`n", $utf8) }
    $agreed = @(Get-Content -LiteralPath $eulaPath | Where-Object { $_ -match '^\s*eula\s*=\s*true\s*$' }).Count -gt 0

    function Read-SharedLog([string] $Path) {
        $stream = $null
        $reader = $null
        try {
            # Paper keeps latest.log open and may rotate it during startup on Windows.
            $sharing = [IO.FileShare]([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
            $stream = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, $sharing)
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
            return $reader.ReadToEnd()
        } catch [IO.IOException] {
            # A sharing/rotation race is observational: retry the same live process next poll.
            return $null
        } finally {
            if ($reader) { $reader.Dispose() }
            if ($stream) { $stream.Dispose() }
        }
    }

    function Invoke-Paper([int] $Run, [bool] $ExpectEulaGate) {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $JavaPath
        $startInfo.WorkingDirectory = $RuntimeDirectory
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardInput = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        foreach ($argument in @('-Xms256M', '-Xmx1024M', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC', '-Dfile.encoding=UTF-8', '-jar', $serverDestination, '--nogui')) {
            $startInfo.ArgumentList.Add($argument)
        }
        $latest = Join-Path $RuntimeDirectory 'logs/latest.log'
        if (Test-Path -LiteralPath $latest) {
            Copy-Item -LiteralPath $latest -Destination (Join-Path $RuntimeDirectory "smoke-before-$Run.log") -Force
            Clear-Content -LiteralPath $latest
        }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        $stdout = $null; $stderr = $null; $started = $false
        try {
            if (!$process.Start()) { throw 'Java did not start.' }
            $started = $true
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
            $ready = $false
            while (!$process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
                if (Test-Path -LiteralPath $latest) {
                    $text = Read-SharedLog $latest
                    if ($text -match 'Done \(' -and $text -match 'StarWorld ready:') { $ready = $true; break }
                }
                Start-Sleep -Milliseconds 250
            }
            if (!$ExpectEulaGate -and !$ready) { throw "Paper/StarWorld did not become ready within the timeout (run $Run)." }
            if (!$ExpectEulaGate) {
                $process.StandardInput.WriteLine('starworld status')
                $process.StandardInput.Flush()
                Start-Sleep -Milliseconds 750
                $process.StandardInput.WriteLine('stop')
                $process.StandardInput.Flush()
            }
            if (!$process.WaitForExit(30000)) { throw "Paper did not stop cleanly (run $Run)." }
            $combined = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
            [IO.File]::WriteAllText((Join-Path $RuntimeDirectory "smoke-run-$Run.log"), $combined, $utf8)
            if ($ExpectEulaGate) {
                if ($combined -notmatch 'agree to the EULA') { throw 'Expected the EULA gate but did not observe it.' }
                Write-Output 'Paper bootstrap and EULA gate passed; eula=false preserved. No gameplay server was started.'
                return
            }
            if ($process.ExitCode -ne 0 -or $combined -match 'StarWorld startup failed|Could not flush StarWorld storage|Error occurred while enabling StarWorld') {
                throw "Paper/StarWorld failed; inspect smoke-run-$Run.log."
            }
            if ($combined -notmatch 'StarWorld ready:.*transactional schema 2' -or $combined -notmatch 'StarWorld 0\.') {
                throw "Missing plugin schema/status output in smoke-run-$Run.log."
            }
            Write-Output "Paper startup, console status and clean shutdown passed (run $Run)."
        } finally {
            if ($started -and !$process.HasExited) {
                try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
                if (!$process.WaitForExit(15000)) { $process.Kill($true); $process.WaitForExit() }
            }
            if ($stdout -and $stdout.IsCompleted -and $stderr -and $stderr.IsCompleted) {
                [IO.File]::WriteAllText((Join-Path $RuntimeDirectory "smoke-run-$Run.log"),
                    $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult(), $utf8)
            }
            $process.Dispose()
        }
    }
    if (!$agreed) { Invoke-Paper 1 $true; return }
    Invoke-Paper 1 $false

    # Probe only the stopped, sentinel-owned StarWorld database; never accesses StarCore tables.
    $database = Join-Path $pluginsDirectory 'StarWorld/starworld.db'
    if (!(Test-Path -LiteralPath $database -PathType Leaf)) { throw 'StarWorld database was not created.' }
    $probeSource = Join-Path $RuntimeDirectory 'StarWorldSmokeProbe.java'
    [IO.File]::WriteAllText($probeSource, @'
import java.sql.*;
public class StarWorldSmokeProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[0])) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
                if (!r.next() || r.getInt(1) != 2) throw new IllegalStateException("Unexpected schema");
            }
            for (String table : new String[]{"city_state", "nation_state", "stock", "region", "reserve", "building", "settlement", "transfer", "audit"}) {
                try (Statement s = c.createStatement()) { s.executeQuery("SELECT COUNT(*) FROM " + table).close(); }
            }
            if (args[1].equals("seed")) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO audit(at,actor,action) VALUES(?,?,?)")) {
                    p.setLong(1, System.currentTimeMillis()); p.setString(2, "smoke-paper"); p.setString(3, args[2]); p.executeUpdate();
                }
            } else {
                try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM audit WHERE actor='smoke-paper' AND action=?")) {
                    p.setString(1, args[2]);
                    try (ResultSet r = p.executeQuery()) { if (!r.next() || r.getInt(1) != 1) throw new IllegalStateException("Restart lost persisted audit marker"); }
                }
            }
        }
        System.out.println("StarWorld schema and persisted restart marker verified: " + args[1]);
    }
}
'@ + "`n", $utf8)
    $marker = 'restart-' + [Guid]::NewGuid().ToString('N')
    & $JavaPath --class-path $pluginDestination $probeSource $database seed $marker
    if ($LASTEXITCODE -ne 0) { throw 'Initial SQLite schema probe failed.' }
    Invoke-Paper 2 $false
    & $JavaPath --class-path $pluginDestination $probeSource $database verify $marker
    if ($LASTEXITCODE -ne 0) { throw 'Restart persistence probe failed.' }
    Write-Output "PASS: two clean Paper starts, console status, SQLite schema 2 and durable restart marker. Logs: $RuntimeDirectory"
} finally { $lock.Dispose() }
