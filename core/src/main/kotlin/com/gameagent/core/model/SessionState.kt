package com.gameagent.core.model

/**
 * Lifecycle of one capture/decide/act session.
 *
 * The app never has more than one session; [SessionState] is the single source of truth that the
 * dashboard renders and that the action validator consults before allowing any input.
 */
enum class SessionState {
    /** Nothing running. Screenshot permission may or may not have been granted. */
    IDLE,

    /** The Android screen-capture consent dialog is showing. */
    REQUESTING_PERMISSION,

    /** Capture resources are being created for an approved projection. */
    CAPTURING,

    /** A frame is being interpreted. */
    PROCESSING,

    /** Capture is live and the pipeline is ready, but the agent is not acting. */
    READY,

    /** The agent loop is running and actions may be executed. */
    RUNNING,

    /** The loop is suspended. No frames are consumed and no actions execute. */
    PAUSED,

    /** Shutting down; resources are being released. */
    STOPPING,

    /** A failure occurred. Details are in the event log. Recovery goes through IDLE. */
    ERROR,
    ;

    /** States in which capture resources are expected to be alive. */
    val holdsCaptureResources: Boolean
        get() = this == CAPTURING || this == PROCESSING || this == READY ||
            this == RUNNING || this == PAUSED || this == STOPPING

    /**
     * States in which the agent may execute an action.
     *
     * [PROCESSING] counts as active on purpose: it is a sub-state of a running session, entered from
     * [RUNNING] while a frame is being interpreted, and it returns to [RUNNING] when the step ends.
     * The transition table guarantees PROCESSING cannot be entered from a non-running state, so this
     * does not widen what can execute input - it stops the pipeline from rejecting its own actions.
     */
    val allowsInput: Boolean get() = this == RUNNING || this == PROCESSING
}
