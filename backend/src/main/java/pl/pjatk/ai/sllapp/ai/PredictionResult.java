package pl.pjatk.ai.sllapp.ai;

public sealed interface PredictionResult {
    record Prediction(char letter, double confidence) implements PredictionResult {}

    record Failure(FailureKind kind, Integer httpStatus, String detail) implements PredictionResult {}

    enum FailureKind {
        DISABLED,
        INVALID_REQUEST,
        REJECTED_SAMPLE,
        PAYLOAD_TOO_LARGE,
        SERVICE_ERROR,
        UNEXPECTED_STATUS,
        TIMEOUT,
        TRANSPORT_ERROR,
        CONTRACT_ERROR,
        OVERLOADED
    }
}
