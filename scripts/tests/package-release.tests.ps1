param([Parameter(Mandatory=$true)][string]$TestDirectory,[string]$ToolingRoot,[string]$InputApk)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$ToolingRoot){$ToolingRoot=[IO.Path]::GetFullPath((Join-Path $project '../../tooling'))}
if(!$InputApk){$InputApk=Join-Path $project 'app/build/outputs/apk/debug/app-debug.apk'}
$testRoot=[IO.Path]::GetFullPath($TestDirectory)
if(Test-Path -LiteralPath $testRoot){throw '测试目录已存在，不覆盖已有测试证据。'}
$badging=@(& (Join-Path $ToolingRoot 'sdk/build-tools/35.0.0/aapt.exe') dump badging $InputApk)
if($LASTEXITCODE -ne 0){throw '测试需要已有的有效APK作为只读输入。'}
$identity=[regex]::Match(($badging -join "`n"),"package: name='cn\.local\.pdfbookmarks' versionCode='(\d+)' versionName='([^']+)'")
if(!$identity.Success){throw '测试APK包名错误。'}
$version=$identity.Groups[2].Value;$code=[int]$identity.Groups[1].Value
$fixture=Join-Path $testRoot 'source'
New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts'),(Join-Path $fixture 'app/build/outputs/apk/debug'),(Join-Path $fixture 'verification/private') | Out-Null
Copy-Item -LiteralPath (Join-Path $project 'scripts/package-release.ps1') -Destination (Join-Path $fixture 'scripts/package-release.ps1')
$apk=Join-Path $fixture 'app/build/outputs/apk/debug/app-debug.apk'
Copy-Item -LiteralPath $InputApk -Destination $apk
$expectedCertificate=(Get-Content -LiteralPath (Join-Path $project 'SIGNING_CERTIFICATE.sha256') -Raw).Trim()
$metadata=@{applicationId='cn.local.pdfbookmarks';elements=@(@{versionName=$version;versionCode=$code;outputFile='app-debug.apk'})}
function ResetFixture{
    [IO.File]::WriteAllText((Join-Path $fixture 'VERSION'),$version)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/build.gradle'),('versionCode '+$code))
    [IO.File]::WriteAllText((Join-Path $fixture 'SIGNING_CERTIFICATE.sha256'),$expectedCertificate)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/build/outputs/apk/debug/output-metadata.json'),($metadata | ConvertTo-Json -Depth 5))
}
ResetFixture
# 全部是合成输入；证明源码包不会携带用户PDF、私钥、环境秘密或诊断资料。
foreach($name in @('sample.pdf','sample.key','.env','verification/private/record.json')){[IO.File]::WriteAllText((Join-Path $fixture $name),'synthetic excluded data')}
$results=[Collections.Generic.List[object]]::new()
function Snapshot([string]$path){
    if(!(Test-Path -LiteralPath $path)){return 'ABSENT'}
    (@(Get-ChildItem -LiteralPath $path -Recurse -File -Force | Sort-Object FullName | ForEach-Object {$_.FullName+' '+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join "`n")
}
function Rejected([string]$name,[string]$target,[string]$reason){
    $before=Snapshot $target;$message=''
    try{& (Join-Path $fixture 'scripts/package-release.ps1') -OutputDirectory $target -ToolingRoot $ToolingRoot | Out-Null}
    catch{$message=$_.Exception.Message}
    if(!$message.Contains($reason)){throw ($name+' 未得到预期拒绝：'+$message)}
    if((Snapshot $target) -cne $before){throw ($name+' 在拒绝前改变了交付或源码')}
    $results.Add(@{case=$name;passed=$true;reason=$reason;targetUnchanged=$true})
}
try{
    $existing=Join-Path $testRoot 'existing'
    New-Item -ItemType Directory -Path $existing | Out-Null
    [IO.File]::WriteAllText((Join-Path $existing ('PDF_Bookmarks_v'+$version+'_android.apk')),'synthetic old release')
    Rejected 'existing-release' $existing '交付目录已存在'
    Rejected 'source-root' $fixture '交付目录应位于源码工程外'
    Rejected 'inside-source' (Join-Path $fixture 'delivery') '交付目录应位于源码工程外'
    $metadata.elements[0].versionName='9.9.9';ResetFixture
    Rejected 'stale-metadata' (Join-Path $testRoot 'stale') '版本不一致'
    $metadata.elements[0].versionName=$version;ResetFixture
    [IO.File]::WriteAllText((Join-Path $fixture 'SIGNING_CERTIFICATE.sha256'),('0'*64))
    Rejected 'wrong-upgrade-certificate' (Join-Path $testRoot 'wrong-key') 'APK升级签名不一致'
    ResetFixture
    [IO.File]::WriteAllText($apk,'synthetic unsigned APK')
    Rejected 'invalid-signature' (Join-Path $testRoot 'unsigned') 'APK签名校验失败'
    Copy-Item -LiteralPath $InputApk -Destination $apk
    [IO.File]::WriteAllText((Join-Path $fixture 'VERSION'),'9.9.9')
    $metadata.elements[0].versionName='9.9.9'
    [IO.File]::WriteAllText((Join-Path $fixture 'app/build/outputs/apk/debug/output-metadata.json'),($metadata | ConvertTo-Json -Depth 5))
    Rejected 'actual-apk-version' (Join-Path $testRoot 'wrong-version') 'APK实际版本与源码不一致'
    $metadata.elements[0].versionName=$version;ResetFixture
    $success=Join-Path $testRoot 'success'
    & (Join-Path $fixture 'scripts/package-release.ps1') -OutputDirectory $success -ToolingRoot $ToolingRoot | Out-Null
    if((Get-FileHash -LiteralPath $InputApk).Hash -cne (Get-FileHash -LiteralPath (Join-Path $success ('PDF_Bookmarks_v'+$version+'_android.apk'))).Hash){throw '正常打包APK不一致'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $success ('PDF_Bookmarks_v'+$version+'_android_source.zip')))
    try{
        $names=@($zip.Entries.FullName)
        if($names -notcontains 'scripts/package-release.ps1' -or $names -notcontains 'SIGNING_CERTIFICATE.sha256'){throw '源码缺少维护入口'}
        if(@($names | Where-Object {$_ -match 'verification/|\.pdf$|\.key$|(^|/)\.env$'}).Count -ne 0){throw '源码包含合成私有输入'}
    }finally{$zip.Dispose()}
    foreach($line in Get-Content -LiteralPath (Join-Path $success 'SHA256SUMS.txt')){
        $parts=$line -split '  ',2
        if((Get-FileHash -LiteralPath (Join-Path $success $parts[1])).Hash.ToLowerInvariant() -cne $parts[0]){throw '交付哈希清单不一致'}
    }
    $results.Add(@{case='valid-package-and-exclusions';passed=$true})
    Rejected 'repeat-package' $success '交付目录已存在'
}finally{
    [IO.File]::WriteAllText((Join-Path $testRoot 'results.json'),($results | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
}
Write-Output ('Packaging regression passed: '+$results.Count+'; fixtures retained: '+$testRoot)
