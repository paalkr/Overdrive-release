package com.overdrive.app.onboarding

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbDaemonLauncher

/**
 * Drives the two-track onboarding guide: owns the [OnboardingOverlayView] lifecycle, the
 * parked-only [OnboardingGate], a cached encoder-busy signal, and the ordered novice
 * spine. The wizards (camera / vehicle / dashboard) are delegated to dedicated coaches
 * that the host hands the overlay to.
 *
 * SEQUENCING: launched from MainActivity AFTER the PIN gate and AFTER SetupGuideDialog,
 * only when [OnboardingState.shouldAutoRunNovice] and the parked gate allow. The overlay
 * attaches to the Activity content root; the camera/vehicle wizards re-parent the overlay
 * onto the relevant dialog window so coachmarks sit ABOVE the MaterialAlertDialog.
 *
 * Step 0 (daemon auth) persists in app-private prefs and is the only hard gate. Everything
 * else is skippable or resumable; nothing here can fire while driving (ACC-ON dismiss).
 */
class OnboardingHost(
    private val activity: Activity,
    private val adbLauncher: AdbDaemonLauncher,
) {
    private val state = OnboardingState.get(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: OnboardingOverlayView? = null
    private var gate: OnboardingGate? = null
    private var started = false
    private var activeCameraCoach: CameraWizardCoach? = null
    private var replayMode = false   // replay re-runs novice basics even if complete
    private var expertMode = false   // launch straight into the Expert track

    // Cached "is the camera daemon (encoder) running?" — refreshed off the main thread
    // every ENCODER_POLL_MS and read synchronously by the overlay's emphasis gate so a
    // decorative pulse never competes with the H.265 encoder on the shared bus.
    @Volatile private var encoderBusyCached = false
    private val encoderPoll = object : Runnable {
        override fun run() {
            try {
                adbLauncher.isDaemonRunning { running -> encoderBusyCached = running }
            } catch (t: Throwable) {
                Log.w(TAG, "encoder poll failed: ${t.message}")
            }
            mainHandler.postDelayed(this, ENCODER_POLL_MS)
        }
    }

    // ---- public lifecycle ------------------------------------------------------------

    /**
     * Start the novice track if it should run and we're parked. Idempotent — safe to call
     * from onResume. Returns true if the overlay was shown.
     */
    fun startIfNeeded(): Boolean {
        if (started) return false
        if (!state.shouldAutoRunNovice()) return false
        return start()
    }

    /** Force-start (replay "?" affordance). Ignores onboardingComplete, still parked-gated. */
    fun startReplay() {
        if (started) dismiss()
        replayMode = true
        start()
    }

    /** Launch the Expert track directly (from the novice "Explore Setup" CTA). */
    fun startExpertTour() {
        if (started) dismiss()
        expertMode = true
        start()
    }

    private fun start(): Boolean {
        val gate = OnboardingGate(activity) { dismiss() }
        if (!gate.canShow()) return false
        this.gate = gate
        gate.register()

        val ov = OnboardingOverlayView(activity, encoderBusy = { encoderBusyCached })
        ov.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        contentRoot().addView(ov)
        overlay = ov
        started = true

        mainHandler.post(encoderPoll)
        Log.i(TAG, "Onboarding started (expert=$expertMode replay=$replayMode)")
        if (expertMode) launchExpertTrack() else routeFirstStep()
        return true
    }

    private fun launchExpertTrack() {
        val ov = overlay ?: return
        ExpertTourCoach(activity, ov, state) {
            dismiss()
        }.begin()
    }

    /** Tear everything down (ACC-ON, completion, or replay restart). */
    fun dismiss() {
        mainHandler.removeCallbacks(encoderPoll)
        // Cancel the camera coach FIRST so its ~10s applying animator can't keep running
        // against a detached overlay (transient Activity leak) after we remove the view.
        activeCameraCoach?.cancel(); activeCameraCoach = null
        gate?.unregister(); gate = null
        overlay?.let { ov -> (ov.parent as? ViewGroup)?.removeView(ov) }
        overlay = null
        started = false
        expertMode = false
        replayMode = false
    }

    /** Called from MainActivity.onAuthGranted — advances Step 0 if we're waiting on it. */
    fun onDaemonAuthGranted() {
        state.daemonAuthorized = true
        if (started && currentStep == Step.AUTHORIZE) {
            mainHandler.post { advanceFromAuthorize() }
        }
    }

    /**
     * Forwarded from MainActivity.onConfigurationChanged (the Activity uses configChanges,
     * so it doesn't recreate on rotation). The overlay recomputes its responsive width +
     * re-resolves the live anchor's new bounds; the card width and the spotlight cutout
     * are otherwise frozen to the launch orientation.
     */
    fun onConfigChanged() {
        if (!started) return
        overlay?.onConfigChanged()
    }

    // ---- step machine ----------------------------------------------------------------

    private enum class Step { AUTHORIZE, CAMERA, VEHICLE, DASHBOARD, DONE }
    private var currentStep = Step.AUTHORIZE

    private fun routeFirstStep() {
        // If the daemon is ALREADY running (already-installed device / auth granted on a
        // prior launch / the OS remembered "Always allow"), the system ADB popup will
        // never re-appear — so Step 0 must NOT wait for an edge that already passed.
        // Detect it up front and skip straight past AUTHORIZE.
        adbLauncher.isDaemonRunning { running ->
            mainHandler.post {
                if (!started) return@post
                if (running) state.daemonAuthorized = true
                currentStep = when {
                    !state.daemonAuthorized -> Step.AUTHORIZE
                    state.cameraStep != OnboardingState.CameraStep.SAVED_OK -> Step.CAMERA
                    !state.vehicleStepDone -> Step.VEHICLE
                    !state.dashboardTourDone -> Step.DASHBOARD
                    else -> Step.DONE
                }
                renderCurrent()
            }
        }
    }

    private fun renderCurrent() {
        val ov = overlay ?: return
        when (currentStep) {
            Step.AUTHORIZE -> renderAuthorize(ov)
            Step.CAMERA -> launchCameraChapter()
            Step.VEHICLE -> launchVehicleChapter()
            Step.DASHBOARD -> launchDashboardChapter()
            Step.DONE -> renderDone(ov)
        }
    }

    // ---- Step 0: authorize daemon ----------------------------------------------------

    private fun renderAuthorize(ov: OnboardingOverlayView) {
        ov.showCentered()
        if (state.daemonAuthorized) { advanceFromAuthorize(); return }

        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_title),
            body = activity.getString(R.string.onboarding_authorize_body),
            primaryText = activity.getString(R.string.onboarding_authorize_button),
            onPrimary = {
                // The system ADB popup is OS-owned (we can't draw over it). Daemon
                // startup already triggers it; here we just narrate + wait for the
                // onAuthGranted callback (routed via MainActivity → onDaemonAuthGranted).
                ov.bindStep(
                    title = activity.getString(R.string.onboarding_authorize_waiting_title),
                    body = activity.getString(R.string.onboarding_authorize_waiting_body),
                    primaryText = null, onPrimary = null,
                    // ALWAYS-AVAILABLE ESCAPE: the user must never be locked behind the
                    // waiting state (e.g. already-installed device where no popup appears).
                    secondaryText = activity.getString(R.string.onboarding_dismiss),
                    onSecondary = { dismiss() },
                )
                // Failsafe: if the grant never arrives (popup dismissed / already authed),
                // re-offer with a Try-again + Dismiss instead of hanging.
                mainHandler.postDelayed({
                    if (started && currentStep == Step.AUTHORIZE && !state.daemonAuthorized) {
                        renderAuthorizeRetry(ov)
                    }
                }, AUTH_WAIT_MS)
            },
            // Escape on the very first auth card too.
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun renderAuthorizeRetry(ov: OnboardingOverlayView) {
        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_retry_title),
            body = activity.getString(R.string.onboarding_authorize_retry_body),
            primaryText = activity.getString(R.string.onboarding_authorize_retry_button),
            onPrimary = { renderAuthorize(ov) },
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun advanceFromAuthorize() {
        currentStep = Step.CAMERA
        renderCurrent()
    }

    // ---- chapter launchers (delegated to coaches) ------------------------------------

    private fun launchCameraChapter() {
        val ov = overlay ?: return
        val coach = CameraWizardCoach(activity, ov, state, adbLauncher) { outcome ->
            // Camera chapter always advances to vehicle whether saved or deferred —
            // it is strongly encouraged, not a hard gate (Auto-detect is a valid baseline).
            activeCameraCoach = null
            currentStep = Step.VEHICLE
            renderCurrent()
        }
        activeCameraCoach = coach
        coach.begin()
    }

    private fun launchVehicleChapter() {
        val ov = overlay ?: return
        VehicleWizardCoach(activity, ov, state) {
            currentStep = Step.DASHBOARD
            renderCurrent()
        }.begin()
    }

    private fun launchDashboardChapter() {
        val ov = overlay ?: return
        DashboardTourCoach(activity, ov, state) {
            currentStep = Step.DONE
            renderCurrent()
        }.begin()
    }

    // ---- completion ------------------------------------------------------------------

    private fun renderDone(ov: OnboardingOverlayView) {
        ov.showCentered()
        ov.pulseAttention()
        ov.bindStep(
            title = activity.getString(R.string.onboarding_done_title),
            body = activity.getString(R.string.onboarding_done_body),
            primaryText = activity.getString(R.string.onboarding_done_primary),
            onPrimary = {
                state.onboardingComplete = true
                state.expertTourEntry = EXPERT_ENTRY_CAMERA_ADVANCED
                dismiss()
            },
            secondaryText = activity.getString(R.string.onboarding_done_secondary),
            onSecondary = {
                // Hand straight into the Expert track (starts at its first chapter).
                state.onboardingComplete = true
                startExpertTour()
            },
        )
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun contentRoot(): ViewGroup =
        activity.findViewById(android.R.id.content)

    private companion object {
        const val TAG = "OnboardingHost"
        const val ENCODER_POLL_MS = 3000L
        const val AUTH_WAIT_MS = 20000L
        const val EXPERT_ENTRY_CAMERA_ADVANCED = "cameraAdvanced"
    }
}
