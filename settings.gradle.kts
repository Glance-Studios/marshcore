pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "marshcore"

include("marshcore-api")
include("marshcore-server")
include("marshcore-client")
