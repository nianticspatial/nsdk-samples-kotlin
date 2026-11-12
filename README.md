# NSDK Kotlin Samples

An Android project demonstrating the Niantic Spatial SDK (NSDK) — depth, occlusion,
meshing, semantic segmentation, device mapping, scanning, and VPS2 localization.

## Quick Links
* [Documentation](https://www.nianticspatial.com/docs/nsdk/?platform=kotlin)
* [NSDK API Reference](https://www.nianticspatial.com/docs/api/kotlin/)
* [Sample project walkthroughs](https://www.nianticspatial.com/docs/nsdk/sample_projects/?platform=kotlin)
* [NSDK Android library](https://github.com/nianticspatial/nsdk-library-aar)

## Requirements

* Android Studio with the Android SDK, `compileSdk` 36 installed
* JDK 11
* [Git LFS](https://git-lfs.com) — required to check out any LFS-tracked assets
* An ARCore-capable Android device — the samples use live camera and sensor data and do
  not run on an emulator
* A Niantic Spatial access token, for the samples that use VPS2 or Sites

The project targets `minSdk` 24 and builds with Gradle 8.11 via the bundled wrapper.

## Getting Started

1. Clone this repository.
2. Fetch any LFS-tracked assets:

   ```sh
   git lfs install
   git lfs pull
   ```

3. Open `NsdkSamples/` in Android Studio and let it sync.
4. Set your access token in `NsdkSamples/build.gradle.kts`:

   ```kotlin
   buildConfigField("String", "ACCESS_TOKEN", "\"YOUR_TOKEN_HERE\"")
   ```

   To create a token, sign in to [Scaniverse Web](https://scaniverse.nianticspatial.com),
   open **Developer tokens** for your organization, and select **New developer token**.
   Copy it before closing the dialog — it is shown only once — and note that it expires on
   the date you choose. See the
   [setup guide](https://www.nianticspatial.com/docs/nsdk/setup/?platform=kotlin) for more.
   Playback Select lists your organization's scans, which a token scoped only to
   `site-assets:read` cannot do; the request fails with `HTTP_FORBIDDEN`.
5. Run the `NsdkSamples` configuration on a connected device.

The app opens on a list of samples; each entry launches one feature.

## What's included

| Sample | What it shows |
|---|---|
| Auth | Authenticating the SDK with an access token |
| Camera stream | Access to the AR camera feed |
| Depth | Per-frame depth estimation |
| Occlusion | Occluding virtual content with estimated depth |
| Meshing | Real-time mesh reconstruction of the environment |
| Mesh downloading | Fetching prebuilt meshes for a location |
| Scene segmentation | Semantic segmentation of the camera feed |
| Device mapping | Building and saving a device map |
| VPS2 | Visual Positioning System localization |
| VPS2 debugger | Diagnostics for VPS2 localization |
| Sites | Discovering and loading nearby sites |
| Capture | Recording sessions for playback |

### Map tiles in the VPS2 sample

The VPS2 sample's mini-map draws its basemap from
[OpenFreeMap](https://openfreemap.org), a free keyless tile service, so the sample runs without a
map account or API token. It is a community service with no uptime guarantee and is not intended
for production use — point `MAP_STYLE_URI` in `VPS2MapView.kt` at your own style URL before
shipping anything built on this sample. The map also needs network access; without it the map card
reports that tiles are unavailable.

## License

See [LICENSE](LICENSE).
