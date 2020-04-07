#Dot source the helper functions for determining which tests to run
. ./vsts/determine_tests_to_run.ps1

$runTestCmd = "mvn install -T 2C"

# Choose which profile to run as. Only one profile allowed per run, so we need one profile per possible combination
# Extra parenthesis do matter here due to these being function calls
if ((IsPullRequestBuild) -and (ShouldSkipIotHubTests) -and (ShouldSkipDPSTests))
{
    $runTestCmd += " -P PRTestsWithoutIoTHubOrDPS"
}
elseif ((IsPullRequestBuild) -and (ShouldSkipIotHubTests))
{
    $runTestCmd += " -P PRTestsWithoutIoTHub"
}
elseif ((IsPullRequestBuild) -and (ShouldSkipDPSTests))
{
    $runTestCmd += " -P PRTestsWithoutDPS"
}
elseif (IsPullRequestBuild)
{
    $runTestCmd += " -P PRTests"
}
else
{
    # No profile to add here, just run all tests
}

Write-Host "Executing the following command:"
Write-Host $runTestCmd

Invoke-Expression $runTestCmd
$gateFailed = $LASTEXITCODE

if ($gateFailed)
{
	Write-Error "Testing was not successful, exiting..."
	exit 1
}
else
{
	Write-Host "Testing was successful!"
	exit 0
}