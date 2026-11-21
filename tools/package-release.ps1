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
$releaseVersion = [version]($version -split '-')[0]
$isFinalRelease = $releaseVersion -ge [version]'1.0.0'
$requiresLifecycleEvidence = $releaseVersion -ge [version]'1.1.0'
$nativeExamples = @('config.yml', 'definitions.yml', 'evolution.yml', 'logistics.yml', 'exports.yml')
if ($releaseVersion -ge [version]'0.5.0') { $nativeExamples += 'disaster-response.yml' }
if ($isFinalRelease) { $nativeExamples += @('public-works.yml', 'civic-needs.yml') }
if ($releaseVersion -ge [version]'1.2.0') { $nativeExamples += 'climate.yml' }
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
function Get-LifecycleEntry([IO.Compression.ZipArchive] $Archive, [string] $Name) {
    $entry = $Archive.GetEntry($Name)
    if (!$entry -or $entry.Length -eq 0) { throw "Lifecycle evidence is missing or empty: $Name" }
    return $entry
}
function Read-LifecycleJson([byte[]] $Bytes) {
    return [Text.Encoding]::UTF8.GetString($Bytes) | ConvertFrom-Json -AsHashtable
}
function Get-LifecycleUtcTicks($Value, [string] $Label) {
    if ($Value -is [DateTimeOffset]) { return $Value.UtcTicks }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -ne [DateTimeKind]::Utc) { throw "Lifecycle timestamp is not UTC: $Label" }
        return ([DateTimeOffset]$Value).UtcTicks
    }
    $parsed = [DateTimeOffset]::MinValue
    if ([string]$Value -notmatch '(?:Z|\+00:00)$') { throw "Lifecycle timestamp must state UTC explicitly: $Label" }
    $valid = [DateTimeOffset]::TryParse([string]$Value, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)
    if (!$valid -or $parsed.Offset -ne [TimeSpan]::Zero) { throw "Lifecycle timestamp is invalid or has no UTC zone: $Label" }
    return $parsed.UtcTicks
}
function Assert-LifecycleEvidence([IO.Compression.ZipArchive] $Archive, $Manifest) {
    $specifications = [ordered]@{
        'life-source' = @{
            Runner = 'tools/senluo-fixture/lifecycle-acceptance.cjs'
            Suite = 'real-paper-life-carry'; Mode = 'source'; Version = '1.0.0'
            Offset = 0; Steps = 4; Starts = 1
        }
        'life-carry' = @{
            Runner = 'tools/senluo-fixture/lifecycle-acceptance.cjs'
            Suite = 'real-paper-life-carry'; Mode = 'candidate'; Version = $version
            Offset = 4; Steps = 7; Starts = 3
        }
        'lifecycle-logistics' = @{
            Runner = 'tools/paper-client/lifecycle-logistics-acceptance.cjs'
            Suite = 'real-paper-lifecycle-logistics'; Mode = $null; Version = $version
            Offset = 0; Steps = 14; Starts = 4
        }
    }
    $runs = @{}
    foreach ($suite in $specifications.Keys) {
        $spec = $specifications[$suite]
        $base = "runtime/$suite"
        $records = @($Manifest.runtime_runs | Where-Object suite -ceq $suite)
        if ($records.Count -ne 1) { throw "Evidence manifest must contain exactly one $suite run." }
        $record = $records[0]
        $evidenceBytes = Read-ZipEntryBytes (Get-LifecycleEntry $Archive "$base/evidence.json")
        $run = Read-LifecycleJson $evidenceBytes
        $runStarted = Get-LifecycleUtcTicks $run.startedAt "$suite.startedAt"
        $runFinished = Get-LifecycleUtcTicks $run.finishedAt "$suite.finishedAt"
        $recordStarted = Get-LifecycleUtcTicks $record.started_at "$suite.record.started_at"
        $recordFinished = Get-LifecycleUtcTicks $record.finished_at "$suite.record.finished_at"
        if ($run.schema -ne 1 -or $run.status -cne 'passed' -or $run.suite -cne $spec.Suite -or
            $record.version -cne $spec.Version -or $record.steps -ne $spec.Steps -or
            $runStarted -gt $runFinished -or $recordStarted -ne $runStarted -or $recordFinished -ne $runFinished -or
            @($run.steps).Count -ne $spec.Steps -or @($run.steps | Where-Object status -cne 'passed').Count -ne 0) {
            throw "Lifecycle manifest does not match the passed $suite run."
        }
        foreach ($key in @('error', 'cleanupError', 'playerCleanupError', 'configurationRestoreError')) {
            if ($run.Contains($key)) { throw "Lifecycle run retained an error: $suite ($key)" }
        }
        if ($spec.Mode) {
            if ($run.mode -cne $spec.Mode -or $run.version -cne $spec.Version -or $run.expectedStages -ne $spec.Steps) {
                throw "Lifecycle run has the wrong version, mode or stage count: $suite"
            }
            $runHash = $run.preparation.pluginJars.'StarWorld.jar'
        } else {
            if ($run.minecraft -cne '1.21.11') { throw 'Logistics lifecycle run has the wrong Minecraft version.' }
            $runHash = $run.pluginJars.'StarWorld.jar'
        }
        if ($runHash -cnotmatch '^[0-9a-f]{64}$' -or $record.plugin_sha256 -cne $runHash -or
            ($suite -ceq 'life-source' -and $runHash -ceq $pluginHash) -or
            ($suite -cne 'life-source' -and $runHash -cne $pluginHash)) {
            throw "Lifecycle run has a mismatched StarWorld JAR: $suite"
        }

        $scriptBytes = Read-ZipEntryBytes (Get-LifecycleEntry $Archive "$base/fixture-script.cjs")
        $scriptHash = Get-BytesSha256 $scriptBytes
        $currentScriptHash = (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $spec.Runner) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($record.fixture_script -cne $spec.Runner -or $record.fixture_script_sha256 -cne $scriptHash -or
            $scriptHash -cne $currentScriptHash -or ($run.Contains('runnerSha256') -and $run.runnerSha256 -cne $scriptHash)) {
            throw "Lifecycle fixture script differs from the verified source: $suite"
        }
        $stageNames = [regex]::Matches([Text.Encoding]::UTF8.GetString($scriptBytes), 'await\s+stage\(\s*([''"])(.*?)\1')
        $expectedTotal = if ($spec.Mode) { 11 } else { 14 }
        if ($stageNames.Count -ne $expectedTotal) { throw "Lifecycle fixture has an unexpected stage count: $suite" }
        for ($i = 0; $i -lt $spec.Steps; $i++) {
            if ($run.steps[$i].name -cne $stageNames[$spec.Offset + $i].Groups[2].Value) {
                throw "Lifecycle run is missing the expected stage $($i + 1): $suite"
            }
        }
        for ($number = 1; $number -le $spec.Starts; $number++) {
            $null = Get-LifecycleEntry $Archive "$base/paper-run-$number.log"
            if ($spec.Mode) {
                $null = Get-LifecycleEntry $Archive "$base/client-run-$number.log"
            } else {
                foreach ($slot in @('a', 'b')) { $null = Get-LifecycleEntry $Archive "$base/client-run-$number-$slot.log" }
                $null = Get-LifecycleEntry $Archive "$base/memory-before-run-$number.json"
            }
        }
        $runs[$suite] = @{Run = $run; Record = $record; Hash = $runHash; EvidenceHash = (Get-BytesSha256 $evidenceBytes)}
    }

    $source = $runs['life-source']
    $candidate = $runs['life-carry']
    $originalBytes = Read-ZipEntryBytes (Get-LifecycleEntry $Archive 'runtime/life-source/original-1.0-evidence.json')
    $original = Read-LifecycleJson $originalBytes
    $originalHash = Get-BytesSha256 $originalBytes
    $historicalPath = Join-Path $RepositoryRoot 'docs/evidence/history/1.0.0/civic-client.json'
    $historicalHash = (Get-FileHash -LiteralPath $historicalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $originalLink = $source.Run.preparation.original
    if ($originalHash -cne $historicalHash -or $originalHash -cne $source.Record.preparation_sha256 -or
        $originalHash -cne $originalLink.evidenceSha256 -or
        $original.schema -ne 1 -or $original.status -cne 'passed' -or $original.version -cne '1.0.0' -or
        $original.pluginJars.'StarWorld.jar' -cne $source.Hash -or
        (Get-LifecycleUtcTicks $original.finishedAt 'original1.0.finishedAt') -gt
            (Get-LifecycleUtcTicks $source.Run.startedAt 'life-source.startedAt') -or
        @($original.steps).Count -ne 30 -or @($original.steps | Where-Object status -cne 'passed').Count -ne 0) {
        throw 'Lifecycle source is not bound to the archived, passed StarWorld 1.0 civic evidence.'
    }
    $sourceLink = $candidate.Run.preparation.source
    $sourceDirectory = ([string]$source.Record.source_directory).Replace('\', '/').TrimEnd('/')
    $sourceRuntime = ([string]$source.Run.runtime).Replace('\', '/').TrimEnd('/')
    if ($candidate.Record.preparation_sha256 -cne $source.EvidenceHash -or $sourceLink.evidenceSha256 -cne $source.EvidenceHash -or
        !$sourceDirectory -or ([string]$sourceLink.evidenceFile).Replace('\', '/') -cne "$sourceDirectory/evidence.json" -or
        !$sourceRuntime -or ([string]$sourceLink.runtime).Replace('\', '/').TrimEnd('/') -cne $sourceRuntime -or
        !$source.Run.oldActionId -or $sourceLink.oldAction.id -cne $source.Run.oldActionId -or
        (Get-LifecycleUtcTicks $source.Run.finishedAt 'life-source.finishedAt') -gt
            (Get-LifecycleUtcTicks $candidate.Run.startedAt 'life-carry.startedAt')) {
        throw 'Lifecycle candidate is not bound to the exact stopped source run.'
    }
    $null = Get-LifecycleEntry $Archive 'runtime/life-source/stopped-source-final.json'
    foreach ($label in @('partial', 'complete', 'final')) {
        $null = Get-LifecycleEntry $Archive "runtime/life-carry/stopped-candidate-$label.json"
    }

    $logistics = $runs['lifecycle-logistics']
    $prepBytes = Read-ZipEntryBytes (Get-LifecycleEntry $Archive 'runtime/lifecycle-logistics/PREPARATION.json')
    $prepHash = Get-BytesSha256 $prepBytes
    $preparation = Read-LifecycleJson $prepBytes
    if ($prepHash -cne $logistics.Record.preparation_sha256 -or $prepHash -cne $logistics.Run.preparation.sha256 -or
        $preparation.schema -ne 1 -or $preparation.artifactsBound -ne $true -or
        $preparation.productVersion -cne $version -or $preparation.targetPluginSha256 -cne $pluginHash -or
        $preparation.fixtureSha256 -cne $logistics.Run.pluginJars.'StarWorldFixture.jar' -or
        $preparation.worldsCopied -ne 0 -or $preparation.databasesCopied -ne 0 -or $preparation.playersCopied -ne 0 -or
        $logistics.Run.originalConfigurationRestored -ne $true) {
        throw 'Logistics lifecycle run is not bound to its fresh runtime preparation and restored configuration.'
    }
    foreach ($label in @('default', 'travel-reduced', 'waypoints-reduced', 'restored')) {
        $null = Get-LifecycleEntry $Archive "runtime/lifecycle-logistics/stopped-$label.json"
    }
    foreach ($label in @('original', 'travel-reduced', 'waypoints-reduced', 'restored')) {
        $null = Get-LifecycleEntry $Archive "runtime/lifecycle-logistics/logistics-$label.yml"
    }
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
    $requiredSuites = @('expansion', 'baseline', 'progression', 'upgrade', 'border')
    if ($releaseVersion -ge [version]'0.4.0') { $requiredSuites += 'operations' }
    if ($releaseVersion -ge [version]'0.5.0') { $requiredSuites += 'disaster' }
    if ($isFinalRelease) { $requiredSuites += @('works', 'civic') } else { $requiredSuites += 'senluo' }
    if ($requiresLifecycleEvidence) { $requiredSuites += @('life-source', 'life-carry', 'lifecycle-logistics') }
    if ($isFinalRelease) {
        if ($manifest.source.sources_jar_sha256 -ne (Get-FileHash -LiteralPath $sourcesJar -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'Evidence manifest does not bind the current sources JAR.'
        }
        $configFiles = @('src/main/resources/plugin.yml') + @($nativeExamples | ForEach-Object { "src/main/resources/$_" }) + @('examples/life.yml','examples/market.yml','examples/senluo-recipes.yml')
        foreach ($file in $configFiles) {
            $recorded = $manifest.source.configuration_sha256.PSObject.Properties[$file]
            if (!$recorded -or $recorded.Value -ne (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $file) -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw "Configuration differs from the final evidence: $file"
            }
        }
    }
    foreach ($suite in $requiredSuites) {
        if ($requiresLifecycleEvidence -and $suite -in @('life-source', 'life-carry', 'lifecycle-logistics')) {
            continue
        }
        $entry = $evidenceArchive.GetEntry("runtime/$suite/evidence.json")
        if (!$entry) { throw "Evidence archive lacks the required $suite run." }
        $run = [Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $entry)) | ConvertFrom-Json
        if ($run.status -ne 'passed' -or $run.pluginJars.'StarWorld.jar' -ne $pluginHash -or
            @($run.steps).Count -eq 0 -or @($run.steps | Where-Object status -ne 'passed').Count -ne 0) {
            throw "Evidence archive contains an incomplete or mismatched $suite run."
        }
        $starts = if ($suite -in @('operations', 'works')) { 3 } else { 2 }
        for ($runNumber = 1; $runNumber -le $starts; $runNumber++) {
            if (!$evidenceArchive.GetEntry("runtime/$suite/paper-run-$runNumber.log")) { throw "$suite acceptance lacks a Paper start transcript." }
        }
        if ($isFinalRelease) {
            $clientNames = switch ($suite) {
                'civic' { 'client-run-1.log'; 'client-run-listener-1.log'; 'client-run-2.log' }
                'works' { foreach ($number in 1..3) { foreach ($slot in 0..2) { "client-run-$number-$slot.log" } } }
                { $_ -in @('expansion','progression','operations','border','disaster') } {
                    foreach ($number in 1..$starts) { foreach ($player in @('a','b')) { "client-run-$number-$player.log" } }
                }
                default { 'client-run-1.log'; 'client-run-2.log' }
            }
            foreach ($name in $clientNames) {
                if (!$evidenceArchive.GetEntry("runtime/$suite/$name")) { throw "$suite acceptance lacks client transcript $name." }
            }
            $records = @($manifest.runtime_runs | Where-Object suite -eq $suite)
            if ($records.Count -ne 1 -or $records[0].plugin_sha256 -ne $pluginHash -or $records[0].steps -ne @($run.steps).Count) {
                throw "Evidence manifest differs from the $suite run."
            }
            $script = [string]$records[0].fixture_script
            $allowedScripts = @{
                expansion='tools/paper-client/expansion-acceptance.cjs'; baseline='tools/paper-client/acceptance.cjs';
                progression='tools/paper-client/progression-acceptance.cjs'; upgrade='tools/paper-client/upgrade-10-acceptance.cjs';
                border='tools/paper-client/border-acceptance.cjs'; operations='tools/paper-client/operations-acceptance.cjs';
                disaster='tools/paper-client/disaster-acceptance.cjs'; works='tools/paper-client/works-acceptance.cjs';
                civic='tools/senluo-fixture/civic-acceptance.cjs'
            }
            if ($script -cne $allowedScripts[$suite] -or $records[0].fixture_script_sha256 -ne (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot $script) -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw "Fixture script differs from the final evidence: $suite"
            }
            if ($suite -in @('disaster','civic','works','upgrade','operations')) {
                $prepName = if ($suite -eq 'upgrade') { 'UPGRADE-PROVENANCE.json' } else { 'PREPARATION.json' }
                $prep = $evidenceArchive.GetEntry("runtime/$suite/$prepName")
                if (!$prep -or (Get-BytesSha256 (Read-ZipEntryBytes $prep)) -ne $records[0].preparation_sha256) { throw "Preparation provenance differs from the final evidence: $suite" }
            }
        }
    }
    if ($requiresLifecycleEvidence) { Assert-LifecycleEvidence $evidenceArchive $manifest }
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
    $requiredEntries = @('plugin.yml') + $nativeExamples + @(
        'dev/starworld/StarWorldPlugin.class', 'dev/starworld/evolution/EvolutionService.class',
        'dev/starworld/logistics/LogisticsService.class', 'dev/starworld/life/LifeService.class',
        'dev/starworld/integration/ResourceOutbox.class', 'dev/starworld/integration/StarCoreCombatContributor.class',
        'dev/starworld/evolution/RegionAtlas.class', 'dev/starworld/evolution/CultureDynamics.class',
        'dev/starworld/integration/BorderConflictCapture.class',
        'dev/starworld/market/MarketService.class', 'dev/starworld/persistence/LedgerMaintenance.class', 'org/sqlite/JDBC.class')
    if ($releaseVersion -ge [version]'0.5.0') { $requiredEntries += 'dev/starworld/disaster/DisasterResponseService.class' }
    if ($isFinalRelease) {
        $requiredEntries += @('dev/starworld/works/PublicWorksService.class',
            'dev/starworld/civic/CivicNeedsService.class','dev/starworld/ui/GovernanceMenu.class')
    }
    if ($releaseVersion -ge [version]'1.2.0') { $requiredEntries += @('dev/starworld/climate/ClimateRules.class','dev/starworld/climate/ClimateDefinitions.class') }
    foreach ($required in $requiredEntries) {
        if ($jarNames -notcontains $required) { throw "Plugin artifact lacks $required" }
    }
    if ($isFinalRelease) {
        foreach ($name in (@('plugin.yml') + $nativeExamples)) {
            $expected = (Get-FileHash -LiteralPath (Join-Path $RepositoryRoot "src/main/resources/$name") -Algorithm SHA256).Hash.ToLowerInvariant()
            if ((Get-BytesSha256 (Read-ZipEntryBytes ($jar.GetEntry($name)))) -ne $expected) { throw "Plugin JAR contains a different configuration: $name" }
        }
    }
    if ($jarNames | Where-Object { $_ -match '^(dev/starcore/|dev/starworld/fixture/|net/momirealms/)' -or $_ -match 'Test\.class$' }) {
        throw 'Plugin artifact contains test fixtures or bundled StarCore classes.'
    }
} finally { $jar.Dispose() }

$packageId = 'package-' + [Guid]::NewGuid().ToString('N')
$staging = Join-Path $target $packageId
$temporaryArchive = Join-Path $target "$packageId.zip"
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
    if ($releaseVersion -ge [version]'0.5.0') { $toolFiles += @('tools/paper-client/disaster-acceptance.cjs','tools/paper-client/upgrade-05-acceptance.cjs') }
    if ($isFinalRelease) {
        $toolFiles += @('tools/paper-client/works-acceptance.cjs','tools/paper-client/prepare-upgrade-10.py',
            'tools/paper-client/upgrade-10-acceptance.cjs','tools/senluo-fixture/civic-acceptance.cjs')
    }
    if ($requiresLifecycleEvidence) {
        $toolFiles += @('tools/lifecycle_evidence.py', 'tools/test_lifecycle_evidence.py',
            'tools/paper-client/lifecycle-logistics-acceptance.cjs', 'tools/senluo-fixture/lifecycle-acceptance.cjs')
    }
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
    foreach ($file in $nativeExamples) {
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot "src/main/resources/$file") -Destination (Join-Path $staging "config-examples/$file")
    }
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'examples/life.yml') -Destination (Join-Path $staging 'config-examples/life.yml')
    Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'examples/market.yml') -Destination (Join-Path $staging 'config-examples/market.yml')
    if ($isFinalRelease) {
        Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'examples/senluo-recipes.yml') -Destination (Join-Path $staging 'config-examples/senluo-recipes.yml')
    }
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
    Compress-Archive -LiteralPath @(Get-ChildItem -LiteralPath $staging -Force | ForEach-Object FullName) -DestinationPath $temporaryArchive -CompressionLevel Optimal
    $archive = [IO.Compression.ZipFile]::OpenRead($temporaryArchive)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File -Force) {
            $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\', '/')
            $archiveEntry = $archive.GetEntry($relative)
            if (!$archiveEntry -or (Get-BytesSha256 (Read-ZipEntryBytes $archiveEntry)) -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw "Archive lost or changed a payload file: $relative"
            }
        }
        $unwanted = @($names | Where-Object {
            $_ -match '(^|/)(node_modules|\.git|target)/' -or $_ -match '^(source/)?runtime/' -or
            $_ -match '\.(db|db-wal|db-shm|log|class)$' -or
            ($_ -match '\.jar$' -and $_ -notin @('plugins/StarWorld.jar', "sources/starworld-$version-sources.jar"))
        })
        if ($unwanted.Count -ne 0) { throw "Archive contains unexpected runtime/generated content: $($unwanted -join ', ')" }
        $entryCount = $names.Count
    } finally { $archive.Dispose() }
    [IO.File]::Move($temporaryArchive, $destination, $true)
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
    if (Test-Path -LiteralPath $temporaryArchive -PathType Leaf) { Remove-Item -LiteralPath $temporaryArchive -Force }
}
