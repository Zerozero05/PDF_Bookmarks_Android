param([string]$JavaHome = '', [string]$Python = '', [string]$InputPdf = '', [string]$InputJson = '')
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$workspaceDir = Split-Path (Split-Path $projectDir -Parent) -Parent
if (!$JavaHome) {
    $JavaHome = (Get-ChildItem -LiteralPath (Join-Path $workspaceDir 'tooling/jdk') -Directory | Select-Object -First 1).FullName
}
if (!$Python) {
    $Python = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
}
if (!(Test-Path -LiteralPath $Python)) { throw 'Provide -Python with Python 3 and pypdf installed.' }
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$dependencyDir = Join-Path $PSScriptRoot 'deps'
New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
$artifacts = @(
    @('com/tom-roush/pdfbox-android/2.0.27.0', 'pdfbox-android-2.0.27.0.aar', '30277F879CFD571DB2A137582C95516A0D4EA6778E945519BC58CA93D57D88C7'),
    @('org/bouncycastle/bcprov-jdk15to18/1.72', 'bcprov-jdk15to18-1.72.jar', 'EA66EA8A450810B2193E8BF9A7AD3E46307C9896224C0F407D1B7D96BA1221CC'),
    @('org/bouncycastle/bcpkix-jdk15to18/1.72', 'bcpkix-jdk15to18-1.72.jar', 'D9B97477B72499BCEE02F5A906510810257FF36A94BF69FBCA0B1E65E7FFDB6E'),
    @('org/bouncycastle/bcutil-jdk15to18/1.72', 'bcutil-jdk15to18-1.72.jar', 'D92184BDEB3105A11AD9E36ACBD66B5F8EED091B08B9C8F3E2549E42B7F131F1'),
    @('com/vaadin/external/google/android-json/0.0.20131108.vaadin1', 'android-json-0.0.20131108.vaadin1.jar', 'DFB7BAE2F404CFE0B72B4D23944698CB716B7665171812A0A4D0F5926C0FAC79'),
    @('com/google/code/gson/gson/2.11.0', 'gson-2.11.0.jar', '57928D6E5A6EDEB2ABD3770A8F95BA44DCE45F3B23B7A9DC2B309C581552A78B')
)
foreach ($artifact in $artifacts) {
    $file = Join-Path $dependencyDir $artifact[1]
    if (!(Test-Path -LiteralPath $file)) {
        Invoke-WebRequest -Uri ('https://repo.maven.apache.org/maven2/' + $artifact[0] + '/' + $artifact[1]) -OutFile $file
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact[2]) { throw 'Test dependency checksum mismatch.' }
}
$aarDir = Join-Path $dependencyDir 'pdfbox-android'
if (!(Test-Path -LiteralPath (Join-Path $aarDir 'classes.jar'))) {
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $dependencyDir $artifacts[0][1]), $aarDir)
}
$buildDir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
$binaryJars = @($artifacts | Where-Object { $_[1].EndsWith('.jar') } | ForEach-Object { Join-Path $dependencyDir $_[1] })
$classPath = (Join-Path $aarDir 'classes.jar') + ';' + ($binaryJars -join ';')
$sources = @(
    (Join-Path $projectDir 'app/src/main/java/cn/local/pdfbookmarks/TocParser.java'),
    (Join-Path $projectDir 'app/src/main/java/cn/local/pdfbookmarks/PdfBookmarks.java'),
    (Join-Path $PSScriptRoot 'EngineTests.java')
) + @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Filter '*.java' -Recurse | ForEach-Object FullName)
& $javac -encoding UTF-8 -cp $classPath -d $buildDir @sources
if ($LASTEXITCODE -ne 0) { throw 'PDF engine host compilation failed.' }
$runDir = Join-Path $projectDir ('work/engine-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffff'))
$fixtureDir = Join-Path $runDir 'fixtures'
$outputDir = Join-Path $runDir 'out'
$scratchDir = Join-Path $runDir 'tmp'
New-Item -ItemType Directory -Path $fixtureDir,$outputDir,$scratchDir -Force | Out-Null
& $Python (Join-Path $PSScriptRoot 'generate_fixtures.py') $fixtureDir
if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed.' }
$arguments = @((Join-Path $aarDir 'assets'), $fixtureDir, $outputDir)
if ($InputPdf -or $InputJson) {
    if (!$InputPdf -or !$InputJson) { throw 'Provide both -InputPdf and -InputJson for the actual book test.' }
    $arguments += @((Get-Item -LiteralPath $InputPdf).FullName,(Get-Item -LiteralPath $InputJson).FullName)
}
$verificationDir = Join-Path $projectDir 'verification'
New-Item -ItemType Directory -Path $verificationDir -Force | Out-Null
& $java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Djava.io.tmpdir=$scratchDir" -cp ($buildDir + ';' + $classPath) EngineTests @arguments |
    Tee-Object -FilePath (Join-Path $verificationDir 'pdf-engine-host.log')
if ($LASTEXITCODE -ne 0) { throw 'PDF engine host tests failed.' }
& $Python (Join-Path $PSScriptRoot 'verify_outputs.py') (Join-Path $outputDir 'engine-manifest.json') (Join-Path $verificationDir 'pdf-engine-independent.json') |
    Tee-Object -FilePath (Join-Path $verificationDir 'pdf-engine-pypdf.log')
if ($LASTEXITCODE -ne 0) { throw 'Independent PDF verification failed.' }
Write-Output "Engine test outputs: $outputDir"
