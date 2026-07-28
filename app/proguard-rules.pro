# ProGuard/R8 rules for release builds.
#
# Minification is currently disabled (isMinifyEnabled = false in build.gradle.kts),
# so nothing here is applied. The file exists because build.gradle.kts references it
# via proguardFiles, and to give a home to any rules needed if minification is ever
# switched on.
#
# If minification is enabled later, note that TranscriptionHistory and PolishPresets
# both persist data as JSON built by hand with org.json (no reflection), so no
# keep rules are required for them.
