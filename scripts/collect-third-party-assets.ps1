param([string]$ArtifactDirectory,[string]$JavaExecutable)
$ErrorActionPreference='Stop'
$projectDirectory=Split-Path $PSScriptRoot -Parent
if(!$ArtifactDirectory){$ArtifactDirectory=Join-Path $projectDirectory 'testengine/deps'}
if(!$JavaExecutable){
    $toolingDirectory=[IO.Path]::GetFullPath((Join-Path $projectDirectory '../../tooling/jdk'))
    $jdkDirectory=Get-ChildItem -LiteralPath $toolingDirectory -Directory | Select-Object -First 1
    if($jdkDirectory){$JavaExecutable=Join-Path $jdkDirectory.FullName 'bin/java.exe'}else{$JavaExecutable='java'}
}
$licenseDirectory=Join-Path $projectDirectory 'licenses'
$assetDirectory=Join-Path $projectDirectory 'app/src/main/assets/licenses'
New-Item -ItemType Directory -Path $ArtifactDirectory,$licenseDirectory,$assetDirectory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Fetch-Artifact([string]$Url,[string]$Name){
    $target=Join-Path $ArtifactDirectory $Name
    if(!(Test-Path -LiteralPath $target)){Invoke-WebRequest -Uri $Url -OutFile $target}
    return $target
}
function Extract-ZipFile([string]$Archive,[string]$Entry,[string]$Destination){
    $zip=[IO.Compression.ZipFile]::OpenRead($Archive)
    try{
        $item=$zip.GetEntry($Entry)
        if(!$item){throw "归档缺少许可项：$Entry"}
        [IO.Compression.ZipFileExtensions]::ExtractToFile($item,$Destination,$true)
    }finally{$zip.Dispose()}
}
function Read-ArchiveText([string]$Archive,[string]$Entry){
    $zip=[IO.Compression.ZipFile]::OpenRead($Archive)
    try{
        $item=$zip.GetEntry($Entry)
        if(!$item){throw "归档缺少来源项：$Entry"}
        $reader=[IO.StreamReader]::new($item.Open())
        try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
    }finally{$zip.Dispose()}
}

$apacheUrl='https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox/2.0.27/pdfbox-2.0.27.jar'
$androidSourceUrl='https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0-sources.jar'
$gsonSourceUrl='https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0-sources.jar'
$unicodeUrl='https://www.unicode.org/license.txt'
$apache=Fetch-Artifact $apacheUrl 'apache-pdfbox-2.0.27.jar'
$androidSource=Fetch-Artifact $androidSourceUrl 'pdfbox-android-2.0.27.0-sources.jar'
$gsonSource=Fetch-Artifact $gsonSourceUrl 'gson-2.11.0-sources.jar'
$unicode=Fetch-Artifact $unicodeUrl 'Unicode-LICENSE.txt'
Extract-ZipFile $apache 'META-INF/LICENSE' (Join-Path $licenseDirectory 'PDFBox-LICENSE.txt')
Extract-ZipFile $apache 'META-INF/NOTICE' (Join-Path $licenseDirectory 'PDFBox-NOTICE.txt')
$pdfboxLicense=Get-Content -LiteralPath (Join-Path $licenseDirectory 'PDFBox-LICENSE.txt') -Raw
$apacheOnly=$pdfboxLicense.Substring(0,$pdfboxLicense.IndexOf('EXTERNAL COMPONENTS'))
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'Gson-LICENSE.txt'),$apacheOnly,[Text.UTF8Encoding]::new($false))
$gsonText=Read-ArchiveText $gsonSource 'com/google/gson/Gson.java'
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'Gson-NOTICE.txt'),$gsonText.Substring(0,$gsonText.IndexOf('*/')+2),[Text.UTF8Encoding]::new($false))
$androidText=Read-ArchiveText $androidSource 'com/tom_roush/pdfbox/android/PDFBoxResourceLoader.java'
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'PDFBox-Android-SOURCE-NOTICE.txt'),$androidText.Substring(0,$androidText.IndexOf('*/')+2),[Text.UTF8Encoding]::new($false))

$bcUrl='https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.72/bcprov-jdk15to18-1.72.jar'
$bc=Fetch-Artifact $bcUrl 'bcprov-jdk15to18-1.72.jar'
$bcLicense=& $JavaExecutable '-cp' $bc 'org.bouncycastle.LICENSE'
if($LASTEXITCODE -ne 0){throw '无法从实际 Bouncy Castle 1.72 归档读取完整许可。'}
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'BouncyCastle-LICENSE.txt'),($bcLicense -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $unicode -Destination (Join-Path $licenseDirectory 'Unicode-LICENSE.txt')
Extract-ZipFile (Join-Path $projectDirectory 'gradle/wrapper/gradle-wrapper.jar') 'META-INF/LICENSE' (Join-Path $licenseDirectory 'GradleWrapper-LICENSE.txt')

# Keep the actual bundled PDFBox resource copyright headers as additional notices.
$aarUrl='https://repo.maven.apache.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar'
$aar=Fetch-Artifact $aarUrl 'pdfbox-android-2.0.27.0.aar'
$zip=[IO.Compression.ZipFile]::OpenRead($aar)
$headers=[Collections.Generic.List[string]]::new()
try{
    foreach($item in $zip.Entries){
        if($item.FullName -notmatch '^assets/.+/(glyphlist|text|unicode|cmap|afm)/'){continue}
        $reader=[IO.StreamReader]::new($item.Open())
        try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
        $lines=$text -split "`r?`n"
        $header=[Collections.Generic.List[string]]::new()
        foreach($line in $lines){
            if($line -match '^(#|%|Comment|StartFontMetrics)'){$header.Add($line)}else{break}
        }
        if($header.Count -gt 0){$headers.Add("SOURCE: $($item.FullName)`n"+($header -join "`n"))}
    }
}finally{$zip.Dispose()}
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'PDFBox-RESOURCE-NOTICES.txt'),($headers -join "`n`n"),[Text.UTF8Encoding]::new($false))

$sources=@(
    'Full license and notice collection for the actual Android runtime dependencies.',
    'Apache PDFBox LICENSE includes Adobe, Liberation Fonts SIL OFL 1.1 and TwelveMonkeys terms.',
    'PdfBox-Android 2.0.27.0 AAR/source archives do not carry separate META-INF LICENSE/NOTICE files; the Apache PDFBox 2.0.27 upstream license/notice and actual source/resource headers are retained here.',
    'Gson 2.11.0 source copyright header is retained together with the full Apache License 2.0.',
    'Bouncy Castle license is printed by org.bouncycastle.LICENSE in the exact bcprov 1.72 artifact; it also applies to its bcpkix and bcutil 1.72 modules.',
    'Unicode resource headers identify data versions 8.0.0 and 10.0.0. The full official upstream Unicode license is retained separately.',
    'Gradle Wrapper license is extracted from the exact wrapper JAR bundled with this source project.',
    '',
    "UPSTREAM $apacheUrl",
    "UPSTREAM $androidSourceUrl",
    "UPSTREAM $gsonSourceUrl",
    "UPSTREAM $bcUrl",
    "UPSTREAM $aarUrl",
    "UPSTREAM $unicodeUrl",
    ''
)
foreach($artifact in @($apache,$androidSource,$gsonSource,$bc,$aar,$unicode)){
    $sources+=('SHA256 '+(Split-Path $artifact -Leaf)+' '+(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant())
}
[IO.File]::WriteAllText((Join-Path $licenseDirectory 'SOURCES.txt'),($sources -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
foreach($file in Get-ChildItem -LiteralPath $licenseDirectory -File){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $assetDirectory $file.Name)}
Copy-Item -LiteralPath (Join-Path $projectDirectory 'LICENSE') -Destination (Join-Path $assetDirectory 'PROJECT-AGPLv3-LICENSE.txt')
foreach($file in Get-ChildItem -LiteralPath $licenseDirectory -File){
    if((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath (Join-Path $assetDirectory $file.Name)).Hash){throw 'APK许可副本校验失败。'}
}
Write-Output "完整第三方许可已保存至 $licenseDirectory 和 $assetDirectory"
