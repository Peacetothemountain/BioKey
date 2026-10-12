package com.android.BioKey.feature.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.BioKey.R
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.core.security.SecurityEngine
import com.android.BioKey.core.security.SecurityVault
import com.android.BioKey.feature.lock.BioKeyAccessibilityService
import com.android.BioKey.feature.lock.DeviceAdminHelper
import com.android.BioKey.feature.setup.SetupScreenExpressive
import com.android.BioKey.ui.theme.ExpressiveTokens
import com.android.BioKey.ui.theme.springPress

class MainActivity : FragmentActivity() {

    private var isAuthenticated by mutableStateOf(false)
    private var hasPromptedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hardware window protection: prevent screenshots, screen recording, and recents thumbnail leaks
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (darkTheme) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                }
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }

            val vault = remember { SecurityVault.getInstance(this) }
            val setupCompleted by vault.setupCompletedFlow.collectAsState()

            var a11yActive by remember { mutableStateOf(BioKeyAccessibilityService.isServiceEnabled(this)) }
            var isAdminActive by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(this)) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        a11yActive = BioKeyAccessibilityService.isServiceEnabled(this@MainActivity)
                        isAdminActive = DeviceAdminHelper.isAdminActive(this@MainActivity)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(setupCompleted) {
                if (!setupCompleted) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        a11yActive = BioKeyAccessibilityService.isServiceEnabled(this@MainActivity)
                        isAdminActive = DeviceAdminHelper.isAdminActive(this@MainActivity)
                    }
                }
            }

            val allRequiredPermissionsGranted = a11yActive && isAdminActive

            MaterialTheme(colorScheme = colorScheme) {
                if (!setupCompleted || !allRequiredPermissionsGranted) {
                    SetupScreenExpressive(
                        onSetupComplete = {
                            vault.setSetupCompleted(true)
                            authenticateBioKey(
                                onSuccess = {
                                    LockStateTracker.onAppUnlocked(packageName)
                                }
                            )
                        }
                    )
                } else if (isAuthenticated) {
                    MainScreenExpressive()
                } else {
                    BioKeyLockGate(
                        onAuthenticate = { authenticateBioKey() },
                        onExit = { finishAffinity() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val vault = SecurityVault.getInstance(this)

        val a11yActive = BioKeyAccessibilityService.isServiceEnabled(this)
        val isAdminActive = DeviceAdminHelper.isAdminActive(this)
        val allRequiredGranted = a11yActive && isAdminActive

        // During setup phase or if required permissions missing: DO NOT prompt or require authentication!
        if (!vault.isSetupCompleted() || !allRequiredGranted) {
            isAuthenticated = true
            return
        }

        if (!vault.isGlobalLockEnabled()) {
            isAuthenticated = true
            return
        }

        // Check if unlocked according to timeout mode
        if (LockStateTracker.isAppUnlocked(packageName, vault.getTimeoutMode())) {
            isAuthenticated = true
            LockStateTracker.onAppResumed(packageName)
            return
        }

        // Lock expired or app was exited: require authentication
        isAuthenticated = false
        hasPromptedOnce = false

        if (!SecurityEngine.INSTANCE.isAuthenticating()) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !isAuthenticated && !SecurityEngine.INSTANCE.isAuthenticating()) {
                    authenticateBioKey(
                        onSuccess = {
                            LockStateTracker.onAppUnlocked(packageName)
                        }
                    )
                }
            }, 200)
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        val vault = SecurityVault.getInstance(this)
        if (!vault.isSetupCompleted()) {
            // During setup phase: keep unblocked so switching to Settings doesn't trigger lock
            return
        }
        if (vault.isGlobalLockEnabled()) {
            LockStateTracker.onAppExited(packageName, vault.getTimeoutMode())
        }
    }

    override fun onDestroy() {
        SecurityEngine.INSTANCE.resetAuthenticatingState()
        super.onDestroy()
    }

    private fun authenticateBioKey(onSuccess: (() -> Unit)? = null) {
        if (SecurityEngine.INSTANCE.isAuthenticating()) return

        SecurityEngine.INSTANCE.showBiometricPrompt(
            activity = this,
            appName = "BioKey",
            packageName = packageName,
            onSuccess = {
                isAuthenticated = true
                hasPromptedOnce = true
                onSuccess?.invoke()
            },
            onFailure = {
                // Keep gate locked
            },
            onError = { _, _ ->
                // Leave BioKeyLockGate accessible so the user can tap "Unlock BioKey" or "Exit"
            }
        )
    }
}

@Composable
private fun BioKeyLockGate(
    onAuthenticate: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            shape = ExpressiveTokens.Shapes.BentoCardLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(96.dp)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_letters),
                        contentDescription = "Bio Key Logo Letters",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.primary,
                            BlendMode.Modulate
                        )
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_title_logo_tentacles),
                        contentDescription = "Bio Key Logo Tentacles",
                        modifier = Modifier.fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.tertiary,
                            BlendMode.Modulate
                        )
                    )
                }


                Text(
                    text = "Hardware Authentication Required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(36.dp))

                Button(
                    onClick = onAuthenticate,
                    shape = ExpressiveTokens.Shapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .springPress()
                ) {
                    Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Unlock BioKey",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(
                    onClick = onExit,
                    modifier = Modifier.springPress()
                ) {
                    Text(
                        text = "Exit",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
