$ErrorActionPreference = "Stop"
$files = Get-ChildItem -Path "app" -Recurse -Include "*.kt","*.java","*.xml","*.gradle","*.json","*.aidl"
$count = 0
foreach ($file in $files) {
    $content = Get-Content $file.FullName -Raw -Encoding UTF8
    if ($content -match 'popopaw') {
        $newContent = $content -replace 'popopaw','pocopaw'
        Set-Content $file.FullName -Value $newContent -Encoding UTF8 -NoNewline
        $count++
    }
}
Write-Output "Updated $count files from popopaw to pocopaw"