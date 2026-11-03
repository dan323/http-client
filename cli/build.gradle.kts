// Command-line front end: parses a .http file and runs its requests.
plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":adapters"))
    testImplementation(project(":mock-server"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.dan323.httpclient.cli.MainKt")
    applicationName = "httpc"
}
