plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.documents"
}

// The document viewer (docs/DOCUMENTS.md §8, D3): a page over the frame drawing a kept PDF with the platform's PdfRenderer, find in the document over the page
// text the server read, Share PDF, Save to device and Keep on this device. What it reads is core-data's (DocumentViewing, DocumentFiles).
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-data"))

    implementation(libs.androidx.activity.compose)               // the system's create-document picker: Save to device
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(project(":core-testing"))
    testImplementation(testFixtures(project(":core-sync")))
}
