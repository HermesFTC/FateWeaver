plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.deployer)
}

android {
    namespace = "gay.zharel.hermes.fateweaver"
    //noinspection GradleDependency
    compileSdk = 33

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {}
    }
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.ftcsdk)

    api(project(":core"))
}

val dokkaJar = tasks.register<Jar>("dokkaJar") {
    dependsOn(tasks.named("dokkaGenerate"))
    from(dokka.basePublicationsDirectory.dir("html"))
    archiveClassifier.set("html-docs")
}

deployer {
    projectInfo {
        artifactId.set("ftc")
        description.set("Integration of FateWeaver loggers into the FTC Lifecycle.")
    }

    content {
        androidComponents("release") {
            kotlinSources()
            docs(dokkaJar)
        }
    }
}

fun getBunExecutable(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    if (os.isWindows) {
        return "bun.cmd"
    }
    val home = System.getProperty("user.home")
    val bunInHome = file("$home/.bun/bin/bun")
    if (bunInHome.exists()) {
        return bunInHome.absolutePath
    }
    return "bun"
}

val installFrontend by tasks.registering(Exec::class) {
    workingDir = file("../frontend")
    commandLine(getBunExecutable(), "install")
    
    inputs.files(fileTree("../frontend") {
        include("package.json", "bun.lockb", "bun.lock")
    })
    outputs.dir(file("../frontend/node_modules"))
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontend)
    workingDir = file("../frontend")
    commandLine(getBunExecutable(), "run", "build")
    
    inputs.dir(file("../frontend/src"))
    inputs.file(file("../frontend/package.json"))
    inputs.file(file("../frontend/vite.config.ts"))
    outputs.dir(file("src/main/assets/web/fate"))
}

tasks.named("preBuild") {
    dependsOn(buildFrontend)
}