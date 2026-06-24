plugins {
    id("buildlogic.gamelan-profiles")
}

group = providers.gradleProperty("gamelan.group").get()
version = providers.gradleProperty("gamelan.version").get()
