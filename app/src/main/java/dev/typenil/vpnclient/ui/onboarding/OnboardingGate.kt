package dev.typenil.vpnclient.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.data.settings.shouldGrandfather
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** What the root shows: a neutral frame, first-run screens, or the app. */
enum class OnboardingGate { Loading, Needed, Done }

private const val READ_RETRIES = 3
private const val READ_RETRY_DELAY_MS = 500L

/**
 * Gate state from the persisted flag. A missing flag first asks
 * [isExistingInstall] — an update of a used install is marked done silently
 * instead of showing first-run screens. Nothing here writes the flag for a
 * fresh install; only [finishOnboarding] does.
 *
 * [done] may fail with [IOException]: it is re-read up to [retries] times
 * [retryDelayMs] apart (Loading meanwhile), then the gate falls back to
 * [OnboardingGate.Needed] — a repeated first-run screen beats a blank one for
 * an install whose flag cannot be read. [finished] (the user pressed the last
 * Continue) opens the app even if the flag could not be written.
 */
internal fun onboardingGate(
    done: Flow<Boolean>,
    isExistingInstall: suspend () -> Boolean,
    markDone: suspend () -> Unit,
    finished: Flow<Boolean> = flowOf(false),
    retries: Int = READ_RETRIES,
    retryDelayMs: Long = READ_RETRY_DELAY_MS,
): Flow<OnboardingGate> = combine(
    decide(done, isExistingInstall, markDone, retries, retryDelayMs),
    finished,
) { gate, userFinished -> if (userFinished) OnboardingGate.Done else gate }

private fun decide(
    done: Flow<Boolean>,
    isExistingInstall: suspend () -> Boolean,
    markDone: suspend () -> Unit,
    retries: Int,
    retryDelayMs: Long,
): Flow<OnboardingGate> = done
    .retryWhen { cause, attempt ->
        (cause is IOException && attempt < retries).also { if (it) delay(retryDelayMs) }
    }
    .map<Boolean, Boolean?> { it }
    .catch { if (it is IOException) emit(null) else throw it }
    .distinctUntilChanged()
    .transformLatest { flag ->
        when (flag) {
            null -> emit(OnboardingGate.Needed)
            true -> emit(OnboardingGate.Done)
            false -> {
                emit(OnboardingGate.Loading)
                // A failed read means "unknown": showing first-run screens is the safe side.
                val existing = try {
                    isExistingInstall()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (existing) {
                    // If the write fails the session still opens; the next launch re-decides.
                    try {
                        markDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    emit(OnboardingGate.Done)
                } else {
                    emit(OnboardingGate.Needed)
                }
            }
        }
    }

@HiltViewModel
class OnboardingGateViewModel @Inject constructor(
    private val settings: SettingsRepository,
    subscriptionDao: SubscriptionDao,
) : ViewModel() {
    private val finished = MutableStateFlow(false)

    val gate: StateFlow<OnboardingGate> = onboardingGate(
        done = settings.onboardingDone,
        isExistingInstall = {
            shouldGrandfather(false, settings.installEvidence(subscriptionDao.getAll().size))
        },
        markDone = settings::markOnboardingDone,
        finished = finished,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingGate.Loading)

    /** The only place a fresh install gets its flag: the last step's Continue. */
    fun finish() {
        viewModelScope.launch { finishOnboarding(finished, settings::markOnboardingDone) }
    }
}

/**
 * Enters the app first, persists second: a failed write must neither block
 * the user nor crash. The next launch simply asks again.
 */
internal suspend fun finishOnboarding(finished: MutableStateFlow<Boolean>, markDone: suspend () -> Unit) {
    finished.value = true
    try {
        markDone()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SecureLog.w("Onboarding", "could not persist the onboarding flag", e)
    }
}

/**
 * Root content while the gate is not [OnboardingGate.Done]. The step lives with
 * the caller (rememberSaveable) so it survives rotation, locale recreate and
 * process death; the flag is written only on finish.
 */
@Composable
internal fun OnboardingHost(
    gate: OnboardingGate,
    step: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (gate == OnboardingGate.Loading) {
        Box(modifier.fillMaxSize().background(AfterglowTheme.colors.paper))
        return
    }
    val sdk = Build.VERSION.SDK_INT
    val context = LocalContext.current
    // Snapshot once: Allow flips the live permission, which must not drop the step mid-flow.
    val granted = rememberSaveable {
        sdk >= OnboardingSteps.NOTIFICATIONS_MIN_SDK &&
            ContextCompat.checkSelfPermission(context, NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
    val advance = { advanceFrom(step, sdk, granted, onStepChange, onFinish) }
    // Any result, including a refusal or a permanent denial, moves on: no retry loop.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    val previous = OnboardingSteps.previous(step, sdk, granted)
    // Step 1: no handler, so system Back leaves the app.
    BackHandler(enabled = previous != null) { previous?.let(onStepChange) }
    OnboardingScreen(
        step = step,
        onContinue = {
            if (step == OnboardingStep.Notifications) permission.launch(NOTIFICATIONS_PERMISSION) else advance()
        },
        onBack = previous?.let { { onStepChange(it) } },
        modifier = modifier,
        onNotNow = advance,
        notificationsGranted = granted,
    )
}

// Guarded by the SDK check: only read when sdk >= NOTIFICATIONS_MIN_SDK.
@SuppressLint("InlinedApi")
private const val NOTIFICATIONS_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
