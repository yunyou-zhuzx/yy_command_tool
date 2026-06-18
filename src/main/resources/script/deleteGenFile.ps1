$RepositoryRoot = '.\yy-service'
$PROJECT = ''
$MODULES = "bm"

# 定义删除目标目录的方法
function RemoveDirectory
{
    param(
        [Parameter(Mandatory = $true)]
        [string]$FolderPath
    )

    if (Test-Path $FolderPath)
    {
        Remove-Item -Force -Recurse $FolderPath
        Write-Host "Succeeded in deleting folder $FolderPath."
    }
    else
    {
        Write-Host "The $FolderPath folder does not exist." -ForegroundColor Yellow
    }
}

foreach ($module in @($MODULES))
{
    $ModulePath = Join-Path $RepositoryRoot "yy-$PROJECT-$module"

    $Folders = @('controller', 'service', 'service\impl', 'mapper')

    foreach ($folder in $Folders)
    {
        $FolderPath = Join-Path $ModulePath "src\main\java\com\yunyoucloud\$PROJECT\$module\$folder\gen"
        RemoveDirectory $FolderPath
    }

    $FolderPath = Join-Path $ModulePath "src\main\resources\mapper\gen"
    RemoveDirectory $FolderPath

    $FolderPath = Join-Path $ModulePath "static\download\template"
    RemoveDirectory $FolderPath

    $FolderPath = $ModulePath + "-api\src\main\java\com\yunyoucloud\$PROJECT\$module\api\entity\gen"
    RemoveDirectory $FolderPath
}

Write-Host "Clean gen code finish" -ForegroundColor Green
