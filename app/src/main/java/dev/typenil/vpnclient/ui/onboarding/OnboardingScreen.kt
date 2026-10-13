package dev.typenil.vpnclient.ui.onboarding

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.common.headingSemantics
import dev.typenil.vpnclient.ui.settings.AboutUrls
import dev.typenil.vpnclient.ui.theme.AfterglowButton
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.VPNClientTheme

private val ContentMaxWidth = 560.dp

/**
 * One first-run step. Stateless: the host owns [step], persists it and decides
 * what Continue / Back do. This screen never asks Android for a permission —
 * the VPN dialog stays behind the connection state machine.
 *
 * Continue is pinned under a scrolling body so it never covers text, at any
 * font scale or orientation.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    sdk: Int = Build.VERSION.SDK_INT,
    privacyPolicyUrl: String = AboutUrls.PRIVACY_POLICY.trim(),
) {
    val colors = AfterglowTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val stepState = stringResource(
        R.string.onboarding_step_state,
        OnboardingSteps.position(step, sdk),
        OnboardingSteps.count(sdk),
    )
    val brand = stringResource(R.string.app_name)

    Column(
        modifier.fillMaxSize().background(colors.paper),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The same text lives in the title's stateDescription; don't read it twice.
            Text(
                stepState,
                Modifier.clearAndSetSemantics {},
                color = colors.muted,
                style = MaterialTheme.typography.labelLarge,
            )
            if (step == OnboardingStep.Welcome) {
                Image(painterResource(R.drawable.afterglow_mascot), contentDescription = null, Modifier.size(96.dp))
            }
            Text(
                stepTitle(step, brand),
                Modifier.headingSemantics().semantics { stateDescription = stepState },
                color = colors.ink,
                style = MaterialTheme.typography.headlineSmall,
            )
            stepBody(step, brand).forEach {
                Text(it, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
            }
            if (step == OnboardingStep.VpnDisclosure && privacyPolicyUrl.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(privacyPolicyUrl) } },
                    Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.about_privacy_policy)) }
            }
        }
        Column(
            Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AfterglowButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_continue))
            }
            if (onBack != null) {
                TextButton(onClick = onBack, Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_back), color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun stepTitle(step: OnboardingStep, brand: String): String = when (step) {
    OnboardingStep.Welcome -> stringResource(R.string.onboarding_welcome_title, brand)
    OnboardingStep.Subscription -> stringResource(R.string.onboarding_subscription_title)
    OnboardingStep.VpnDisclosure -> stringResource(R.string.onboarding_vpn_title)
    OnboardingStep.Notifications -> stringResource(R.string.onboarding_notifications_title)
}

@Composable
private fun stepBody(step: OnboardingStep, brand: String): List<String> = when (step) {
    OnboardingStep.Welcome -> listOf(stringResource(R.string.onboarding_welcome_body, brand))
    OnboardingStep.Subscription -> listOf(
        stringResource(R.string.onboarding_subscription_body),
        stringResource(R.string.onboarding_subscription_hwid),
    )
    OnboardingStep.VpnDisclosure -> listOf(
        stringResource(R.string.onboarding_vpn_route),
        stringResource(R.string.onboarding_vpn_privacy),
        stringResource(R.string.onboarding_vpn_checks),
    )
    OnboardingStep.Notifications -> listOf(stringResource(R.string.onboarding_notifications_body))
}

@Composable
private fun PreviewStep(step: OnboardingStep, url: String = "") = VPNClientTheme {
    OnboardingScreen(step, onContinue = {}, onBack = if (step == OnboardingStep.Welcome) null else ({}), sdk = 34, privacyPolicyUrl = url)
}

@Preview(name = "Onboarding welcome", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun WelcomePreview() = PreviewStep(OnboardingStep.Welcome)

@Preview(name = "Onboarding disclosure", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DisclosurePreview() = PreviewStep(OnboardingStep.VpnDisclosure, url = "https://example.com/privacy")

@Preview(name = "Onboarding disclosure 2.0x", showBackground = true, widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun DisclosureLargeFontPreview() = PreviewStep(OnboardingStep.VpnDisclosure)
