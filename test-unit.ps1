param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$workspace = Split-Path (Split-Path $project -Parent) -Parent
$tools = Join-Path $workspace 'tools\android-build'
$env:JAVA_HOME = Join-Path $tools 'jdk-21.0.12.1+1'
$env:ANDROID_HOME = Join-Path $tools 'sdk'
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-home'
$gradle = Join-Path $env:GRADLE_USER_HOME 'wrapper\dists\gradle-8.13-bin\bynj8867joaln2pdnozgpow3q\gradle-8.13\bin\gradle.bat'
if (!$SkipBuild) {
    & $gradle -p $project --no-daemon :app:assembleDebug :app:compileDebugUnitTestKotlin
    if ($LASTEXITCODE -ne 0) { throw 'Android build or test compilation failed' }
}
# Direct JUnitCore avoids this host's Gradle worker startup failure on a Unicode workspace path.
$cache = Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1'
$classpath = @(
    (Join-Path $project 'app\build\tmp\kotlin-classes\debugUnitTest'),
    (Join-Path $project 'app\build\tmp\kotlin-classes\debug'),
    (Join-Path $project 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'),
    (Join-Path $cache 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2\4a9f78ef49483748e2c129f3d124b8fa249dafbf\kotlinx-coroutines-core-jvm-1.10.2.jar'),
    (Join-Path $cache 'org.jetbrains.kotlinx\kotlinx-coroutines-test-jvm\1.10.2\deb87732ba15dc455f08af4b120fca0d96d6cb5f\kotlinx-coroutines-test-jvm-1.10.2.jar'),
    (Join-Path $cache 'junit\junit\4.13.2\8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12\junit-4.13.2.jar'),
    (Join-Path $cache 'org.hamcrest\hamcrest-core\1.3\42a25dc3219429f0e5d060061f71acb49bf010a0\hamcrest-core-1.3.jar'),
    (Join-Path $cache 'org.json\json\20240303\ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a\json-20240303.jar'),
    (Join-Path $cache 'org.jetbrains.kotlin\kotlin-stdlib\2.2.21\fa374a986e128314c3db00a20aae55f72a258511\kotlin-stdlib-2.2.21.jar')
)
foreach ($entry in $classpath) { if (!(Test-Path -LiteralPath $entry)) { throw "Required test dependency missing: $entry" } }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp ($classpath -join ';') org.junit.runner.JUnitCore `
    dev.dsh.pocket.data.TranscriptTest `
    dev.dsh.pocket.data.PresentationTest `
    dev.dsh.pocket.data.PendingPromptStoreTest `
    dev.dsh.pocket.data.ProjectionStateTest `
    dev.dsh.pocket.data.RpcRejectedExceptionTest `
    dev.dsh.pocket.data.ModesTest `
    dev.dsh.pocket.cowork.ActionGateTest `
    dev.dsh.pocket.cowork.ManagedProfileScopeTest `
    dev.dsh.pocket.cowork.ProfileWindowPolicyTest `
    dev.dsh.pocket.cowork.ApprovalGateTest `
    dev.dsh.pocket.cowork.BrokerSafetyTest `
    dev.dsh.pocket.cowork.TaskLifecycleTest `
    dev.dsh.pocket.cowork.SecureWindowSnapshotTest `
    dev.dsh.pocket.cowork.LauncherEntrySweepTest `
    dev.dsh.pocket.cowork.InstallWatchTest `
    dev.dsh.pocket.cowork.StrayTaskSweepTest `
    dev.dsh.pocket.cowork.PopupPermissionPolicyTest `
    dev.dsh.pocket.cowork.StrokePathTest `
    dev.dsh.pocket.cowork.ScreenZoomTest `
    dev.dsh.pocket.island.IslandExecutionTest `
    dev.dsh.pocket.cowork.CoworkStateBridgeTest `
    dev.dsh.pocket.cowork.CoworkLinkStateTest `
    dev.dsh.pocket.cowork.ToolContractTest `
    dev.dsh.pocket.cowork.BrokerSourceTest `
    dev.dsh.pocket.cowork.TakeoverTest `
    dev.dsh.pocket.cowork.RefusalPolicyTest `
    dev.dsh.pocket.cowork.BatchPolicyTest `
    dev.dsh.pocket.cowork.ActivityLogTest `
    dev.dsh.pocket.island.IslandStoreTest `
    dev.dsh.pocket.island.IslandPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'JVM regression tests failed' }

