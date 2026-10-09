param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceRoot=Split-Path $PSScriptRoot -Parent
$version=(Get-Content -LiteralPath (Join-Path $sourceRoot 'VERSION') -Raw).Trim()
$apkName='PDF_Bookmarks_v'+$version+'_android.apk'
$zipName='PDF_Bookmarks_v'+$version+'_android_source.zip'
$apkPath=Join-Path $OutputDirectory $apkName
$zipPath=Join-Path $OutputDirectory $zipName
function FileHash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function EntryHash($entry){
    $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create()
    try{[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}
    finally{$stream.Dispose();$sha.Dispose()}
}
$lines=@(Get-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt'))
if($lines.Count -ne 2){throw 'SHA256清单必须准确包含APK和源码包。'}
$listed=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($line in $lines){
    $parts=$line -split '  ',2
    if($parts.Count -ne 2 -or $parts[1] -cnotin @($apkName,$zipName)-or !$listed.Add($parts[1]) -or (FileHash (Join-Path $OutputDirectory $parts[1])) -cne $parts[0]){throw 'SHA256清单名称或文件内容不一致。'}
    if((Get-Content -LiteralPath (Join-Path $OutputDirectory ($parts[1]+'.sha256')) -Raw).Trim() -cne $line){throw '独立SHA256与总清单不一致。'}
}
if((FileHash $apkPath) -cne (FileHash (Join-Path $sourceRoot 'app/build/outputs/apk/debug/app-debug.apk'))){throw '交付APK与本次构建不一致。'}
$names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$zip=[IO.Compression.ZipFile]::OpenRead($zipPath)
try{
    foreach($entry in $zip.Entries){
        $name=$entry.FullName
        if(!$names.Add($name) -or $name -match '(^/|\|:|(^|/)\.\.(/|$))|(^|/)(work|verification|deps|build|tooling|sources|books|signing|\.git|\.gradle)/|(^|/)(local\.properties|\.env(\..*)?)$|\.(pdf|apk|exe|zip|keystore|jks|p12|pfx|pem|key)$'){throw ('源码包有重复、私有或不安全条目：'+$name)}
        $current=Join-Path $sourceRoot $name
        if(!(Test-Path -LiteralPath $current -PathType Leaf)-or (EntryHash $entry) -cne (FileHash $current)){throw ('源码包与当前源文件不一致：'+$name)}
    }
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src') -Recurse -File){
        $relative=$file.FullName.Substring($sourceRoot.Length+1).Replace('\','/')
        if(!$names.Contains($relative)){throw ('源码包缺少应用源码或资源：'+$relative)}
    }
    foreach($required in @('VERSION','SIGNING_CERTIFICATE.sha256','LICENSE','THIRD_PARTY_NOTICES.md','gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties','build.gradle','settings.gradle','app/build.gradle','README.md','docs/REQUIREMENTS.md','docs/MAINTENANCE.md','docs/DEVICE_ACCEPTANCE.md','scripts/build.ps1','scripts/release.ps1','scripts/package-release.ps1','scripts/verify-release.ps1','scripts/tests/package-release.tests.ps1','.github/workflows/android.yml')){if(!$names.Contains($required)){throw ('源码包缺少维护依赖：'+$required)}}
}finally{$zip.Dispose()}
$licenseFiles=@(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'app/src/main/assets/licenses') -File)
$apk=[IO.Compression.ZipFile]::OpenRead($apkPath)
try{
    $licenses=@($apk.Entries | Where-Object {$_.FullName.StartsWith('assets/licenses/')})
    if($licenses.Count -ne $licenseFiles.Count){throw 'APK许可数量不一致。'}
    foreach($file in $licenseFiles){
        $entries=@($licenses | Where-Object {$_.FullName -ceq 'assets/licenses/'+$file.Name})
        if($entries.Count -ne 1 -or (EntryHash $entries[0]) -cne (FileHash $file.FullName)){throw ('APK许可资源不一致：'+$file.Name)}
    }
}finally{$apk.Dispose()}
[pscustomobject]@{passed=$true;version=$version;apkSha256=(FileHash $apkPath);sourceSha256=(FileHash $zipPath);sourceFiles=$names.Count;licenseFiles=$licenseFiles.Count} | ConvertTo-Json
