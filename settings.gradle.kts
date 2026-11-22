pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "SepandAI"
include(":app")
val llamaModule = file("libs/llama.kt/llama-kt")
if (!llamaModule.exists()) {
    throw GradleException("llama.kt is missing. Run scripts/bootstrap.sh before syncing/building Sepand AI.")
}
include(":llama-kt")
project(":llama-kt").projectDir = llamaModule
