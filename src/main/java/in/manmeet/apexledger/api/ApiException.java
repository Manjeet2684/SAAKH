package in.manmeet.apexledger.api;

public class ApiException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public ApiException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, message, 400);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(code, message, 404);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(code, message, 409);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(code, message, 422);
    }
}
