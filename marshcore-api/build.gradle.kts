plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName.set("marshcore-api")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("flk_version")}")
}
