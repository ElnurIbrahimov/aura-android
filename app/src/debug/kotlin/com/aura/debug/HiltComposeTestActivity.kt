package com.aura.debug

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty `@AndroidEntryPoint` host for instrumented Compose tests that need
 * `hiltViewModel()` to resolve.
 *
 * `createComposeRule()` launches a plain `ComponentActivity`, which is not a Hilt
 * entry point, so `hiltViewModel()` cannot find a factory there and every screen
 * that defaults its ViewModels would fail for a reason unrelated to the screen.
 * `UiCatalogActivity` is not usable either: it is deliberately Hilt-free and
 * renders stateless `*Content` composables from hand-built state, so it can never
 * exercise the ViewModel-construction path.
 *
 * That path is the one worth testing. `MindScreen` and `DreamsScreen` both shipped
 * defaulting a `@HiltViewModel` with Compose's plain `viewModel()` factory, which
 * throws on navigation — and no test in the repo composes a screen, so both routes
 * were unopenable for their entire existence while 3,387 unit tests stayed green.
 *
 * Debug source set rather than androidTest so it lands in the app under test and
 * gets a manifest entry; `app/src/debug/AndroidManifest.xml` declares it.
 */
@AndroidEntryPoint
class HiltComposeTestActivity : ComponentActivity()
