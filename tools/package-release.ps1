#requires -Version 7.0
<#
.SYNOPSIS
Packages the verified StarWorld plugin, source tree, examples and evidence.
.DESCRIPTION
Uses an explicit source allowlist. Server runtimes, node_modules, database files,
generated target folders and third-party plugin JARs are never added. The only
temporary directory removed is the unique package GUID directory created here.
.EXAMPLE
./tools/package-release.ps1
#>
[CmdletBinding()]
param(
    [string] $RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $ExpectedPluginSha256
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
[xml] $pom = [IO.File]::ReadAllText((Join-Path $RepositoryRoot 'pom.xml'))
if ($pom.project.groupId -ne 'dev.starworld' -or $pom.project.artifactId -ne 'starworld') {
    throw 'RepositoryRoot is not the StarWorld Maven project.'
}
$version = [string] $pom.project.version
if ($version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw "Unsupported release version: $version" }
$target = Join-Path $RepositoryRoot 'target'
$pluginJar = Join-Path $target "starworld-$version.jar"
$sourcesJar = Join-Path $target "starworld-$version-sources.jar"
$evidenceZip = Join-Path $target "StarWorld-$version-evidence.zip"
foreach ($file in @($pluginJar, $sourcesJar, $evidenceZip)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required build artifact is missing: $file" }
}
$pluginHash = (Get-FileHash -LiteralPath $pluginJar -Algorithm SHA256).Hash.ToLowerInvariant()
$testEvidence = Join-Path $RepositoryRoot 'docs/evidence/unit-tests.json'
if (!(Test-Path -LiteralPath $testEvidence -PathType Leaf)) { throw 'Required unit-test evidence is missing.' }
$unit = [IO.File]::ReadAllText($testEvidence) | ConvertFrom-Json
if ($unit.version -ne $version -or $unit.artifact_sha256 -ne $pluginHash) {
    throw 'Plugin JAR/version does not match the recorded verified build.'
}
if ($unit.totals.tests -lt 1 -or $unit.totals.failures -ne 0 -or $unit.totals.errors -ne 0 -or $unit.totals.skipped -ne 0) {
    throw 'Recorded verification contains no tests, failures, errors or skipped tests.'
}
if ($ExpectedPluginSha256 -and $pluginHash -ne $ExpectedPluginSha256.ToLowerInvariant()) {
    throw 'Plugin JAR does not match ExpectedPluginSha256.'
}

function Read-ZipEntryBytes([IO.Compression.ZipArchiveEntry] $Entry) {
    $stream = $Entry.Open()
    $buffer = [IO.MemoryStream]::new()
    try { $stream.CopyTo($buffer); return ,$buffer.ToArray() }
    finally { $buffer.Dispose(); $stream.Dispose() }
}
function Get-BytesSha256([byte[]] $Bytes) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}
# A stale evidence ZIP must not be attached to a different installable artifact.
$evidenceArchive = [IO.Compression.ZipFile]::OpenRead($evidenceZip)
try {
    $manifestEntry = $evidenceArchive.GetEntry('manifest.json')
    if (!$manifestEntry) { throw 'Evidence archive has no manifest; run package-evidence.py first.' }
    $manifest = [Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $manifestEntry)) | ConvertFrom-Json
    if ($manifest.version -ne $version -or $manifest.plugin_sha256 -ne $pluginHash -or
        $manifest.unit_tests.tests -ne $unit.totals.tests -or $manifest.unit_tests.failures -ne 0 -or
        $manifest.unit_tests.errors -ne 0 -or $manifest.unit_tests.skipped -ne 0) {
        throw 'Evidence manifest does not describe this verified artifact.'
    }
    $seenEvidence = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $manifest.entries) {
        $name = [string] $entry.path
        if (!$name -or $name.Contains('\') -or $name.StartsWith('/') -or
            ($name -split '/') -contains '..' -or !$seenEvidence.Add($name)) {
            throw "Invalid/duplicate evidence manifest path: $name"
        }
        $payloadEntry = $evidenceArchive.GetEntry($name)
        if (!$payloadEntry -or $payloadEntry.Length -ne $entry.bytes -or
            (Get-BytesSha256 (Read-ZipEntryBytes $payloadEntry)) -ne $entry.sha256) {
            throw "Evidence payload does not match its manifest: $name"
        }
    }
    if ($evidenceArchive.Entries.Count -ne $seenEvidence.Count + 1) { throw 'Evidence archive has unlisted payloads.' }
    foreach ($suite in @('expansion', 'senluo', 'baseline', 'progression', 'upgrade', 'border')) {
        $entry = $evidenceArchive.GetEntry("runtime/$suite/evidence.json")
        if (!$entry) { throw "Evidence archive lacks the required $suite run." }
        $run = [Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $entry)) | ConvertFrom-Json
        if ($run.status -ne 'passed' -or $run.pluginJars.'StarWorld.jar' -ne $pluginHash -or
            @($run.steps).Count -eq 0 -or @($run.steps | Where-Object status -ne 'passed').Count -ne 0) {
            throw "Evidence archive contains an incomplete or mismatched $suite run."
        }
    }
    if ([version]($version -split '-')[0] -ge [version]'0.4.0') {
        $operationsEntry = $evidenceArchive.GetEntry('runtime/operations/evidence.json')
        if (!$operationsEntry) { throw 'Evidence archive lacks the required 0.4 operations acceptance.' }
        $operations = [Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $operationsEntry)) | ConvertFrom-Json
        if ($operations.status -ne 'passed' -or $operations.pluginJars.'StarWorld.jar' -ne $pluginHash -or
            @($operations.steps).Count -eq 0 -or @($operations.steps | Where-Object status -ne 'passed').Count -ne 0) {
            throw 'Operations acceptance is incomplete or describes another plugin.'
        }
        foreach ($runNumber in @(1, 2, 3)) {
            if (!$evidenceArchive.GetEntry("runtime/operations/paper-run-$runNumber.log")) { throw 'Operations acceptance lacks all three starts.' }
        }
    }
    $smokeEntry = $evidenceArchive.GetEntry('runtime/smoke/smoke-evidence.json')
    if (!$smokeEntry) { throw 'Evidence archive lacks the required standalone run.' }
    $smoke = [Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $smokeEntry)) | ConvertFrom-Json
    if ($smoke.status -ne 'passed' -or $smoke.artifact_sha256 -ne $pluginHash) {
        throw 'Standalone evidence is incomplete or describes another plugin.'
    }
    foreach ($name in @('smoke-run-1.log', 'smoke-run-2.log')) {
        $logEntry = $evidenceArchive.GetEntry("runtime/smoke/$name")
        if (!$logEntry -or (Get-BytesSha256 (Read-ZipEntryBytes $logEntry)) -ne $smoke.logs.$name) {
            throw "Standalone log is missing or differs from its evidence: $name"
        }
    }
} finally { $evidenceArchive.Dispose() }

# Refuse to combine current source with a JAR built before those source edits.
$sourceArchive = [IO.Compression.ZipFile]::OpenRead($sourcesJar)
try {
    foreach ($folder in @('java', 'resources')) {
        $sourceFolder = Join-Path $RepositoryRoot "src/main/$folder"
        foreach ($file in Get-ChildItem -LiteralPath $sourceFolder -File -Recurse -Force) {
            $name = [IO.Path]::GetRelativePath($sourceFolder, $file.FullName).Replace('\', '/')
            $entry = $sourceArchive.GetEntry($name)
            if (!$entry -or (Get-BytesSha256 (Read-ZipEntryBytes $entry)) -ne
                (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw "Sources JAR differs from the current source: $name"
            }
        }
    }
} finally { $sourceArchive.Dispose() }
# Inspect the actual installable artifact, not merely a successful build log.
$jar = [IO.Compression.ZipFile]::OpenRead($pluginJar)
try {
    $jarNames = @($jar.Entries | ForEach-Object FullName)
    foreach ($required in @('plugin.yml', 'config.yml', 'definitions.yml', 'evolution.yml', 'logistics.yml', 'exports.yml',
        'dev/starworld/StarWorldPlugin.class', 'dev/starworld/evolution/EvolutionService.class',
        'dev/starworld/logistics/LogisticsService.class', 'dev/starworld/life/LifeService.class',
        'dev/starworld/integration/ResourceOutbox.class', 'dev/starworld/integration/StarCoreCombatContributor.class',
        'dev/starworld/evolution/RegionAtlas.class', 'dev/starworld/evolution/CultureDynamics.class',
            'dev/starworld/integration/BorderConflictCapture.class',
        'dev/starworld/market/MarketService.class', 'dev/starworld/persistence/LedgerMaintenance.class', 'org/sqlite/JDBC.class')) {
        if ($jarNames -notcontains $required) { throw "Plugin artifact lacks $required" }
    }
    if ($jarNames | Where-Object { $_ -match '^(dev/starcore/|dev/starworld/fixture/|net/momirealms/)' -or $_ -match 'Test\.class$' }) {
        throw 'Plugin artifact contains test fixtures or bundled StarCore classes.'
    }
} finally { $jar.Dispose() }

$staging = Join-Path $target ('package-' + [Guid]::NewGuid().ToString('N'))
$destination = Join-Path $target "StarWorld-$version.zip"
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    function Copy-AllowedFile([string] $RelativePath, [string] $DestinationRoot) {
        $source = Join-Path $RepositoryRoot $RelativePath
        if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Allowlisted source is missing: $RelativePath" }
        if ((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Refusing a linked source file: $RelativePath"
        }
        $destinationFile = Join-Path $DestinationRoot $RelativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destinationFile) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destinationFile
    }
    $sourceRoot = Join-Path $staging 'source'
    New-Item -ItemType Directory -Path $sourceRoot | Out-Null
    $rootFiles = @('pom.xml', 'mvnw', 'mvnw.cmd', '.gitignore', '.gitattributes', 'LICENSE', 'README.md', 'CHANGELOG.md')
    foreach ($file in $rootFiles) { Copy-AllowedFile $file $sourceRoot }
    # Only these directories contain project source. Nested generated or linked
    # directories are rejected/filtered before any file is copied.
    foreach ($folder in @('.mvn', '.github', 'src', 'docs', 'examples')) {
        $sourceFolder = Join-Path $RepositoryRoot $folder
        if (!(Test-Path -LiteralPath $sourceFolder -PathType Container)) { throw "Missing source folder: $folder" }
        foreach ($entry in Get-ChildItem -LiteralPath $sourceFolder -Recurse -Force) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing linked source: $($entry.FullName)" }
            if ($entry.PSIsContainer) { continue }
            $relative = [IO.Path]::GetRelativePath($RepositoryRoot, $entry.FullName)
            if ($relative -match '(^|[\\/])(target|node_modules|\.git)([\\/]|$)' -or
                $relative -match '\.(db|db-wal|db-shm|log|class|jar|zip)$') {
                throw "Unexpected generated file in allowlisted source directory: $relative"
            }
            Copy-AllowedFile $relative $sourceRoot
        }
    }
    $toolFiles = @(
        'tools/package-release.ps1', 'tools/package-evidence.py', 'tools/smoke-paper.ps1',
        'tools/paper-fixture/pom.xml', 'tools/paper-fixture/plugin.yml',
        'tools/paper-fixture/README.md', 'tools/paper-fixture/starcore-build-provenance.json',
        'tools/paper-client/package.json', 'tools/paper-client/acceptance.cjs',
        'tools/paper-client/expansion-acceptance.cjs', 'tools/paper-client/progression-acceptance.cjs', 'tools/paper-client/upgrade-acceptance.cjs', 'tools/paper-client/border-acceptance.cjs', 'tools/paper-client/README.md',
        'tools/paper-client/operations-acceptance.cjs', 'tools/paper-client/upgrade-04-acceptance.cjs',
        'tools/senluo-fixture/pom.xml', 'tools/senluo-fixture/README.md',
        'tools/senluo-fixture/acceptance.cjs'
    )
    foreach ($file in $toolFiles) { Copy-AllowedFile $file $sourceRoot }
    $fixtureSource = Join-Path $RepositoryRoot 'tools/paper-fixture/src'
    foreach ($file in Get-ChildItem -LiteralPath $fixtureSource -Recurse -File -Force) {
        if ($file.Extension -ne '.java' -or $file.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Unexpected fixture source file: $($file.FullName)"
        }
        Copy-AllowedFile ([IO.Path]::GetRelativePath($RepositoryRoot, $file.FullName)) $sourceRoot
    }
    $senluoSource = Join-Path $RepositoryRoot 'tools/senluo-fixture/src'
    foreach ($file in Get-ChildItem -LiteralPath $senluoSource -Recurse -File -Force) {
        if ($file.Extension -notin @('.java', '.yml') -or $file.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Unexpected optional-integration fixture source file: $($file.FullName)"
        }
        Copy-AllowedFile ([IO.Path]::GetRelativePath($RepositoryRoot, $file.FullName)) $sourceRoot
    }
    New-Item -ItemType Directory -Path (Join-Path $staging 'plugins'),
        (Join-Path $staging 'sources'), (Join-Path $staging 'config-examples') | Out-Null
    Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $staging 'plugins/StarWorld.jar')
    Copy-Item -LiteralPath $sourcesJar -Destination (Join-Path $staging "sources/starworld-$version-sources.jar")
    foreach ($file in @('config.yml', 'definitions.yml', 'evolution.yml', 'logistics.yml', 'exports.yml')) {
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot "src/main/resources/$file") -Destination (Join-Path $staging "config-examples/$file")
    }
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'examples/life.yml') -Destination (Join-Path $staging 'config-examples/life.yml')
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'examples/market.yml') -Destination (Join-Path $staging 'config-examples/market.yml')
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'README.md') -Destination (Join-Path $staging 'README.md')
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'LICENSE') -Destination (Join-Path $staging 'LICENSE')
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'docs') -Destination (Join-Path $staging 'docs') -Recurse
    Copy-Item -LiteralPath $evidenceZip -Destination (Join-Path $staging "StarWorld-$version-evidence.zip")
    $payload = @(Get-ChildItem -LiteralPath $staging -Recurse -File -Force | Sort-Object FullName)
    $checksums = foreach ($file in $payload) {
        $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\', '/')
        '{0}  {1}' -f (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
    }
    [IO.File]::WriteAllText((Join-Path $staging 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n", $utf8)
    Compress-Archive -LiteralPath @(Get-ChildItem -LiteralPath $staging -Force | ForEach-Object FullName) -DestinationPath $destination -CompressionLevel Optimal -Force
    $archive = [IO.Compression.ZipFile]::OpenRead($destination)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File -Force) {
            $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\', '/')
            if ($names -notcontains $relative) { throw "Archive lost a payload file: $relative" }
        }
        $unwanted = @($names | Where-Object {
            $_ -match '(^|/)(node_modules|\.git|target)/' -or $_ -match '^(source/)?runtime/' -or
            $_ -match '\.(db|db-wal|db-shm|log|class)$' -or
            ($_ -match '\.jar$' -and $_ -notin @('plugins/StarWorld.jar', "sources/starworld-$version-sources.jar"))
        })
        if ($unwanted.Count -ne 0) { throw "Archive contains unexpected runtime/generated content: $($unwanted -join ', ')" }
        $entryCount = $names.Count
    } finally { $archive.Dispose() }
    [pscustomobject]@{
        Path = $destination
        Bytes = (Get-Item -LiteralPath $destination).Length
        Sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        PluginSha256 = $pluginHash
        Entries = $entryCount
        EvidenceIncluded = (Test-Path -LiteralPath $evidenceZip -PathType Leaf)
    } | Format-List
} finally {
    # Never delete a computed directory without checking its resolved location.
    $resolvedTarget = [IO.Path]::GetFullPath($target).TrimEnd('\', '/')
    $resolvedStaging = [IO.Path]::GetFullPath($staging)
    if ([IO.Path]::GetDirectoryName($resolvedStaging) -ne $resolvedTarget -or
        [IO.Path]::GetFileName($resolvedStaging) -notmatch '^package-[a-f0-9]{32}$') {
        throw "Refusing to remove unexpected staging directory: $resolvedStaging"
    }
    if (Test-Path -LiteralPath $resolvedStaging) { Remove-Item -LiteralPath $resolvedStaging -Recurse -Force }
}
