param([Parameter(Mandatory=$true)][string]$OutputDirectory,[string]$ToolingRoot,[string]$InputJson,[int]$ActualPageCount,[string]$EnjoyFixtureFolder,[string]$EnjoyMixedFixtureFolder)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputDirectory){throw '交付目录已存在，禁止覆盖历史交付。'}
# 使用同一套构建及测试入口；只有成功返回才允许发布打包。
$build=@{}
if($ToolingRoot){$build.ToolingRoot=$ToolingRoot}
if($InputJson){$build.InputJson=$InputJson;$build.ActualPageCount=$ActualPageCount}
if($EnjoyFixtureFolder){$build.EnjoyFixtureFolder=$EnjoyFixtureFolder}
if($EnjoyMixedFixtureFolder){$build.EnjoyMixedFixtureFolder=$EnjoyMixedFixtureFolder}
& (Join-Path $PSScriptRoot 'build.ps1') @build
$package=@{OutputDirectory=$OutputDirectory}
if($ToolingRoot){$package.ToolingRoot=$ToolingRoot}
& (Join-Path $PSScriptRoot 'package-release.ps1') @package
& (Join-Path $PSScriptRoot 'verify-release.ps1') -OutputDirectory $OutputDirectory
