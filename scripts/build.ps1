param([string[]]$Tasks=@('assembleDebug','lintDebug','testDebugUnitTest'),[string]$ToolingRoot,[string]$InputJson,[int]$ActualPageCount,[string]$EnjoyFixtureFolder,[string]$EnjoyMixedFixtureFolder)
$ErrorActionPreference='Stop'
$projectDir=Split-Path $PSScriptRoot -Parent
if(!$ToolingRoot){$ToolingRoot=[IO.Path]::GetFullPath((Join-Path $projectDir '../../tooling'))}
$jdkDir=Get-ChildItem -LiteralPath (Join-Path $ToolingRoot 'jdk') -Directory | Select-Object -First 1
if(!$jdkDir){throw '请通过 -ToolingRoot 指定含JDK和Android SDK的工具目录。'}
$env:JAVA_HOME=$jdkDir.FullName
$env:ANDROID_HOME=Join-Path $ToolingRoot 'sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME=Join-Path $ToolingRoot 'android-user-home'
$env:GRADLE_USER_HOME=Join-Path $ToolingRoot 'gradle-home'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
Push-Location $projectDir
try{
    $extra=@()
    if($InputJson){if($ActualPageCount -lt 1){throw '真实JSON回归需要实际PDF总页数'};$extra=@(('-Djasminum.fixture='+$InputJson),('-Djasminum.actualPageCount='+$ActualPageCount))}
    if($EnjoyFixtureFolder){if(!$InputJson -or !(Test-Path -LiteralPath $EnjoyFixtureFolder -PathType Container)){throw '享做实际样本回归需要对应JSON、实际页数和只读样本目录'};$extra+=('-Denjoy.fixtureFolder='+$EnjoyFixtureFolder)}
    if($EnjoyMixedFixtureFolder){if(!(Test-Path -LiteralPath $EnjoyMixedFixtureFolder -PathType Container)){throw '混合页码回归需要只读样本目录'};$extra+=('-Denjoy.mixedFixtureFolder='+$EnjoyMixedFixtureFolder)}
    & './gradlew.bat' @Tasks @extra '--no-daemon' '--console=plain'
    if($LASTEXITCODE -ne 0){throw 'Android构建/检查失败'}
}finally{Pop-Location}
