$mavenHome = "D:\JAVA\apache-maven-3.6.3"
[Environment]::SetEnvironmentVariable("MAVEN_HOME", $mavenHome, "User")

$mavenBin = "$mavenHome\bin"
$currentPath = [Environment]::GetEnvironmentVariable("Path", "User")

if ($currentPath -split ";" -notcontains $mavenBin) {
    if ($currentPath -and -not $currentPath.EndsWith(";")) {
        $newPath = "$currentPath;$mavenBin"
    }
    else {
        $newPath = "$currentPath$mavenBin"
    }
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    Write-Host "MAVEN_HOME set and bin added to Path."
}
else {
    Write-Host "Maven bin already in Path."
}
