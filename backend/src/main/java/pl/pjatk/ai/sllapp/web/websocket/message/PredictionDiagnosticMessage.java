package pl.pjatk.ai.sllapp.web.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import pl.pjatk.ai.sllapp.ai.PredictionResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionDiagnosticMessage(String type, long seq, long positionId, long elapsedMs, Disposition disposition,
                                          Character letter, Double confidence, Integer httpStatus, String failure) {
    public enum Disposition { PROCESSED, POSITION_COMPLETED, TOO_LATE, IGNORED }

    public static PredictionDiagnosticMessage from(SampleMessage sample, PredictionResult result, long elapsedMs,
                                                    Disposition disposition) {
        return switch (result) {
            case PredictionResult.Prediction prediction -> new PredictionDiagnosticMessage("prediction_result",
                    sample.seq(), sample.positionId(), elapsedMs, disposition, prediction.letter(),
                    prediction.confidence(), 200, null);
            case PredictionResult.Failure failure -> new PredictionDiagnosticMessage("prediction_result",
                    sample.seq(), sample.positionId(), elapsedMs, disposition, null, null,
                    failure.httpStatus(), failure.kind().name());
            case null -> new PredictionDiagnosticMessage("prediction_result", sample.seq(), sample.positionId(),
                    elapsedMs, disposition, null, null, null, "INTERNAL_ERROR");
        };
    }
}
