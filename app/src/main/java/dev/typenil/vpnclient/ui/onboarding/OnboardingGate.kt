package dev.typenil.vpnclient.ui.onboarding

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.data.settings.shouldGrandfather
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** What the root shows: a neutral frame, first-run screens, or the app. */
enum class OnboardingGate { Loading, Needed, Done }

/**
 * Gate state from the persisted flag. A missing flag first asks
 * [isExistingInstall] — an update of a used install is marked done silently
 * instead of showing first-run screens. Nothing here writes the flag for a
 * fresh install; only [OnboardingGateViewModel.finish] does.
 */
internal fun onboardingGate(
    done: Flow<Boolean?>,
    isExistingInstall: suspend () -> Boolean,
    markDone: suspend () -> Unit,
): Flow<OnboardingGate> = done.distinctUntilChanged().transformLatest { flag ->
    when (flag) {
        null -> emit(OnboardingGate.Loading)
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
    val gate: StateFlow<OnboardingGate> = onboardingGate(
        done = settings.onboardingDone,
        isExistingInstall = {
            shouldGrandfather(false, settings.installEvidence(subscriptionDao.getAll().size))
        },
        markDone = settings::markOnboardingDone,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingGate.Loading)

    /** The only place a fresh install gets its flag: the last step's Continue. */
    fun finish() {
        viewModelScope.launch { settings.markOnboardingDone() }
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
    val previous = OnboardingSteps.previous(step, sdk)
    // Step 1: no handler, so system Back leaves the app.
    BackHandler(enabled = previous != null) { previous?.let(onStepChange) }
    OnboardingScreen(
        step = step,
        onContinue = { OnboardingSteps.next(step, sdk)?.let(onStepChange) ?: onFinish() },
        onBack = previous?.let { { onStepChange(it) } },
        modifier = modifier,
    )
}
