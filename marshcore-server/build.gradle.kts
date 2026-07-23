plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName.set("marshcore-server")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("flk_version")}")

    // Jar-in-jar rather than compiling the shared classes into both mods: Fabric mods share a
    // classloader, so two copies would clash if anyone installs both. Loader dedups nested jars.
    implementation(project(":marshcore-api"))
    include(project(":marshcore-api"))
}
