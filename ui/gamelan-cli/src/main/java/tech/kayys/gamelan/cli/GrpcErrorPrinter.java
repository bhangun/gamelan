package tech.kayys.gamelan.cli;

import io.grpc.StatusRuntimeException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GrpcErrorPrinter {

    private static final Pattern CODE_PATTERN = Pattern.compile("^([A-Z_]+_\\d{3}):\\s*(.*)$");

    private GrpcErrorPrinter() {
    }

    static void print(StatusRuntimeException error) {
        String description = error.getStatus().getDescription();
        if (description == null || description.isBlank()) {
            System.err.println("gRPC error: " + error.getStatus().getCode());
            return;
        }

        Matcher matcher = CODE_PATTERN.matcher(description);
        if (matcher.matches()) {
            String code = matcher.group(1);
            String message = matcher.group(2);
            System.err.println("ErrorCode: " + code);
            System.err.println("Message: " + message);
            return;
        }

        System.err.println("gRPC error: " + description);
    }
}
