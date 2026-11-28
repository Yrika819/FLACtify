package com.flactify.roborazzi.poc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import androidx.test.ext.junit.runners.AndroidJUnit4

/** A synthetic-only smoke test proving a Compose image can be captured in a JVM test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoborazziComposeSmokeTest {
    @Test
    fun capturesSyntheticComposeFixture() {
        captureRoboImage("build/outputs/roborazzi/synthetic-compose-smoke.png") {
            MaterialTheme {
                Surface {
                    Box(modifier = Modifier.size(160.dp)) {
                        Text("Synthetic FLACtify fixture")
                    }
                }
            }
        }
    }
}
