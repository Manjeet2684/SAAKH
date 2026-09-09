<# : batch portion
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_PSMODULEP_SAVE__=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir='%~dp0'; $script='%__MVNW_ARG0_NAME__%'; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw '%~f0'))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE__%
@SET __MVNW_PSMODULEP_SAVE__=
@SET __MVNW_ARG0_NAME__=
@IF NOT "%__MVNW_CMD__%"=="" ("%__MVNW_CMD__%" %*)
@echo Cannot start maven from wrapper >&2 && exit /b 1
@GOTO :EOF
: end batch / begin powershell #>

$ErrorActionPreference = "Stop"
$distributionUrl = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}

$MVN_CMD = $script -replace '^mvnw','mvn'
$MAVEN_HOME_PARENT = "$HOME/.m2/wrapper/dists/$($distributionUrl -replace '^.*/' -replace '\.[^.]*$','' -replace '-bin$','')"
$MAVEN_HOME_NAME = ([System.Security.Cryptography.MD5]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"

if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  exit 0
}

if (-not $distributionUrl.EndsWith(".zip")) {
  Write-Error "distributionUrl is not valid, must end with *.zip, was $distributionUrl"
}

New-Item -ItemType Directory -Force -Path "$MAVEN_HOME_PARENT" | Out-Null
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

$DOWNLOAD_URL_FILE = "$TMP_DOWNLOAD_DIR/$(Split-Path $distributionUrl -Leaf)"
$webclient = New-Object System.Net.WebClient
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, $DOWNLOAD_URL_FILE)

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($DOWNLOAD_URL_FILE, $TMP_DOWNLOAD_DIR)

$actualDistributionDir = (Get-ChildItem -Path $TMP_DOWNLOAD_DIR -Directory | Select-Object -First 1).Name
Copy-Item -Path "$TMP_DOWNLOAD_DIR/$actualDistributionDir" -Destination $MAVEN_HOME -Recurse
Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
