package in.manmeet.apexledger.api;

import org.slf4j.MDC;

public record ErrorResponse(String code, String message, String requestId) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
