param([Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$ToolingRoot)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectDirectory=Split-Path $PSScriptRoot -Parent
$version=(Get-Content -LiteralPath (Join-Path $projectDirectory 'VERSION') -Raw).Trim()
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'VERSION不是有效的三段版本号。'}
$outputDirectoryFull=[IO.Path]::GetFullPath($OutputDirectory)
if($outputDirectoryFull.Equals($projectDirectory,[StringComparison]::OrdinalIgnoreCase)-or $outputDirectoryFull.StartsWith($projectDirectory+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '交付目录应位于源码工程外，避免把发布文件再次打包。'}
if(Test-Path -LiteralPath $outputDirectoryFull){throw '交付目录已存在，禁止覆盖历史交付；请选择新的版本或新的空位置。'}
$apkName='PDF_Bookmarks_v'+$version+'_android.apk'
$sourceName='PDF_Bookmarks_v'+$version+'_android_source.zip'
$built=Join-Path $projectDirectory 'app/build/outputs/apk/debug/app-debug.apk'
if(!(Test-Path -LiteralPath $built -PathType Leaf)){throw '先构建APK再打包。'}
$metadata=Get-Content -LiteralPath (Join-Path $projectDirectory 'app/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
$codeMatch=[regex]::Match((Get-Content -LiteralPath (Join-Path $projectDirectory 'app/build.gradle') -Raw),'versionCode\s+([1-9]\d*)')
if(!$codeMatch.Success -or $metadata.applicationId -cne 'cn.local.pdfbookmarks' -or $metadata.elements.Count -ne 1 -or $metadata.elements[0].versionName -cne $version -or [int]$metadata.elements[0].versionCode -ne [int]$codeMatch.Groups[1].Value){throw '构建产物与源码版本不一致，请重新构建。'}
if(!$ToolingRoot){$ToolingRoot=[IO.Path]::GetFullPath((Join-Path $projectDirectory '../../tooling'))}
$jdk=Get-ChildItem -LiteralPath (Join-Path $ToolingRoot 'jdk') -Directory | Select-Object -First 1
if(!$jdk){throw '缺少JDK，请用-ToolingRoot指定现有工具目录。'}
$env:JAVA_HOME=$jdk.FullName
$tools=Join-Path $ToolingRoot 'sdk/build-tools/35.0.0'
$expectedCertificate=(Get-Content -LiteralPath (Join-Path $projectDirectory 'SIGNING_CERTIFICATE.sha256') -Raw).Trim().ToLowerInvariant()
if($expectedCertificate -notmatch '^[0-9a-f]{64}$'){throw '升级签名指纹记录无效。'}
$signature=@(& (Join-Path $tools 'apksigner.bat') verify --verbose --print-certs $built 2>&1)
if($LASTEXITCODE -ne 0){throw 'APK签名校验失败，不生成交付。'}
$certificate=[regex]::Match(($signature -join "`n"),'certificate SHA-256 digest: ([0-9a-fA-F]{64})')
if(!$certificate.Success -or $certificate.Groups[1].Value.ToLowerInvariant() -cne $expectedCertificate){throw 'APK升级签名不一致，禁止作为覆盖更新交付。'}
$badging=@(& (Join-Path $tools 'aapt.exe') dump badging $built 2>&1)
if($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch ("package: name='cn\.local\.pdfbookmarks' versionCode='"+$codeMatch.Groups[1].Value+"' versionName='"+[regex]::Escape($version)+"'")){throw 'APK实际版本与源码不一致，不生成交付。'}
& (Join-Path $tools 'zipalign.exe') -c 4 $built | Out-Null
if($LASTEXITCODE -ne 0){throw 'APK对齐校验失败，不生成交付。'}
# 所有检查通过才创建全新的交付目录；失败不自动删除任何文件。
New-Item -ItemType Directory -Path $outputDirectoryFull | Out-Null
Copy-Item -LiteralPath $built -Destination (Join-Path $outputDirectoryFull $apkName)
$sourcePath=Join-Path $outputDirectoryFull $sourceName
$stream=[IO.File]::Open($sourcePath,[IO.FileMode]::CreateNew)
$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
try{
    $files=Get-ChildItem -LiteralPath $projectDirectory -Recurse -File -Force | Sort-Object FullName
    foreach($file in $files){
        $relative=$file.FullName.Substring($projectDirectory.Length+1).Replace('\','/')
        if($relative -match '(^|/)(\.gradle|build|work|deps|verification|__pycache__|\.git|sources|books|tooling|signing)/|(^|/)(local\.properties|\.env(\..*)?)$|\.(pdf|apk|exe|zip|keystore|jks|p12|pfx|pem|key)$'){continue}
        if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw '源码包不包含链接文件。'}
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}finally{$zip.Dispose();$stream.Dispose()}
$lines=@()
foreach($name in @($apkName,$sourceName)){
    $file=Join-Path $outputDirectoryFull $name
    $hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $line=$hash+'  '+$name
    $lines+=$line
    [IO.File]::WriteAllText($file+'.sha256',$line+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText((Join-Path $outputDirectoryFull 'SHA256SUMS.txt'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$lines
