package pl.pjatk.ai.sllapp.web.websocket.message;

public record ErrorMessage(String type, String code, String message) {
    public static ErrorMessage of(String code, String message) {
        return new ErrorMessage("error", code, message);
    }
}
