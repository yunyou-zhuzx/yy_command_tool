$ip = ""
$projectName = ""
$type = "server"
$service = ""

$user=""
$isPackage=""
$needReload=""
$deployWay=""
$pluginScriptDir = ""

try
{
    $url = "http://192.168.10.60/gen/code/script/text/ddx.ps1"
    $scriptCotent =  Invoke-WebRequest -Uri $url -UseBasicParsing -ErrorAction Stop | Select-Object -expandproperty content
    if ($scriptCotent) {
        Invoke-Expression -Command $scriptCotent
    } else {
        Write-Host "Network Error: Script content is not available"  -ForegroundColor Red
        exit 1
    }
}
catch
{
    Write-Host "Failed to execute script: $_"  -ForegroundColor Red
    exit 1
}
