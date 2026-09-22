package com.pinkdreams.imaging.orchestration

enum class CandidateStatus {
    GENERATED,
    SHORTLISTED,
    DECLINED,
    SAVED,
    /** Admin marked face/body identity as not matching references. */
    IDENTITY_MISMATCH,
    POST_READY,
    POSTED,
    FAILED,
}
